package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

object GeminiConfig {
    const val WEBSOCKET_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    // gemini-2.5-flash-native-audio-preview-12-2025 was shut down on 17 Nov 2026;
    // gemini-3.8-live is Google's named replacement.
    const val MODEL = "models/gemini-3.8-live"

    const val INPUT_AUDIO_SAMPLE_RATE = 16000
    const val OUTPUT_AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_BITS_PER_SAMPLE = 16

    const val VIDEO_FRAME_INTERVAL_MS = 1000L
    const val VIDEO_JPEG_QUALITY = 50

    val systemInstruction: String
        get() = if (isOpenClawConfigured) SettingsManager.geminiSystemPrompt
        else SettingsManager.geminiSystemPrompt + NO_ACTIONS_NOTE

    // Added while OpenClaw (the "actions" part) is not set up, so Gemini does not try to call,
    // message or search through a tool that cannot work, and says so plainly instead.
    private const val NO_ACTIONS_NOTE = """

IMPORTANT, OVERRIDES THE ABOVE: the execute tool is NOT available in this setup. You cannot send messages, make calls, set reminders, save notes or control apps. If the user asks for such an action, say briefly that actions are not set up yet, then help with what you can see and know. Answer the user in the language they speak."""

    val apiKey: String
        get() = SettingsManager.geminiAPIKey

    val openClawHost: String
        get() = SettingsManager.openClawHost

    val openClawPort: Int
        get() = SettingsManager.openClawPort

    val openClawHookToken: String
        get() = SettingsManager.openClawHookToken

    val openClawGatewayToken: String
        get() = SettingsManager.openClawGatewayToken

    fun websocketURL(): String? {
        if (apiKey == "YOUR_GEMINI_API_KEY" || apiKey.isEmpty()) return null
        return "$WEBSOCKET_BASE_URL?key=$apiKey"
    }

    val isConfigured: Boolean
        get() = apiKey != "YOUR_GEMINI_API_KEY" && apiKey.isNotEmpty()

    val isOpenClawConfigured: Boolean
        get() = openClawGatewayToken != "YOUR_OPENCLAW_GATEWAY_TOKEN"
                && openClawGatewayToken.isNotEmpty()
                && openClawHost != "http://YOUR_MAC_HOSTNAME.local"
}
