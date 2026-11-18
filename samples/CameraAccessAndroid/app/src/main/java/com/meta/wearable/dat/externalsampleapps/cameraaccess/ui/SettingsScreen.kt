package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugLog
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var geminiAPIKey by remember { mutableStateOf(SettingsManager.geminiAPIKey) }
    var systemPrompt by remember { mutableStateOf(SettingsManager.geminiSystemPrompt) }
    var openClawHost by remember { mutableStateOf(SettingsManager.openClawHost) }
    var openClawPort by remember { mutableStateOf(SettingsManager.openClawPort.toString()) }
    var openClawHookToken by remember { mutableStateOf(SettingsManager.openClawHookToken) }
    var openClawGatewayToken by remember { mutableStateOf(SettingsManager.openClawGatewayToken) }
    var webrtcSignalingURL by remember { mutableStateOf(SettingsManager.webrtcSignalingURL) }
    var cameraMode by remember { mutableStateOf(SettingsManager.cameraMode) }
    var proactiveNotificationsEnabled by remember { mutableStateOf(SettingsManager.proactiveNotificationsEnabled) }
    var audioMode by remember { mutableStateOf(SettingsManager.audioMode) }
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun save() {
        SettingsManager.geminiAPIKey = geminiAPIKey.trim()
        SettingsManager.geminiSystemPrompt = systemPrompt.trim()
        SettingsManager.openClawHost = openClawHost.trim()
        openClawPort.trim().toIntOrNull()?.let { SettingsManager.openClawPort = it }
        SettingsManager.openClawHookToken = openClawHookToken.trim()
        SettingsManager.openClawGatewayToken = openClawGatewayToken.trim()
        SettingsManager.webrtcSignalingURL = webrtcSignalingURL.trim()
        SettingsManager.cameraMode = cameraMode
        SettingsManager.proactiveNotificationsEnabled = proactiveNotificationsEnabled
        SettingsManager.audioMode = audioMode
    }

    fun reload() {
        geminiAPIKey = SettingsManager.geminiAPIKey
        systemPrompt = SettingsManager.geminiSystemPrompt
        openClawHost = SettingsManager.openClawHost
        openClawPort = SettingsManager.openClawPort.toString()
        openClawHookToken = SettingsManager.openClawHookToken
        openClawGatewayToken = SettingsManager.openClawGatewayToken
        webrtcSignalingURL = SettingsManager.webrtcSignalingURL
        cameraMode = SettingsManager.cameraMode
        proactiveNotificationsEnabled = SettingsManager.proactiveNotificationsEnabled
        audioMode = SettingsManager.audioMode
    }

    // The phone's own back gesture also saves (before, only the arrow saved; back closed the app).
    BackHandler {
        save()
        onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = {
                    save()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Gemini section
            SectionHeader("Gemini API")
            MonoTextField(
                value = geminiAPIKey,
                onValueChange = { geminiAPIKey = it },
                label = "API Key",
                placeholder = "Enter Gemini API key",
            )

            SectionHeader("System Prompt")
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            // OpenClaw section
            SectionHeader("OpenClaw")
            MonoTextField(
                value = openClawHost,
                onValueChange = { openClawHost = it },
                label = "Host",
                placeholder = "http://your-mac.local",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = openClawPort,
                onValueChange = { openClawPort = it },
                label = "Port",
                placeholder = "18789",
                keyboardType = KeyboardType.Number,
            )
            MonoTextField(
                value = openClawHookToken,
                onValueChange = { openClawHookToken = it },
                label = "Hook Token",
                placeholder = "Hook token",
            )
            MonoTextField(
                value = openClawGatewayToken,
                onValueChange = { openClawGatewayToken = it },
                label = "Gateway Token",
                placeholder = "Gateway auth token",
            )

            // WebRTC section
            SectionHeader("WebRTC")
            MonoTextField(
                value = webrtcSignalingURL,
                onValueChange = { webrtcSignalingURL = it },
                label = "Signaling URL",
                placeholder = "wss://your-server.example.com",
                keyboardType = KeyboardType.Uri,
            )

            // Camera (when the glasses camera runs)
            SectionHeader("Camera")
            ChoiceList(
                options = listOf(
                    Triple(
                        SettingsManager.CAMERA_ON_DEMAND,
                        "Only when needed (recommended)",
                        "Camera stays off. When your question needs eyes, Gemini takes one sharp photo, like Meta AI.",
                    ),
                    Triple(
                        SettingsManager.CAMERA_LIVE,
                        "Live video",
                        "Camera always on and Gemini watches all the time. More battery, less sharp.",
                    ),
                ),
                selected = cameraMode,
                onSelect = { cameraMode = it },
            )

            // Voice (how you are heard and how Gemini is played)
            SectionHeader("Voice")
            val audioOptions = listOf(
                Triple(
                    SettingsManager.AUDIO_MODE_BT_CALL,
                    "Bluetooth call (recommended)",
                    "Glasses mic and speakers like a phone call. Meant to work with the phone locked.",
                ),
                Triple(
                    SettingsManager.AUDIO_MODE_PHONE,
                    "Phone microphone",
                    "Like the first version. Works with the screen on.",
                ),
                Triple(
                    SettingsManager.AUDIO_MODE_GLASSES_SDK,
                    "Glasses mic (SDK), test only",
                    "Hears you when locked, but the glasses may mute Gemini's voice. Keeps the camera on.",
                ),
            )
            ChoiceList(options = audioOptions, selected = audioMode, onSelect = { audioMode = it })

            // Notifications
            SectionHeader("Notifications")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Proactive Notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Receive updates from OpenClaw spoken through glasses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = proactiveNotificationsEnabled,
                    onCheckedChange = { proactiveNotificationsEnabled = it },
                )
            }

            // Diagnostics: share the on-phone log (no keys or spoken words are in it)
            SectionHeader("Diagnostics")
            TextButton(onClick = {
                val file = DebugLog.exportForSharing(context)
                if (file == null) {
                    Toast.makeText(context, "Could not prepare the log", Toast.LENGTH_SHORT).show()
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "VisionClaw diagnostic log")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share diagnostic log"))
                }
            }) {
                Text("Share diagnostic log")
            }

            // Reset
            TextButton(onClick = { showResetDialog = true }) {
                Text("Reset to Defaults", color = Color.Red)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Settings") },
            text = { Text("This will reset all settings to the values built into the app.") },
            confirmButton = {
                TextButton(onClick = {
                    SettingsManager.resetAll()
                    reload()
                    showResetDialog = false
                }) {
                    Text("Reset", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Radio list: (value, title, description) per option. */
@Composable
private fun ChoiceList(
    options: List<Triple<String, String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    options.forEach { (value, title, description) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected == value, onClick = { onSelect(value) }),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MonoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
