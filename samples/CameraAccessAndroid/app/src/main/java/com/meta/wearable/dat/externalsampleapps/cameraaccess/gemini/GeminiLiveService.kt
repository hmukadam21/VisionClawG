package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

// GeminiLiveService - WebSocket client for the Gemini Live API (gemini-3.8-live)
//
// v1.2 additions for long, pocket-style sessions:
// - Session resumption: the server sends sessionResumptionUpdate messages with a handle; a new
//   connection opened with that handle continues the SAME conversation. Google ends every Live
//   connection after roughly 10 minutes (it sends goAway first), so the app reconnects with the
//   handle and the user does not notice.
// - Stale-socket guard: events from an old WebSocket (closing after a reconnect) are ignored, so
//   they cannot tear down the new connection.

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiToolCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GeminiToolCallCancellation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolDeclarations
import java.io.ByteArrayOutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

sealed class GeminiConnectionState {
    data object Disconnected : GeminiConnectionState()
    data object Connecting : GeminiConnectionState()
    data object SettingUp : GeminiConnectionState()
    data object Ready : GeminiConnectionState()
    data class Error(val message: String) : GeminiConnectionState()
}

class GeminiLiveService {
    companion object {
        private const val TAG = "GeminiLiveService"
        private const val CONNECT_TIMEOUT_MS = 15000L
    }

    private val _connectionState = MutableStateFlow<GeminiConnectionState>(GeminiConnectionState.Disconnected)
    val connectionState: StateFlow<GeminiConnectionState> = _connectionState.asStateFlow()

    private val _isModelSpeaking = MutableStateFlow(false)
    val isModelSpeaking: StateFlow<Boolean> = _isModelSpeaking.asStateFlow()

    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onDisconnected: ((String?) -> Unit)? = null
    var onInputTranscription: ((String) -> Unit)? = null
    var onOutputTranscription: ((String) -> Unit)? = null
    var onToolCall: ((GeminiToolCall) -> Unit)? = null
    var onToolCallCancellation: ((GeminiToolCallCancellation) -> Unit)? = null

    // Latency tracking
    private var lastUserSpeechEnd: Long = 0
    private var responseLatencyLogged = false

    @Volatile private var webSocket: WebSocket? = null
    private val sendExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var connectCallback: ((Boolean) -> Unit)? = null
    private var timeoutTimer: Timer? = null

    // Latest resumable session handle from the server (null = start a new conversation)
    @Volatile private var resumptionHandle: String? = null

    val hasResumptionHandle: Boolean
        get() = resumptionHandle != null

    // Diagnostics: log the first resumption update and each unknown message type once
    @Volatile private var resumptionLogged = false
    private val loggedUnknownKeys = mutableSetOf<String>()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    /** Forget the previous conversation; the next connect() starts a fresh one. */
    fun clearResumption() {
        resumptionHandle = null
    }

