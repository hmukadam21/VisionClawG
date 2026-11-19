package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

// GeminiSessionViewModel - runs one Gemini Live conversation (mic + glasses frames + replies)
//
// v1.2: automatic reconnect for long, pocket-style sessions.
// Google closes every Live connection after roughly 10 minutes (goAway), and mobile networks
// drop connections now and then. Instead of ending the session, the app now reconnects by itself
// using the session-resumption handle, so the same conversation carries on. The microphone keeps
// running during the short gap (audio is simply not sent until the link is back).
// After 6 failed attempts in a row (about 30 seconds), it gives up and shows the error.
//
// v1.4: the user's voice comes from the GLASSES microphones (DAT SDK audio stream, forwarded by
// StreamViewModel via onGlassesAudio) when available. That audio keeps flowing with the phone
// locked, unlike the phone mic, which Android silences in the background. If no glasses audio
// arrives within a few seconds, the app falls back to the phone microphone by itself.
// Echo guard: while Gemini is speaking (plus a short tail) glasses-mic audio is not sent, so
// Gemini does not hear its own voice from the glasses speakers. (No barge-in in this mode.)
//
// v1.6: look_through_glasses. In glasses mode Gemini gets a tool that asks the app for a picture
// (lookProvider, supplied by StreamViewModel; fine_detail = a sharper but slower photo). Since
// v1.7 the picture travels inside the tool answer (see GeminiLiveService.sendLookResult). With the on-demand camera this is the only way Gemini
// sees; with live video it is the "closer look" for small text and details.

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiFunctionCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawEventClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GeminiUiState(
    val isGeminiActive: Boolean = false,
    val connectionState: GeminiConnectionState = GeminiConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val toolCallStatus: ToolCallStatus = ToolCallStatus.Idle,
    val openClawConnectionState: OpenClawConnectionState = OpenClawConnectionState.NotConfigured,
)

/** Result of one look through the glasses camera. */
sealed class LookResult {
    data class Photo(val bitmap: Bitmap) : LookResult()
    data class Failed(val reason: String) : LookResult()
}

class GeminiSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GeminiSessionVM"

        // Wait before each reconnect attempt; the list length is the number of attempts.
        private val RECONNECT_DELAYS_MS = longArrayOf(500, 1000, 2000, 4000, 8000, 15000)

        // No glasses audio within this time after connecting -> use the phone microphone
        private const val GLASSES_AUDIO_WAIT_MS = 4000L

        // Keep the glasses mic muted this long after Gemini's last audio chunk (echo tail)
        private const val ECHO_TAIL_MS = 800L

        private const val MIN_SEND_BYTES = 3200 // 100 ms at 16 kHz mono PCM16
    }

    private val _uiState = MutableStateFlow(GeminiUiState())
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val geminiService = GeminiLiveService()
    private val openClawBridge = OpenClawBridge()
    private var toolCallRouter: ToolCallRouter? = null
    private val audioManager = AudioManager()
    private val eventClient = OpenClawEventClient()
    private var lastVideoFrameTime: Long = 0
    private var stateObservationJob: Job? = null

    // Session bookkeeping (touched from the main thread only)
    @Volatile private var sessionWanted = false
    private var micStarted = false
    private var reconnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    // Set by StreamViewModel: true when the glasses stream was opened with microphone audio
    @Volatile var glassesAudioExpected: Boolean = false

    // Set by StreamViewModel: takes one sharp photo through the glasses (look_through_glasses)
    @Volatile var lookProvider: (suspend (fineDetail: Boolean) -> LookResult)? = null
    private val lookJobs = ConcurrentHashMap<String, Job>()

    private enum class MicSource { NONE, GLASSES, PHONE }
    @Volatile private var micSource = MicSource.NONE
    @Volatile private var lastGlassesAudioMs = 0L
    @Volatile private var lastGeminiAudioMs = 0L
    private val glassesAudioBuffer = java.io.ByteArrayOutputStream()
    private val glassesAudioLock = Any()
    private var micWatchdogJob: Job? = null

    fun startSession() {
        if (_uiState.value.isGeminiActive) return

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        DebugLog.log("AI", "AI started (new conversation, mode $streamingMode)")
        sessionWanted = true
        micStarted = false
        reconnecting = false
        reconnectAttempts = 0
        geminiService.clearResumption() // a new tap on the AI button = a new conversation
        // Glasses mode: the look tool; with the on-demand camera also the high-resolution request
        val glasses = streamingMode == StreamingMode.GLASSES
        val onDemand = glasses && SettingsManager.cameraOnDemand
        geminiService.lookToolEnabled = glasses
        geminiService.liveVideo = !onDemand
        geminiService.requestHighMediaResolution = onDemand
        _uiState.value = _uiState.value.copy(isGeminiActive = true)

        // Wire audio callbacks
        audioManager.onAudioCaptured = lambda@{ data ->
            DebugLog.micChunks.incrementAndGet()
            // Phone mode: mute mic while model speaks to prevent echo
            if (streamingMode == StreamingMode.PHONE && geminiService.isModelSpeaking.value) return@lambda
            geminiService.sendAudio(data)
        }

        geminiService.onAudioReceived = { data ->
            lastGeminiAudioMs = System.currentTimeMillis()
            audioManager.playAudio(data)
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
        }

        geminiService.onTurnComplete = {
            _uiState.value = _uiState.value.copy(userTranscript = "")
        }

        geminiService.onInputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                userTranscript = _uiState.value.userTranscript + text,
                aiTranscript = ""
            )
        }

        geminiService.onOutputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
        }

        // Dropped connection (or goAway): reconnect instead of ending the session
        geminiService.onDisconnected = { reason ->
            viewModelScope.launch { onConnectionLost(reason ?: "unknown") }
        }

        // Check OpenClaw and start session
        viewModelScope.launch {
            openClawBridge.checkConnection()
            openClawBridge.resetSession()

            // Wire tool call handling
            toolCallRouter = ToolCallRouter(openClawBridge, viewModelScope)
            wireToolCallbacks()

            // Observe service state
            stateObservationJob = viewModelScope.launch {
                while (isActive) {
                    delay(100)
                    _uiState.value = _uiState.value.copy(
                        connectionState = geminiService.connectionState.value,
                        isModelSpeaking = geminiService.isModelSpeaking.value,
                        toolCallStatus = openClawBridge.lastToolCallStatus.value,
                        openClawConnectionState = openClawBridge.connectionState.value,
                    )
                }
            }

            // Connect to Gemini (first connection of this conversation)
            geminiService.connect { setupOk ->
                viewModelScope.launch {
                    if (!sessionWanted) return@launch
                    if (!setupOk) {
                        val msg = when (val state = geminiService.connectionState.value) {
                            is GeminiConnectionState.Error -> state.message
                            else -> "Failed to connect to Gemini"
                        }
                        stopSession("first connect failed: $msg")
                        _uiState.value = _uiState.value.copy(errorMessage = msg)
                        return@launch
                    }
                    startMicAndEvents()
                }
            }
        }
    }

    private fun wireToolCallbacks() {
        geminiService.onToolCall = { toolCall ->
            for (call in toolCall.functionCalls) {
                if (call.name == GeminiConfig.LOOK_TOOL_NAME) {
                    handleLook(call)
                } else {
                    toolCallRouter?.handleToolCall(call) { response ->
                        geminiService.sendToolResponse(response)
                    }
                }
            }
        }
        geminiService.onToolCallCancellation = { cancellation ->
            for (id in cancellation.ids) {
                lookJobs.remove(id)?.let {
                    it.cancel()
                    DebugLog.log("LOOK", "look cancelled by Gemini (user spoke again)")
                }
            }
            toolCallRouter?.cancelToolCalls(cancellation.ids)
        }
    }

    /** look_through_glasses: take the photo, send it to Gemini, then answer the tool call. */
    private fun handleLook(call: GeminiFunctionCall) {
        val fineDetail = call.args["fine_detail"]?.toString()?.equals("true", ignoreCase = true) == true
        DebugLog.log("LOOK", "Gemini asked to look${if (fineDetail) " (fine detail: sharp photo)" else ""}")
        val job = viewModelScope.launch {
            val result = lookProvider?.invoke(fineDetail)
                ?: LookResult.Failed("the glasses camera is not connected")
            val toolResult = when (result) {
                is LookResult.Photo -> {
                    val sent = try {
                        withContext(Dispatchers.Default) {
                            geminiService.sendLookResult(call.id, call.name, result.bitmap)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.log("LOOK", "could not prepare the picture: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                    if (sent != null) {
                        DebugLog.log("LOOK", "picture sent to Gemini: $sent")
                        null // answered together with the picture
                    } else {
                        DebugLog.log("LOOK", "picture taken but Gemini was not connected")
                        ToolResult.Failure("The connection dropped before the picture could be sent. Ask the user to repeat the question.")
                    }
                }
                is LookResult.Failed -> {
                    DebugLog.looksFailed.incrementAndGet()
                    DebugLog.log("LOOK", "look FAILED: ${result.reason}")
                    ToolResult.Failure(
                        "The glasses camera could not take a photo (${result.reason}). Tell the user briefly and offer to try again."
                    )
                }
            }
            // A picture was already answered with sendLookResult; failures are answered here
            if (toolResult != null) {
                geminiService.sendToolResponse(
                    JSONObject().put(
                        "toolResponse",
                        JSONObject().put(
                            "functionResponses",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("name", call.name)
                                    .put("response", toolResult.toJSON())
                            ),
                        ),
                    )
                )
            }
            lookJobs.remove(call.id)
        }
        lookJobs[call.id] = job
    }

    /**
     * Starts listening (once per conversation): the glasses microphone when the glasses stream
     * carries audio, otherwise the phone microphone. Also the optional OpenClaw event stream.
     */
    private fun startMicAndEvents() {
        if (micStarted) return
        micStarted = true
        val useGlasses = streamingMode == StreamingMode.GLASSES &&
            SettingsManager.glassesMicEnabled && glassesAudioExpected
        val useBluetoothCall = !useGlasses && streamingMode == StreamingMode.GLASSES &&
            SettingsManager.audioMode == SettingsManager.AUDIO_MODE_BT_CALL
        if (useGlasses) {
            micSource = MicSource.GLASSES
            DebugLog.micSource = "glasses"
            audioManager.startPlayback(forGlassesMic = true)
            DebugLog.log("AI", "listening through the GLASSES microphone")
            val startedAt = System.currentTimeMillis()
            micWatchdogJob?.cancel()
            micWatchdogJob = viewModelScope.launch {
                delay(GLASSES_AUDIO_WAIT_MS)
                if (sessionWanted && micSource == MicSource.GLASSES && lastGlassesAudioMs < startedAt) {
                    DebugLog.log("AI", "no glasses audio after ${GLASSES_AUDIO_WAIT_MS / 1000} s: switching to the PHONE microphone")
                    switchToPhoneMic()
                }
            }
        } else if (useBluetoothCall) {
            DebugLog.log("AI", "listening through the glasses as a BLUETOOTH CALL (HFP)")
            if (!switchToPhoneMic(bluetoothCall = true)) return
        } else {
            DebugLog.log(
                "AI",
                "listening through the PHONE microphone (audioMode=${SettingsManager.audioMode}, " +
                    "glassesAudioExpected=$glassesAudioExpected, mode=$streamingMode)",
            )
            if (!switchToPhoneMic()) return
        }

        // Connect to OpenClaw event stream for proactive notifications
        if (SettingsManager.proactiveNotificationsEnabled) {
            eventClient.onNotification = { text ->
                val state = _uiState.value
                if (state.isGeminiActive && state.connectionState == GeminiConnectionState.Ready) {
                    geminiService.sendTextMessage(text)
                }
            }
            eventClient.connect()
        }
    }

    /**
     * Microphone capture + voice-call playback. With bluetoothCall=true the glasses are made the
     * call device first (glasses mic + speakers over HFP); if that fails, the phone mic is used.
     * Returns false if the mic could not start.
     */
    private fun switchToPhoneMic(bluetoothCall: Boolean = false): Boolean {
        audioManager.stopAll()
        micSource = MicSource.PHONE
        DebugLog.micSource = "phone"
        if (bluetoothCall) {
            if (audioManager.startBluetoothCallRoute()) {
                DebugLog.micSource = "bt-call"
            } else {
                DebugLog.log("AI", "Bluetooth call route unavailable: using the phone microphone")
            }
        }
        return try {
            audioManager.startPlayback(forGlassesMic = false)
            audioManager.startCapture()
            DebugLog.log("AI", "phone microphone started")
            true
        } catch (e: Exception) {
            stopSession("mic failed: ${e.message}")
            _uiState.value = _uiState.value.copy(errorMessage = "Mic capture failed: ${e.message}")
            false
        }
    }

    /**
     * Audio from the glasses microphones (16 kHz mono PCM16), called by StreamViewModel for every
     * frame of the glasses stream. Sent to Gemini in ~100 ms chunks, except while Gemini speaks.
     */
    fun onGlassesAudio(pcm: ByteArray) {
        lastGlassesAudioMs = System.currentTimeMillis()
        if (!sessionWanted || micSource != MicSource.GLASSES || pcm.isEmpty()) return

        // Echo guard: Gemini's voice comes out right next to the glasses' microphones
        if (geminiService.isModelSpeaking.value ||
            lastGlassesAudioMs - lastGeminiAudioMs < ECHO_TAIL_MS) {
            DebugLog.audioMutedWhileGeminiSpeaks.incrementAndGet()
            synchronized(glassesAudioLock) { glassesAudioBuffer.reset() }
            return
        }

        DebugLog.micChunks.incrementAndGet()
        val chunk: ByteArray? = synchronized(glassesAudioLock) {
            glassesAudioBuffer.write(pcm, 0, pcm.size)
            if (glassesAudioBuffer.size() >= MIN_SEND_BYTES) {
                glassesAudioBuffer.toByteArray().also { glassesAudioBuffer.reset() }
            } else {
                null
            }
        }
        chunk?.let { geminiService.sendAudio(it) }
    }

    private fun onConnectionLost(reason: String) {
        DebugLog.log("AI", "connection lost: $reason (wanted=$sessionWanted, alreadyReconnecting=$reconnecting)")
        if (!sessionWanted || reconnecting) return
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (!sessionWanted) return
        if (reconnectAttempts >= RECONNECT_DELAYS_MS.size) {
            DebugLog.log("AI", "GIVING UP after $reconnectAttempts reconnect attempts (last: $reason)")
            stopSession("gave up reconnecting")
            _uiState.value = _uiState.value.copy(errorMessage = "Connection lost: $reason")
            return
        }
        reconnecting = true
        // goAway is planned: reconnect at once. Other drops: back off a little more each time.
        val delayMs = if (reason == "goAway") 0L else RECONNECT_DELAYS_MS[reconnectAttempts]
        reconnectAttempts++
        DebugLog.log("AI", "reconnect attempt $reconnectAttempts in ${delayMs} ms (reason: $reason)")

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (!sessionWanted) return@launch
            audioManager.stopPlayback()
            geminiService.disconnect(clearToolCallbacks = false)
            geminiService.connect { ok ->
                viewModelScope.launch {
                    if (!sessionWanted) return@launch
                    if (ok) {
                        DebugLog.log("AI", "reconnected (same conversation: ${geminiService.hasResumptionHandle})")
                        reconnectAttempts = 0
                        reconnecting = false
                        startMicAndEvents()
                    } else {
                        // The stored handle may have expired: the next try starts fresh.
                        DebugLog.log("AI", "reconnect attempt failed: ${geminiService.connectionState.value}")
                        geminiService.clearResumption()
                        reconnecting = false
                        scheduleReconnect("retry failed")
                    }
                }
            }
        }
    }

    fun stopSession(reason: String = "AI button / screen closed") {
        if (sessionWanted) DebugLog.log("AI", "AI stopped: $reason")
        sessionWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
        reconnectAttempts = 0
        eventClient.disconnect()
        toolCallRouter?.cancelAll()
        toolCallRouter = null
        lookJobs.values.forEach { it.cancel() }
        lookJobs.clear()
        micWatchdogJob?.cancel()
        micWatchdogJob = null
        audioManager.stopAll()
        micStarted = false
        micSource = MicSource.NONE
        DebugLog.micSource = "none"
        synchronized(glassesAudioLock) { glassesAudioBuffer.reset() }
        geminiService.disconnect()
        geminiService.clearResumption()
        stateObservationJob?.cancel()
        stateObservationJob = null
        _uiState.value = GeminiUiState()
    }

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        if (!_uiState.value.isGeminiActive) return
        if (_uiState.value.connectionState != GeminiConnectionState.Ready) return
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameTime < GeminiConfig.VIDEO_FRAME_INTERVAL_MS) return
        lastVideoFrameTime = now
        geminiService.sendVideoFrame(bitmap)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
