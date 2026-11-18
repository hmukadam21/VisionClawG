/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - glasses camera streaming (DAT SDK 1.0.1) + VisionClaw frame forwarding
//
// SDK 1.0.1 lifecycle:
//   1. Wearables.createSession(deviceSelector)  -> DeviceSession
//   2. session.start()                          -> wait for DeviceSessionState.STARTED
//   3. session.addCamera(StreamConfiguration)   -> Camera, whose .stream is the video Stream
//   4. stream.start()                           -> frames arrive on stream.videoStream
//   5. stop: camera.close(), session.stop()
//
// Pocket mode (v1.2): the stream is requested COMPRESSED (HEVC). The SDK keeps a compressed
// stream flowing when the phone is locked or the app is in the background, while a raw stream
// pauses. GlassesFrameDecoder turns the HEVC frames back into Bitmaps (a few per second), which
// are shown on screen and forwarded to Gemini (~1 per second) and WebRTC.
//
// On-demand camera (v1.6, the default): like Meta AI, the camera is OFF. The glasses session is
// opened without a camera. When Gemini calls look_through_glasses (or the photo button is
// tapped), lookNow() adds a camera, starts a small stream (the SDK's photo rides a running
// stream), takes ONE photo with stream.capturePhoto(), and switches the camera off again.
// If the session ends meanwhile (glasses folded, link lost), the voice carries on and the next
// look opens a new session. In live mode the same tool takes a sharp photo from the running
// stream, falling back to the newest video picture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioFrame
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.LookResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()

    // Valid SDK frame rates: 2, 7, 15, 24, 30. Gemini looks about once a second, so 7 fps from
    // the glasses is plenty and costs the glasses battery far less than 24 or 30.
    private const val FRAME_RATE = 7

    // Decoded pictures turned into Bitmaps at most every 250 ms (~4 per second) for the on-screen
    // preview; Gemini takes one per second of those (throttled in GeminiSessionViewModel).
    private const val PICTURE_INTERVAL_MS = 250L

    // On-demand look: waits (the photo itself took about 1 s in Meta's own sample app)
    private const val SESSION_START_TIMEOUT_MS = 10_000L
    private const val CAMERA_START_TIMEOUT_MS = 10_000L
    private const val PHOTO_TIMEOUT_MS = 15_000L
    private const val CAMERA_STOP_TIMEOUT_MS = 3_000L
    private const val FRAME_FALLBACK_WAIT_MS = 1_500L

    // Photos are decoded at no more than about this size (a full photo would be ~48 MB in memory)
    private const val KEEP_MAX_EDGE = 2048
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  // DAT SDK 1.0.1 objects
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  @Volatile private var frameDecoder: GlassesFrameDecoder? = null

  // Frame handling runs off the main thread, one frame at a time, in order.
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null
  private var audioJob: Job? = null
  @Volatile private var audioArrivalLogged = false

  // True while this view model is ending the stream itself, so the STOPPED states that follow
  // are not treated as "the glasses went away".
  private var stopping = false

  // Camera mode of the current run (read from Settings when the stream starts)
  @Volatile private var onDemand = false
  private val sessionState = MutableStateFlow<DeviceSessionState?>(null)

  // On-demand look: one at a time; the previous camera is fully off before the next one
  private val lookMutex = Mutex()
  @Volatile private var lookCamera: Camera? = null
  // Kept from when the camera was added: after camera.stop() the SDK throws on camera.stream
  @Volatile private var lookCameraStream: Stream? = null
  @Volatile private var lookDecoder: GlassesFrameDecoder? = null
  @Volatile private var lastLookFrame: Bitmap? = null
  private val lookCameraJobs = mutableListOf<Job>()
  @Volatile private var lookTeardownJob: Job? = null

  // VisionClaw additions
  var geminiViewModel: GeminiSessionViewModel? = null
    set(value) {
      field = value
      // Gemini's look_through_glasses tool takes its photo here
      value?.lookProvider = { lookNow() }
    }
  var webrtcViewModel: WebRTCSessionViewModel? = null
  private var phoneCameraManager: PhoneCameraManager? = null

  private var heartbeatJob: Job? = null

  init {
    // "Stop" tapped in the notification: end Gemini, the live share and the glasses stream.
    viewModelScope.launch {
      StreamingService.stopRequests.collect {
        DebugLog.log("STREAM", "Stop requested from notification")
        geminiViewModel?.stopSession("notification Stop")
        webrtcViewModel?.stopSession()
        stopStream("notification Stop")
        wearablesViewModel.navigateToDeviceSelection()
      }
    }
  }

  // Every 30 s: how much flowed through each link since the last line (see DebugLog).
  private fun startHeartbeat() {
    heartbeatJob?.cancel()
    heartbeatJob =
        viewModelScope.launch {
          while (true) {
            delay(30_000)
            val gemini = geminiViewModel?.uiState?.value
            DebugLog.heartbeat(
                "stream=${_uiState.value.streamSessionState} mode=${_uiState.value.streamingMode} " +
                    "camera=${if (onDemand) "on-demand" else "live"} session=${sessionState.value} " +
                    "geminiActive=${gemini?.isGeminiActive} gemini=${gemini?.connectionState}"
            )
          }
        }
  }

  // MARK: - Glasses streaming

  fun startStream() {
    stopStream("starting a new stream")
    stopping = false
    onDemand = SettingsManager.cameraOnDemand
    DebugLog.log(
        "STREAM",
        "starting glasses session (camera ${if (onDemand) "ON DEMAND" else "LIVE"})",
    )
    startHeartbeat()

    // Foreground service keeps glasses link + microphone alive while the screen is off
    StreamingService.start(getApplication())
    _uiState.update {
      it.copy(
          streamingMode = StreamingMode.GLASSES,
          streamSessionState = StreamState.STARTING,
          cameraOnDemand = onDemand,
      )
    }

    if (!openSession()) leaveStreaming("createSession failed")
  }

  /** Creates and starts a glasses session (state arrives in observeSession). */
  private fun openSession(): Boolean {
    var opened = false
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no state change is missed
          observeSession(created)
          created.start()
          opened = true
        }
        .onFailure { error, _ ->
          DebugLog.log("STREAM", "createSession FAILED: ${error.description}")
          wearablesViewModel.setRecentError("Could not connect to the glasses: ${error.description}")
        }
    return opened
  }

  /** Stops the glasses session and forgets it (the on-demand look opens a new one). */
  private fun closeSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    runCatching { session?.stop() }.onFailure { Log.w(TAG, "session stop failed", it) }
    session = null
    sessionState.value = null
  }

  private fun observeSession(currentSession: DeviceSession) {
    sessionStateJob =
        viewModelScope.launch {
          currentSession.state.collect { state ->
            DebugLog.log("STREAM", "glasses session state: $state")
            sessionState.value = state
            when (state) {
              DeviceSessionState.STARTED ->
                  if (onDemand) {
                    // Camera stays off until a look
                    if (lookCamera == null) {
                      _uiState.update { it.copy(streamSessionState = StreamState.STOPPED) }
                    }
                    DebugLog.log("STREAM", "glasses ready, camera off until Gemini needs to look")
                  } else {
                    // A camera can only be added once the session is STARTED
                    attachCamera(currentSession)
                  }
              // STOPPED is terminal (the glasses were removed, folded, disconnected, ...)
              DeviceSessionState.STOPPED ->
                  if (!stopping) {
                    if (onDemand) {
                      // The voice carries on; the next look opens a new session
                      _uiState.update { it.copy(streamSessionState = StreamState.STOPPED) }
                      DebugLog.log("STREAM", "glasses session ended; the next look reconnects")
                    } else {
                      leaveStreaming("glasses session stopped")
                    }
                  }
              else -> Unit
            }
          }
        }
    sessionErrorJob =
        viewModelScope.launch {
          currentSession.errors.collect { error ->
            DebugLog.log("STREAM", "glasses session error: ${error.description}")
            wearablesViewModel.setRecentError(error.description)
          }
        }
  }

  private fun attachCamera(currentSession: DeviceSession) {
    if (camera != null) return
    // Glasses microphone audio rides along with the video (16 kHz mono PCM, Gemini's format)
    val withAudio =
        SettingsManager.glassesMicEnabled && wearablesViewModel.uiState.value.glassesMicGranted
    geminiViewModel?.glassesAudioExpected = withAudio
    DebugLog.log(
        "STREAM",
        "adding camera (compressed HEVC, MEDIUM, $FRAME_RATE fps, glasses mic audio=$withAudio)",
    )
    frameDecoder?.release()
    frameDecoder = GlassesFrameDecoder(PICTURE_INTERVAL_MS) { bitmap -> onPicture(bitmap) }
    audioArrivalLogged = false
    currentSession
        .addCamera(
            StreamConfiguration(
                audioCodec =
                    if (withAudio) AudioCodec.PCM(AudioSampleRate.RATE_16000, 1) else null,
                videoQuality = VideoQuality.MEDIUM,
                frameRate = FRAME_RATE,
                // Compressed: keeps flowing with the phone locked / app in background.
                compressVideo = true,
            )
        )
        .onSuccess { addedCamera ->
          camera = addedCamera
          val addedStream = addedCamera.stream
          stream = addedStream
          // Subscribe before start() so no state change is missed
          observeStream(addedStream)
          if (withAudio) {
            audioJob =
                viewModelScope.launch(Dispatchers.IO) {
                  addedStream.audioStream.collect { handleAudioFrame(it) }
                }
          }
          addedStream.start().onFailure { error, _ ->
            DebugLog.log("STREAM", "stream start FAILED: ${error.description}")
            failAndLeave("Could not start the glasses camera: ${error.description}")
          }
        }
        .onFailure { error, _ ->
          DebugLog.log("STREAM", "addCamera FAILED: ${error.description}")
          failAndLeave("Could not open the glasses camera: ${error.description}")
        }
  }

  private fun observeStream(currentStream: Stream) {
    videoJob =
        viewModelScope.launch(frameDispatcher) {
          currentStream.videoStream.collect { handleVideoFrame(it) }
        }
    streamStateJob =
        viewModelScope.launch {
          // The stream replays STOPPED when we subscribe (before start()), so only a STOPPED or
          // CLOSED that comes after an active state means the stream really ended.
          var hasBeenActive = false
          currentStream.state.collect { state ->
            DebugLog.log("STREAM", "video stream state: $state")
            val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
            if (!isTerminal) {
              hasBeenActive = true
              _uiState.update { it.copy(streamSessionState = state) }
            } else if (hasBeenActive) {
              hasBeenActive = false
              if (!stopping) leaveStreaming("video stream ended ($state)")
            }
          }
        }
    streamErrorJob =
        viewModelScope.launch {
          currentStream.errorStream.collect { error ->
            DebugLog.log("STREAM", "video stream error: ${error.description}")
            wearablesViewModel.setRecentError(error.description)
          }
        }
  }

  private fun leaveStreaming(reason: String) {
    stopStream(reason)
    wearablesViewModel.navigateToDeviceSelection()
  }

  private fun failAndLeave(message: String) {
    wearablesViewModel.setRecentError(message)
    leaveStreaming(message)
  }

  // MARK: - Phone camera mode (unchanged)

  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    onDemand = false
    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager

    manager.onFrameCaptured = { bitmap ->
      _uiState.update { it.copy(videoFrame = bitmap) }
      // Forward to Gemini (throttled inside the VM)
      geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
      // Forward to WebRTC (every frame)
      webrtcViewModel?.pushVideoFrame(bitmap)
    }

    _uiState.update {
      it.copy(
          streamingMode = StreamingMode.PHONE,
          streamSessionState = StreamState.STREAMING,
      )
    }
    manager.start(lifecycleOwner)
    DebugLog.log("STREAM", "phone camera mode started")
    startHeartbeat()
  }

  // MARK: - Stop

  fun stopStream(reason: String = "Stop button / screen closed") {
    if (session != null || phoneCameraManager != null) DebugLog.log("STREAM", "stopping: $reason")
    stopping = true
    heartbeatJob?.cancel()
    heartbeatJob = null

    // Stop foreground service
    StreamingService.stop(getApplication())

    videoJob?.cancel()
    videoJob = null
    audioJob?.cancel()
    audioJob = null
    geminiViewModel?.glassesAudioExpected = false
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null

    frameDecoder?.release()
    frameDecoder = null

    // On-demand look camera, if one is on right now
    lookTeardownJob?.cancel()
    lookTeardownJob = null
    releaseLookCamera()

    // Closing the camera stops its stream; stopping the session releases the glasses.
    runCatching { camera?.close() }.onFailure { Log.w(TAG, "camera close failed", it) }
    camera = null
    stream = null
    closeSession()

    phoneCameraManager?.stop()
    phoneCameraManager = null
    _uiState.update { INITIAL_STATE }
  }

  // MARK: - Photo capture

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    // On-demand camera: the photo button takes one look, then offers to share it
    if (onDemand && uiState.value.streamingMode == StreamingMode.GLASSES) {
      _uiState.update { it.copy(isCapturing = true) }
      viewModelScope.launch {
        DebugLog.log("LOOK", "photo button tapped")
        when (val result = lookNow()) {
          is LookResult.Photo ->
              _uiState.update {
                it.copy(
                    isCapturing = false,
                    capturedPhoto = result.bitmap,
                    isShareDialogVisible = true,
                )
              }
          is LookResult.Failed -> {
            DebugLog.looksFailed.incrementAndGet()
            DebugLog.log("LOOK", "photo button look FAILED: ${result.reason}")
            _uiState.update { it.copy(isCapturing = false) }
            wearablesViewModel.setRecentError("Photo failed: ${result.reason}")
          }
        }
      }
      return
    }

    if (uiState.value.streamSessionState == StreamState.STREAMING) {
      // Phone mode: capture current video frame as photo
      if (uiState.value.streamingMode == StreamingMode.PHONE) {
        uiState.value.videoFrame?.let { frame ->
          _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
        }
        return
      }

      val currentStream = stream ?: return
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        currentStream
            .capturePhoto()
            .onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            .onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
              _uiState.update { it.copy(isCapturing = false) }
              wearablesViewModel.setRecentError("Photo failed: ${error.description}")
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  // MARK: - Look (one sharp photo for Gemini: look_through_glasses)

  /** Takes one sharp photo through the glasses. Never throws; failures come back as Failed. */
  suspend fun lookNow(): LookResult =
      lookMutex.withLock {
        DebugLog.looks.incrementAndGet()
        // The previous look's camera must be fully off before a new one is added
        lookTeardownJob?.join()
        try {
          when {
            _uiState.value.streamingMode == StreamingMode.PHONE ->
                _uiState.value.videoFrame?.let { LookResult.Photo(it) }
                    ?: LookResult.Failed("no picture from the phone camera yet")
            !onDemand -> liveLook()
            else -> onDemandLook()
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          DebugLog.log("LOOK", "look error: ${e.javaClass.simpleName}: ${e.message}")
          LookResult.Failed("the glasses camera had an error")
        }
      }

  /** Live mode: a photo from the running stream, or else the newest video picture. */
  private suspend fun liveLook(): LookResult {
    val currentStream = stream
    if (currentStream != null && _uiState.value.streamSessionState == StreamState.STREAMING) {
      takeStreamPhoto(currentStream)?.let { return LookResult.Photo(it) }
    }
    val frame = _uiState.value.videoFrame ?: return LookResult.Failed("no picture from the glasses yet")
    DebugLog.log("LOOK", "using the newest video picture instead (${frame.width}x${frame.height})")
    return LookResult.Photo(frame)
  }

  /** On-demand mode: camera on, one photo, camera off (switched off in the background). */
  private suspend fun onDemandLook(): LookResult {
    val startedAt = SystemClock.elapsedRealtime()
    _uiState.update { it.copy(isLooking = true) }
    try {
      val currentSession =
          ensureSessionStarted() ?: return LookResult.Failed("the glasses are not connected")
      val sessionMs = SystemClock.elapsedRealtime() - startedAt

      val lookStream = startLookCamera(currentSession)
          ?: return LookResult.Failed("the glasses camera could not be switched on")
      DebugLog.log(
          "LOOK",
          "camera on after ${SystemClock.elapsedRealtime() - startedAt} ms (glasses ready after $sessionMs ms)",
      )

      val photo = takeStreamPhoto(lookStream)
      if (photo != null) {
        _uiState.update { it.copy(videoFrame = photo) }
        DebugLog.log("LOOK", "look done in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return LookResult.Photo(photo)
      }

      // No photo: the small video picture is better than nothing
      val frame =
          lastLookFrame
              ?: withTimeoutOrNull(FRAME_FALLBACK_WAIT_MS) {
                while (lastLookFrame == null) delay(100)
                lastLookFrame
              }
      return if (frame != null) {
        DebugLog.log("LOOK", "using a video picture instead (${frame.width}x${frame.height})")
        LookResult.Photo(frame)
      } else {
        LookResult.Failed("the photo did not arrive from the glasses")
      }
    } finally {
      _uiState.update { it.copy(isLooking = false) }
      // Camera off in the background so Gemini gets the photo without waiting for it
      if (lookCamera != null) lookTeardownJob = viewModelScope.launch { switchOffLookCamera() }
    }
  }

  /** The glasses session, (re)opened if needed and STARTED, or null. */
  private suspend fun ensureSessionStarted(): DeviceSession? {
    val state = sessionState.value
    if (session == null ||
        state == DeviceSessionState.STOPPED ||
        state == DeviceSessionState.STOPPING) {
      DebugLog.log("LOOK", "opening a new glasses session (was $state)")
      closeSession()
      if (!openSession()) return null
    }
    val started =
        withTimeoutOrNull(SESSION_START_TIMEOUT_MS) {
          sessionState.first { it == DeviceSessionState.STARTED }
        }
    if (started == null) {
      DebugLog.log("LOOK", "glasses session not ready after ${SESSION_START_TIMEOUT_MS / 1000} s (${sessionState.value})")
      return null
    }
    return session
  }

  /** Adds a camera with a small stream and waits until it is STREAMING. Returns the stream. */
  private suspend fun startLookCamera(currentSession: DeviceSession): Stream? {
    var added: Camera? = null
    var addError: String? = null
    // Small stream: the photo rides it, and a small stream leaves the link to the photo
    val config =
        StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = FRAME_RATE, compressVideo = true)
    currentSession.addCamera(config).onSuccess { added = it }.onFailure { e, _ -> addError = e.description }
    if (added == null) {
      // The previous camera may still be detaching: one more try a moment later
      DebugLog.log("LOOK", "addCamera failed ($addError), trying again in 1 s")
      delay(1_000)
      addError = null
      currentSession.addCamera(config).onSuccess { added = it }.onFailure { e, _ -> addError = e.description }
    }
    val cam = added
    if (cam == null) {
      DebugLog.log("LOOK", "addCamera FAILED: $addError")
      return null
    }
    lookCamera = cam
    val lookStream = cam.stream
    lookCameraStream = lookStream

    lastLookFrame = null
    val decoder = GlassesFrameDecoder(PICTURE_INTERVAL_MS) { bitmap ->
      lastLookFrame = bitmap
      _uiState.update { it.copy(videoFrame = bitmap) }
    }
    lookDecoder = decoder
    synchronized(lookCameraJobs) {
      lookCameraJobs +=
          viewModelScope.launch(frameDispatcher) {
            lookStream.videoStream.collect { frame ->
              DebugLog.glassesFrames.incrementAndGet()
              if (frame.isCompressed) decoder.decode(frame)
            }
          }
      lookCameraJobs +=
          viewModelScope.launch {
            lookStream.state.collect { state ->
              DebugLog.log("LOOK", "camera state: $state")
              _uiState.update { it.copy(streamSessionState = state) }
            }
          }
      lookCameraJobs +=
          viewModelScope.launch {
            lookStream.errorStream.collect { DebugLog.log("LOOK", "camera error: ${it.description}") }
          }
    }

    // Subscribe before start() so STREAMING is not missed
    val streaming = viewModelScope.async { lookStream.state.first { it == StreamState.STREAMING } }
    val reached =
        try {
          var startError: String? = null
          lookStream.start().onFailure { e, _ -> startError = e.description }
          if (startError != null) {
            DebugLog.log("LOOK", "camera start FAILED: $startError")
            return null
          }
          withTimeoutOrNull(CAMERA_START_TIMEOUT_MS) { streaming.await() }
        } finally {
          streaming.cancel()
        }
    if (reached == null) {
      DebugLog.log("LOOK", "camera not streaming after ${CAMERA_START_TIMEOUT_MS / 1000} s")
      return null
    }
    return lookStream
  }

  /** One photo from a running stream (Meta's stream.capturePhoto), decoded, or null. */
  private suspend fun takeStreamPhoto(currentStream: Stream): Bitmap? {
    val startedAt = SystemClock.elapsedRealtime()
    val arrival = CompletableDeferred<PhotoData?>()
    val ask =
        viewModelScope.launch {
          currentStream
              .capturePhoto()
              .onSuccess { arrival.complete(it) }
              .onFailure { error, _ ->
                DebugLog.log("LOOK", "photo FAILED: ${error.description}")
                arrival.complete(null)
              }
        }
    val data =
        try {
          withTimeoutOrNull(PHOTO_TIMEOUT_MS) { arrival.await() }
        } finally {
          ask.cancel()
        }
    val elapsed = SystemClock.elapsedRealtime() - startedAt
    if (data == null) {
      DebugLog.log("LOOK", "no photo after $elapsed ms")
      return null
    }
    val (bitmap, description) = withContext(Dispatchers.Default) { decodePhoto(data) }
    DebugLog.log(
        "LOOK",
        "photo arrived in $elapsed ms: $description -> " +
            (bitmap?.let { "${it.width}x${it.height}" } ?: "could not be decoded"),
    )
    return bitmap
  }

  /**
   * Stops the look camera and waits until it is really off (so the next addCamera works).
   * v1.6.1: uses the stream kept from startLookCamera. Asking the camera for its stream after
   * stop() throws in the SDK, which crashed v1.6 right after every on-demand photo.
   */
  private suspend fun switchOffLookCamera() {
    val cam = lookCamera ?: return
    val camStream = lookCameraStream
    val startedAt = SystemClock.elapsedRealtime()
    try {
      // Watch for the end first (UNDISPATCHED = subscribed now, before stop() below)
      val stopped =
          camStream?.let { s ->
            viewModelScope.async(start = CoroutineStart.UNDISPATCHED) {
              s.state.first { it == StreamState.STOPPED || it == StreamState.CLOSED }
            }
          }
      val off =
          try {
            runCatching { cam.stop() }.onFailure { Log.w(TAG, "look camera stop failed", it) }
            stopped?.let { withTimeoutOrNull(CAMERA_STOP_TIMEOUT_MS) { it.await() } }
          } finally {
            stopped?.cancel()
          }
      DebugLog.log(
          "LOOK",
          "camera off after ${SystemClock.elapsedRealtime() - startedAt} ms" +
              if (off == null) " (did not confirm, closed anyway)" else "",
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Never let switching the camera off take the app down
      DebugLog.log("LOOK", "camera switch-off problem: ${e.javaClass.simpleName}: ${e.message}")
    } finally {
      releaseLookCamera()
    }
    if (onDemand && session != null) {
      _uiState.update { it.copy(streamSessionState = StreamState.STOPPED) }
    }
  }

  /** Closes the look camera at once and drops its collectors and decoder. */
  private fun releaseLookCamera() {
    val cam = lookCamera
    lookCamera = null
    lookCameraStream = null
    synchronized(lookCameraJobs) {
      lookCameraJobs.forEach { it.cancel() }
      lookCameraJobs.clear()
    }
    runCatching { cam?.close() }.onFailure { Log.w(TAG, "look camera close failed", it) }
    lookDecoder?.release()
    lookDecoder = null
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to share photo", e)
    }
  }

  // MARK: - Frames

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    DebugLog.glassesFrames.incrementAndGet()
    if (videoFrame.isCompressed) {
      // Normal path: HEVC -> decoder -> onPicture()
      frameDecoder?.decode(videoFrame)
      return
    }
    // Fallback: a raw I420 frame (only if the SDK ignored compressVideo)
    handleRawFrame(videoFrame)
  }

  /** Microphone audio from the glasses: 16-bit PCM, native byte order, mono, 16 kHz. */
  private fun handleAudioFrame(frame: AudioFrame) {
    val buffer = frame.buffer.duplicate() // leave the SDK's buffer position untouched
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (!audioArrivalLogged) {
      audioArrivalLogged = true
      DebugLog.log(
          "STREAM",
          "glasses microphone audio arriving: ${bytes.size} bytes per frame (${frame.buffer.order()})",
      )
    }
    DebugLog.glassesAudioFrames.incrementAndGet()
    var peak = 0
    var i = 0
    while (i + 1 < bytes.size) {
      val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
      val a = if (sample < 0) -sample else sample
      if (a > peak) peak = a
      i += 2
    }
    DebugLog.notePeak(DebugLog.glassesMicPeak, peak)
    geminiViewModel?.onGlassesAudio(bytes)
  }

  /** A decoded picture from the glasses (called on the decoder's thread). */
  private fun onPicture(bitmap: Bitmap) {
    DebugLog.pictures.incrementAndGet()
    _uiState.update { it.copy(videoFrame = bitmap) }
    // Forward to Gemini (throttled to ~1 per second inside the VM)
    geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
    // Forward to WebRTC
    webrtcViewModel?.pushVideoFrame(bitmap)
  }

  private fun handleRawFrame(videoFrame: VideoFrame) {
    val width = videoFrame.width
    val height = videoFrame.height
    val buffer = videoFrame.buffer
    val expectedSize = width * height * 3 / 2 // I420: Y plane + quarter-size U and V planes
    if (width <= 0 || height <= 0 || buffer.remaining() < expectedSize) {
      Log.w(TAG, "Unexpected frame: ${width}x$height, ${buffer.remaining()} bytes")
      return
    }

    val byteArray = ByteArray(expectedSize)
    val originalPosition = buffer.position()
    buffer.get(byteArray, 0, expectedSize)
    buffer.position(originalPosition)

    val nv21 = convertI420toNV21(byteArray, width, height)
    val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val jpeg =
        ByteArrayOutputStream().use { out ->
          image.compressToJpeg(Rect(0, 0, width, height), 50, out)
          out.toByteArray()
        }
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
    onPicture(bitmap)
  }

  // Convert I420 (YYYYYYYY:UU:VV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  // MARK: - Photo decoding

  private fun handlePhotoData(photo: PhotoData) {
    val (capturedPhoto, description) = decodePhoto(photo)
    DebugLog.log("STREAM", "photo button: $description")
    if (capturedPhoto == null) {
      wearablesViewModel.setRecentError("Could not read the photo from the glasses")
      return
    }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  /**
   * Decodes a glasses photo (a Bitmap, or HEIC with its EXIF rotation applied) to at most about
   * KEEP_MAX_EDGE, still above the size sent to Gemini. Returns the picture (or null) and a short
   * description of what arrived, for the log.
   */
  private fun decodePhoto(photo: PhotoData): Pair<Bitmap?, String> =
      when (photo) {
        is PhotoData.Bitmap -> {
          val original = photo.bitmap
          val longEdge = maxOf(original.width, original.height)
          val bitmap =
              if (longEdge > KEEP_MAX_EDGE) {
                val factor = KEEP_MAX_EDGE.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * factor).toInt().coerceAtLeast(1),
                    (original.height * factor).toInt().coerceAtLeast(1),
                    true,
                )
              } else {
                original
              }
          Pair(bitmap, "bitmap ${original.width}x${original.height}")
        }
        is PhotoData.HEIC -> {
          val data = photo.data.duplicate().apply { rewind() }
          val bytes = ByteArray(data.remaining())
          data.get(bytes)
          // Read the size first, then decode at a reduced size (powers of 2) to save memory
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
          var sample = 1
          while (longEdge / (sample * 2) >= GeminiConfig.PHOTO_MAX_EDGE) sample *= 2
          val decoded =
              BitmapFactory.decodeByteArray(
                  bytes,
                  0,
                  bytes.size,
                  BitmapFactory.Options().apply { inSampleSize = sample },
              )
          val bitmap = decoded?.let { applyTransform(it, getTransform(getExifInfo(bytes))) }
          Pair(bitmap, "HEIC ${bounds.outWidth}x${bounds.outHeight}, ${bytes.size / 1024} KB")
        }
      }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      else -> Unit // NORMAL / UNDEFINED: no transformation needed
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