    fun connect(callback: (Boolean) -> Unit) {
        val url = GeminiConfig.websocketURL()
        if (url == null) {
            _connectionState.value = GeminiConnectionState.Error("No API key configured")
            callback(false)
            return
        }

        _connectionState.value = GeminiConnectionState.Connecting
        connectCallback = callback
        DebugLog.log("GEMINI", "connecting (model ${GeminiConfig.MODEL}, resume=${resumptionHandle != null})")

        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            // Only the current socket may change state; an old one closing late is ignored.
            private fun isCurrent(ws: WebSocket) = ws === this@GeminiLiveService.webSocket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                DebugLog.log("GEMINI", "socket open, sending setup")
                _connectionState.value = GeminiConnectionState.SettingUp
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(webSocket)) return
                handleMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(webSocket)) return
                val msg = t.message ?: "Unknown error"
                DebugLog.log(
                    "GEMINI",
                    "socket FAILURE: ${t.javaClass.simpleName}: $msg" +
                        (response?.let { " (HTTP ${it.code} ${it.message})" } ?: "") +
                        " while ${if (connectCallback != null) "connecting" else "connected"}",
                )
                _connectionState.value = GeminiConnectionState.Error(msg)
                _isModelSpeaking.value = false
                val wasConnecting = connectCallback != null
                resolveConnect(false)
                // A failure while connecting is reported through the connect callback only.
                if (!wasConnecting) onDisconnected?.invoke(msg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                val wasConnecting = connectCallback != null
                DebugLog.log(
                    "GEMINI",
                    "server closing connection: code $code, reason '$reason' while ${if (wasConnecting) "connecting" else "connected"}",
                )
                // While connecting, keep Gemini's reason visible (e.g. a rejected setup field)
                _connectionState.value =
                    if (wasConnecting) GeminiConnectionState.Error("Gemini closed the connection ($code): $reason")
                    else GeminiConnectionState.Disconnected
                _isModelSpeaking.value = false
                resolveConnect(false)
                if (!wasConnecting) onDisconnected?.invoke("Connection closed (code $code: $reason)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = GeminiConnectionState.Disconnected
                _isModelSpeaking.value = false
            }
        })
        webSocket = socket

        // Timeout (Timer so we don't block sendExecutor)
        timeoutTimer?.cancel()
        timeoutTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (webSocket !== socket) return
                    if (_connectionState.value == GeminiConnectionState.Connecting
                        || _connectionState.value == GeminiConnectionState.SettingUp) {
                        DebugLog.log("GEMINI", "connect TIMED OUT after ${CONNECT_TIMEOUT_MS / 1000} s")
                        _connectionState.value = GeminiConnectionState.Error("Connection timed out")
                        resolveConnect(false)
                    }
                }
            }, CONNECT_TIMEOUT_MS)
        }
    }

    /**
     * Closes the connection.
     * @param clearToolCallbacks false when reconnecting, so tool-call handling stays wired up.
     */
    fun disconnect(clearToolCallbacks: Boolean = true) {
        timeoutTimer?.cancel()
        timeoutTimer = null
        val old = webSocket
        webSocket = null // events from `old` are now ignored
        old?.close(1000, null)
        if (clearToolCallbacks) {
            onToolCall = null
            onToolCallCancellation = null
        }
        _connectionState.value = GeminiConnectionState.Disconnected
        _isModelSpeaking.value = false
        resolveConnect(false)
    }

    fun sendAudio(data: ByteArray) {
        if (_connectionState.value != GeminiConnectionState.Ready) {
            DebugLog.audioDroppedNotReady.incrementAndGet()
            return
        }
        DebugLog.audioSentToGemini.incrementAndGet()
        sendExecutor.execute {
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64)
                    })
                })
            }
            webSocket?.send(json.toString())
        }
    }

    fun sendVideoFrame(bitmap: Bitmap) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        DebugLog.picturesSentToGemini.incrementAndGet()
        sendExecutor.execute {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, GeminiConfig.VIDEO_JPEG_QUALITY, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("video", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64)
                    })
                })
            }
            webSocket?.send(json.toString())
        }
    }

    fun sendToolResponse(response: JSONObject) {
        sendExecutor.execute {
            webSocket?.send(response.toString())
        }
    }

    fun sendTextMessage(text: String) {
        if (_connectionState.value != GeminiConnectionState.Ready) return
        sendExecutor.execute {
            val json = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", text)
                        }))
                    }))
                })
            }
            webSocket?.send(json.toString())
        }
    }

    // Private

    private fun resolveConnect(success: Boolean) {
        val cb = connectCallback
        connectCallback = null  // null out BEFORE invoking to prevent re-entrancy
        timeoutTimer?.cancel()
        timeoutTimer = null
        cb?.invoke(success)
    }

    private fun sendSetupMessage(socket: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", GeminiConfig.MODEL)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    // gemini-3.8-live does not accept thinkingConfig; it must be omitted.
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", GeminiConfig.systemInstruction)
                    }))
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", ToolDeclarations.allDeclarationsJSON())
                }))
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("silenceDurationMs", 500)
                        put("prefixPaddingMs", 40)
                    })
                    put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                    // turnCoverage left to the model default (audio activity + all video).
                })
                put("contextWindowCompression", JSONObject().apply {
                    put("slidingWindow", JSONObject().apply {
                        put("targetTokens", 80000)
                    })
                })
                // Ask for resumption handles; pass the last one to continue that conversation.
                put("sessionResumption", JSONObject().apply {
                    resumptionHandle?.let { put("handle", it) }
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        // Send directly (not via sendExecutor) to ensure it's the first message
        socket.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Setup complete
            if (json.has("setupComplete")) {
                DebugLog.log("GEMINI", "READY (setup complete)")
                _connectionState.value = GeminiConnectionState.Ready
                resolveConnect(true)
                return
            }

            // New resumption handle (may arrive on its own or alongside other content)
            if (json.has("sessionResumptionUpdate")) {
                val update = json.getJSONObject("sessionResumptionUpdate")
                val handle = update.optString("newHandle", "")
                if (update.optBoolean("resumable", false) && handle.isNotEmpty()) {
                    resumptionHandle = handle
                    if (!resumptionLogged) {
                        resumptionLogged = true
                        DebugLog.log("GEMINI", "session resumption is working (first handle received)")
                    }
                }
                if (json.length() == 1) return
            }

            // Gemini's own voice detector: it heard the user start speaking
            if (json.has("voiceActivity")) {
                val type = json.optJSONObject("voiceActivity")?.optString("type", "") ?: ""
                if (type == "ACTIVITY_START") DebugLog.geminiHeardSpeech.incrementAndGet()
                if (json.length() == 1) return
            }

            // GoAway: the server will close this connection soon; the view model reconnects
            if (json.has("goAway")) {
                val goAway = json.getJSONObject("goAway")
                val timeLeft = goAway.opt("timeLeft")?.toString() ?: "?"
                DebugLog.log("GEMINI", "goAway from server (time left: $timeLeft), reconnecting")
                _connectionState.value = GeminiConnectionState.Disconnected
                _isModelSpeaking.value = false
                onDisconnected?.invoke("goAway")
                return
            }

            // Tool call
            val toolCall = GeminiToolCall.fromJSON(json)
            if (toolCall != null) {
                Log.d(TAG, "Tool call received: ${toolCall.functionCalls.size} function(s)")
                onToolCall?.invoke(toolCall)
                return
            }

            // Tool call cancellation
            val cancellation = GeminiToolCallCancellation.fromJSON(json)
            if (cancellation != null) {
                Log.d(TAG, "Tool call cancellation: ${cancellation.ids.joinToString()}")
                onToolCallCancellation?.invoke(cancellation)
                return
            }

            // Server content
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                if (serverContent.optBoolean("interrupted", false)) {
                    _isModelSpeaking.value = false
                    onInterrupted?.invoke()
                    return
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                if (mimeType.startsWith("audio/pcm")) {
                                    val base64Data = inlineData.optString("data", "")
                                    if (base64Data.isNotEmpty()) {
                                        val audioData = Base64.decode(base64Data, Base64.DEFAULT)
                                        if (!_isModelSpeaking.value) {
                                            _isModelSpeaking.value = true
                                            if (lastUserSpeechEnd > 0 && !responseLatencyLogged) {
                                                val latency = System.currentTimeMillis() - lastUserSpeechEnd
                                                Log.d(TAG, "[Latency] ${latency}ms (user speech end -> first audio)")
                                                responseLatencyLogged = true
                                            }
                                        }
                                        DebugLog.audioFromGemini.incrementAndGet()
                                        onAudioReceived?.invoke(audioData)
                                    }
                                }
                            } else if (part.has("text")) {
                                Log.d(TAG, part.getString("text"))
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    _isModelSpeaking.value = false
                    responseLatencyLogged = false
                    onTurnComplete?.invoke()
                }

                if (serverContent.has("inputTranscription")) {
                    val transcription = serverContent.getJSONObject("inputTranscription")
                    val transcriptText = transcription.optString("text", "")
                    if (transcriptText.isNotEmpty()) {
                        DebugLog.transcriptsIn.incrementAndGet()
                        Log.d(TAG, "You: $transcriptText")
                        lastUserSpeechEnd = System.currentTimeMillis()
                        responseLatencyLogged = false
                        onInputTranscription?.invoke(transcriptText)
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val transcription = serverContent.getJSONObject("outputTranscription")
                    val transcriptText = transcription.optString("text", "")
                    if (transcriptText.isNotEmpty()) {
                        Log.d(TAG, "AI: $transcriptText")
                        onOutputTranscription?.invoke(transcriptText)
                    }
                }
            }
            // Anything else: note each new kind of message once (helps spot server errors)
            val keys = json.keys().asSequence()
                .filter { it != "usageMetadata" && it != "sessionResumptionUpdate" && it != "voiceActivity" }
                .toList()
            for (key in keys) {
                if (key != "serverContent" && loggedUnknownKeys.add(key)) {
                    DebugLog.log("GEMINI", "unhandled message type '$key': ${json.opt(key).toString().take(300)}")
                }
            }
        } catch (e: Exception) {
            DebugLog.log("GEMINI", "could not read a server message: ${e.message}")
        }
    }
}
