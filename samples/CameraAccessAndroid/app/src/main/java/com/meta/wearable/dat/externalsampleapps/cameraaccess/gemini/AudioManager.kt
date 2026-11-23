package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

// AudioManager - speaker output for Gemini's voice, and (optionally) the PHONE microphone.
//
// v1.4: playback and capture are separate.
// - Glasses-mic mode: the user's voice comes from the glasses (DAT SDK audio stream, handled in
//   StreamViewModel/GeminiSessionViewModel), so only playback runs here, as MEDIA audio = the
//   glasses' speakers, exactly like Meta's BirdSpotter sample. (v1.4 used the "assistant" usage,
//   which was routed to the glasses but could not be heard: on some phones it has its own volume.)
// - Phone-mic mode (fallback, phone camera mode, or the setting turned off): the phone's mic is
//   captured here with VOICE_COMMUNICATION (built-in echo cancellation) and playback uses the
//   voice-communication route, as before.
// - Bluetooth-call mode (v1.5 default): before the phone-mic path starts, the glasses are made
//   the "communication device" (Bluetooth HFP, like a phone call), so VOICE_COMMUNICATION capture
//   uses the glasses' microphones and playback their speakers. This is Meta's documented route
//   for two-way voice alongside camera streaming. Phone-call audio quality (8 kHz).
// Diagnostics: loudest mic sample per heartbeat, whether Android silenced the phone mic, and
// which output/input device each stream is routed to.

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class AudioManager {
    companion object {
        private const val TAG = "AudioManager"
        private const val MIN_SEND_BYTES = 3200 // 100ms at 16kHz mono Int16 = 1600 frames * 2 bytes
    }

    var onAudioCaptured: ((ByteArray) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false
    @Volatile private var isPlaying = false
    @Volatile private var outputRouteLogged = false
    private val accumulatedData = ByteArrayOutputStream()
    private val accumulateLock = Any()

    private var recordingCallback: android.media.AudioManager.AudioRecordingCallback? = null
    private var lastSilenced: Boolean? = null
    @Volatile private var bluetoothCallActive = false

    /**
     * Routes call audio to the glasses over Bluetooth HFP (glasses mic + glasses speakers).
     * Call before startPlayback(forGlassesMic = false) and startCapture().
     * @return true if the glasses were selected as the communication device.
     */
    fun startBluetoothCallRoute(preferNotGlasses: Boolean = false): Boolean {
        val system = SettingsManager.appContext?.getSystemService(android.media.AudioManager::class.java)
            ?: return false
        val devices = system.availableCommunicationDevices
        DebugLog.log(
            "AUDIO",
            "call devices available: " + devices.joinToString { "${it.productName} (type ${it.type})" },
        )
        val callDevices =
            devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } +
                devices.filter { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        // Phone mode (v1.7.3): the car (Android Auto) or a headset, not the glasses, if both are on
        val glassesName = Regex("(?i)ray.?ban|meta|oakley")
        val glasses =
            (if (preferNotGlasses) callDevices.firstOrNull { !glassesName.containsMatchIn(it.productName.toString()) } else null)
                ?: callDevices.firstOrNull()
        if (glasses == null) {
            DebugLog.log("AUDIO", "no Bluetooth call device found: cannot use the Bluetooth-call route")
            return false
        }
        system.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        val ok = system.setCommunicationDevice(glasses)
        bluetoothCallActive = ok
        DebugLog.log(
            "AUDIO",
            "Bluetooth call route to ${glasses.productName} (type ${glasses.type}): " +
                if (ok) "ON" else "REFUSED",
        )
        if (!ok) system.mode = android.media.AudioManager.MODE_NORMAL
        return ok
    }

    private fun stopBluetoothCallRoute() {
        if (!bluetoothCallActive) return
        bluetoothCallActive = false
        val system = SettingsManager.appContext?.getSystemService(android.media.AudioManager::class.java)
            ?: return
        system.clearCommunicationDevice()
        system.mode = android.media.AudioManager.MODE_NORMAL
        DebugLog.log("AUDIO", "Bluetooth call route OFF")
    }

    /**
     * Starts the speaker output for Gemini's voice.
     * @param forGlassesMic true: media route (glasses speakers, normal media quality).
     *                      false: voice-communication route, paired with the phone mic's echo canceller.
     */
    fun startPlayback(forGlassesMic: Boolean) {
        if (audioTrack != null) return
        val usage =
            if (forGlassesMic) AudioAttributes.USAGE_MEDIA
            else AudioAttributes.USAGE_VOICE_COMMUNICATION
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(GeminiConfig.OUTPUT_AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    GeminiConfig.OUTPUT_AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2
            )
            .build()
        audioTrack?.addOnRoutingChangedListener({ routing ->
            val dev = routing.routedDevice
            DebugLog.log("AUDIO", "speaker output now: ${dev?.productName ?: "?"} (type ${dev?.type ?: "?"})")
        }, Handler(Looper.getMainLooper()))
        audioTrack?.play()
        isPlaying = true
        outputRouteLogged = false
        val system = SettingsManager.appContext?.getSystemService(android.media.AudioManager::class.java)
        val media = system?.let {
            "${it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/" +
                "${it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}"
        }
        val call = system?.let {
            "${it.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)}/" +
                "${it.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)}"
        }
        DebugLog.log(
            "AUDIO",
            "speaker output started (${if (forGlassesMic) "media route" else "voice-call route"}), " +
                "volumes: media=$media call=$call, audio mode=${system?.mode}",
        )
    }

    /** Starts capturing the PHONE microphone (16 kHz mono PCM16). */
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isCapturing) return

        val bufferSize = AudioRecord.getMinBufferSize(
            GeminiConfig.INPUT_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            GeminiConfig.INPUT_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = record
        registerSilencingWatch()

        record.addOnRoutingChangedListener({ routing ->
            val dev = routing.routedDevice
            DebugLog.log("AUDIO", "microphone input now: ${dev?.productName ?: "?"} (type ${dev?.type ?: "?"})")
        }, Handler(Looper.getMainLooper()))
        record.startRecording()
        isCapturing = true
        val input = record.routedDevice
        DebugLog.log("AUDIO", "microphone capture started (input: ${input?.productName ?: "?"}, type ${input?.type ?: "?"})")

        synchronized(accumulateLock) {
            accumulatedData.reset()
        }

        captureThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    DebugLog.notePeak(DebugLog.phoneMicPeak, peakOf(buffer, read))
                    synchronized(accumulateLock) {
                        accumulatedData.write(buffer, 0, read)
                        if (accumulatedData.size() >= MIN_SEND_BYTES) {
                            val chunk = accumulatedData.toByteArray()
                            accumulatedData.reset()
                            onAudioCaptured?.invoke(chunk)
                        }
                    }
                }
            }
        }, "audio-capture").also { it.start() }
    }

    fun playAudio(data: ByteArray) {
        if (!isPlaying || data.isEmpty()) return
        val track = audioTrack ?: return
        track.write(data, 0, data.size)
        if (!outputRouteLogged) {
            outputRouteLogged = true
            val out = track.routedDevice
            DebugLog.log("AUDIO", "Gemini voice playing on: ${out?.productName ?: "?"} (type ${out?.type ?: "?"})")
        }
    }

    /** Drops any queued speech (used when the user interrupts or on reconnect). */
    fun stopPlayback() {
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    /** Stops the phone microphone only. */
    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false

        captureThread?.join(1000)
        captureThread = null

        // Flush remaining accumulated audio
        synchronized(accumulateLock) {
            if (accumulatedData.size() > 0) {
                val chunk = accumulatedData.toByteArray()
                accumulatedData.reset()
                onAudioCaptured?.invoke(chunk)
            }
        }

        unregisterSilencingWatch()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Audio capture stopped")
    }

    /** Stops the microphone, the speaker output and the Bluetooth call route. */
    fun stopAll() {
        stopCapture()
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        stopBluetoothCallRoute()
    }

    // Loudest absolute 16-bit little-endian sample in the buffer
    private fun peakOf(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            val a = abs(sample)
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }

    // Logs when Android silences (or un-silences) our phone-mic recording, e.g. on screen lock
    private fun registerSilencingWatch() {
        val context = SettingsManager.appContext ?: return
        val systemAudio = context.getSystemService(android.media.AudioManager::class.java) ?: return
        lastSilenced = null
        val callback = object : android.media.AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val sessionId = audioRecord?.audioSessionId ?: return
                val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
                val silenced = mine.isClientSilenced
                if (silenced != lastSilenced) {
                    lastSilenced = silenced
                    DebugLog.log(
                        "AUDIO",
                        if (silenced) "Android SILENCED the phone microphone for this app"
                        else "phone microphone is live (not silenced)",
                    )
                }
            }
        }
        systemAudio.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        recordingCallback = callback
    }

    private fun unregisterSilencingWatch() {
        val callback = recordingCallback ?: return
        val context = SettingsManager.appContext ?: return
        context.getSystemService(android.media.AudioManager::class.java)
            ?.unregisterAudioRecordingCallback(callback)
        recordingCallback = null
    }
}
