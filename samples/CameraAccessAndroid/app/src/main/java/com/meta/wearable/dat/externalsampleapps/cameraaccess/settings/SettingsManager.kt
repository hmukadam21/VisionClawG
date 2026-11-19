package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets

object SettingsManager {
    private const val PREFS_NAME = "visionclaw_settings"

    private lateinit var prefs: SharedPreferences

    // Application context, for system services used outside activities (e.g. audio diagnostics)
    @Volatile var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // How the user's voice is heard and Gemini's voice is played (glasses mode):
    //  AUDIO_MODE_BT_CALL     glasses mic + glasses speakers as a Bluetooth call (HFP), Meta's
    //                         documented route for two-way voice next to camera streaming
    //  AUDIO_MODE_PHONE       phone mic, voice to the glasses as before (v1.1-v1.3 behaviour)
    //  AUDIO_MODE_GLASSES_SDK glasses mic via the DAT camera stream + media speaker (v1.4)
    const val AUDIO_MODE_BT_CALL = "bt_call"
    const val AUDIO_MODE_PHONE = "phone"
    const val AUDIO_MODE_GLASSES_SDK = "glasses_sdk"

    var audioMode: String
        get() = prefs.getString("audioMode", null) ?: AUDIO_MODE_BT_CALL
        set(value) = prefs.edit().putString("audioMode", value).apply()

    /** True when the glasses mic comes through the DAT camera stream (AUDIO_MODE_GLASSES_SDK). */
    val glassesMicEnabled: Boolean
        get() = audioMode == AUDIO_MODE_GLASSES_SDK

    var geminiAPIKey: String
        get() = prefs.getString("geminiAPIKey", null) ?: Secrets.geminiAPIKey
        set(value) = prefs.edit().putString("geminiAPIKey", value).apply()

    var geminiSystemPrompt: String
        get() = prefs.getString("geminiSystemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("geminiSystemPrompt", value).apply()

    var openClawHost: String
        get() = prefs.getString("openClawHost", null) ?: Secrets.openClawHost
        set(value) = prefs.edit().putString("openClawHost", value).apply()

    var openClawPort: Int
        get() {
            val stored = prefs.getInt("openClawPort", 0)
            return if (stored != 0) stored else Secrets.openClawPort
        }
        set(value) = prefs.edit().putInt("openClawPort", value).apply()

    var openClawHookToken: String
        get() = prefs.getString("openClawHookToken", null) ?: Secrets.openClawHookToken
        set(value) = prefs.edit().putString("openClawHookToken", value).apply()

    var openClawGatewayToken: String
        get() = prefs.getString("openClawGatewayToken", null) ?: Secrets.openClawGatewayToken
        set(value) = prefs.edit().putString("openClawGatewayToken", value).apply()

    var webrtcSignalingURL: String
        get() = prefs.getString("webrtcSignalingURL", null) ?: Secrets.webrtcSignalingURL
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    // When the glasses camera runs (v1.6):
    //  CAMERA_ON_DEMAND  camera off; Gemini takes one sharp photo when a question needs eyes
    //                    (the look_through_glasses tool), like Meta AI
    //  CAMERA_LIVE       camera always on, Gemini watches the live video (1 picture per second)
    const val CAMERA_ON_DEMAND = "on_demand"
    const val CAMERA_LIVE = "live"

    var cameraMode: String
        get() = prefs.getString("cameraMode", null) ?: CAMERA_ON_DEMAND
        set(value) = prefs.edit().putString("cameraMode", value).apply()

    // Gemini refused a photo inside a tool answer once (v1.7): send looks as video + text instead
    var multimodalToolResponseRejected: Boolean
        get() = prefs.getBoolean("multimodalToolResponseRejected", false)
        set(value) = prefs.edit().putBoolean("multimodalToolResponseRejected", value).apply()

    /** On-demand camera, except in the SDK-mic test mode, whose audio rides the live stream. */
    val cameraOnDemand: Boolean
        get() = cameraMode != CAMERA_LIVE && audioMode != AUDIO_MODE_GLASSES_SDK

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    const val DEFAULT_SYSTEM_PROMPT = """You are an AI assistant for someone wearing Meta Ray-Ban smart glasses. You can see through their camera and have a voice conversation. Keep responses concise and natural.

CRITICAL: You have NO memory, NO storage, and NO ability to take actions on your own. You cannot remember things, keep lists, set reminders, search the web, send messages, or do anything persistent. You are ONLY a voice interface.

You have exactly ONE tool: execute. This connects you to a powerful personal assistant that can do anything -- send messages, search the web, manage lists, set reminders, create notes, research topics, control smart home devices, interact with apps, and much more.

ALWAYS use execute when the user asks you to:
- Send a message to someone (any platform: WhatsApp, Telegram, iMessage, Slack, etc.)
- Search or look up anything (web, local info, facts, news)
- Add, create, or modify anything (shopping lists, reminders, notes, todos, events)
- Research, analyze, or draft anything
- Control or interact with apps, devices, or services
- Remember or store any information for later

Be detailed in your task description. Include all relevant context: names, content, platforms, quantities, etc. The assistant works better with complete information.

NEVER pretend to do these things yourself.

IMPORTANT: Before calling execute, ALWAYS speak a brief acknowledgment first. For example:
- "Sure, let me add that to your shopping list." then call execute.
- "Got it, searching for that now." then call execute.
- "On it, sending that message." then call execute.
Never call execute silently -- the user needs verbal confirmation that you heard them and are working on it. The tool may take several seconds to complete, so the acknowledgment lets them know something is happening.

For messages, confirm recipient and content before delegating unless clearly urgent."""
}
