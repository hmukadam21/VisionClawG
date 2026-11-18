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

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

  // VisionClaw additions
  var geminiViewModel: GeminiSessionViewModel? = null
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
                    "geminiActive=${gemini?.isGeminiActive} gemini=${gemini?.connectionState}"
            )
          }
        }
  }

  // MARK: - Glasses streaming

  fun startStream() {
    stopStream("starting a new stream")
    stopping = false
    DebugLog.log("STREAM", "starting glasses session")
    startHeartbeat()

    // Foreground service keeps glasses link + microphone alive while the screen is off
    StreamingService.start(getApplication())
    _uiState.update {
      it.copy(streamingMode = StreamingMode.GLASSES, streamSessionState = StreamState.STARTING)
    }

    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no state change is missed
          observeSession(created)
          created.start()
        }
        .onFailure { error, _ ->
          DebugLog.log("STREAM", "createSession FAILED: ${error.description}")
          failAndLeave("Could not connect to the glasses: ${error.description}")
        }
  }

  private fun observeSession(currentSession: DeviceSession) {
    sessionStateJob =
        viewModelScope.launch {
          currentSession.state.collect { state ->
            DebugLog.log("STREAM", "glasses session state: $state")
            when (state) {
              // A camera can only be added once the session is STARTED
              DeviceSessionState.STARTED -> attachCamera(currentSession)
              // STOPPED is terminal (the glasses were removed, folded, disconnected, ...)
              DeviceSessionState.STOPPED -> if (!stopping) leaveStreaming("glasses session stopped")
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

    // Closing the camera stops its stream; stopping the session releases the glasses.
    runCatching { camera?.close() }.onFailure { Log.w(TAG, "camera close failed", it) }
    camera = null
    stream = null
    runCatching { session?.stop() }.onFailure { Log.w(TAG, "session stop failed", it) }
    session = null

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
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val data = photo.data.duplicate().apply { rewind() }
            val byteArray = ByteArray(data.remaining())
            data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    if (capturedPhoto == null) {
      wearablesViewModel.setRecentError("Could not read the photo from the glasses")
      return
    }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap? {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size) ?: return null
    return applyTransform(bitmap, transform)
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
