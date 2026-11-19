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

    // One sharp photo for the look_through_glasses tool: long edge and JPEG quality sent to Gemini
    const val PHOTO_MAX_EDGE = 1536
    const val PHOTO_JPEG_QUALITY = 85

    const val LOOK_TOOL_NAME = "look_through_glasses"

    // Tool answer texts for a look (v1.7)
    const val LOOK_RESULT_ATTACHED =
        "Picture taken through the user's glasses just now; it is attached to this answer. Answer the user's question from it."
    const val LOOK_RESULT_FOLLOWS =
        "Picture taken. It reaches you with the next message; wait for it and do not describe anything yet."
    const val LOOK_NUDGE = "(The picture from my glasses is here. Please answer my question from it.)"

    /**
     * The user's prompt plus notes the app adds for this session.
     * @param lookTool the look_through_glasses tool is declared (glasses mode)
     * @param liveVideo the camera streams continuously (otherwise it is off until a look)
     */
    fun systemInstruction(lookTool: Boolean, liveVideo: Boolean): String {
        var text = SettingsManager.geminiSystemPrompt
        if (!isOpenClawConfigured) text += NO_ACTIONS_NOTE
        if (lookTool) text += if (liveVideo) LOOK_NOTE_LIVE else LOOK_NOTE_ON_DEMAND
        return text
    }

    private const val LOOK_NOTE_ON_DEMAND = """

VISION, OVERRIDES THE ABOVE WHERE THEY DIFFER: the glasses camera is OFF to save battery. You see nothing until you call the look_through_glasses tool, which returns a fresh picture of what the user is looking at. Whenever answering needs sight (what is this, read this, how much is it, which one, describe, can you see, help me find, or anything about the user's surroundings), first say a very short phrase such as "Let me look", then call look_through_glasses, and answer from the picture it returns. Set fine_detail to true only when you must read small text or see fine detail. Never guess or describe things you have not seen in a picture. A picture shows only that moment: if the user asks about something new or has moved, call it again. Never claim you are watching live video."""

    private const val LOOK_NOTE_LIVE = """

VISION: you see a live but low-resolution video from the glasses. When you need fine detail (small text, prices, labels, screens, faraway signs), say a very short phrase such as "Let me take a closer look", then call the look_through_glasses tool with fine_detail true; it returns one sharp photo of the current view."""

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
