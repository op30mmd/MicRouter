package com.mmd.microuter.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scrollState = rememberScrollState()

    // --- STATE VARIABLES ---
    // We initialize state from SharedPreferences.
    // When state changes, we update the UI *and* save to Prefs immediately.

    var port by remember { mutableStateOf(prefs.getString("server_port", "6000") ?: "6000") }
    var showPortDialog by remember { mutableStateOf(false) }

    var sampleRate by remember { mutableStateOf(prefs.getString("sample_rate", "48000") ?: "48000") }
import android.media.MediaRecorder
//... existing imports

    // --- STATE VARIABLES ---
    // ... existing state variables
    var showSampleRateMenu by remember { mutableStateOf(false) }

    val audioSourceMap = mapOf(
        MediaRecorder.AudioSource.DEFAULT to "Default",
        MediaRecorder.AudioSource.MIC to "Microphone",
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice Recognition",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Voice Communication"
    )
    var audioSource by remember { mutableStateOf(prefs.getInt("audio_source", MediaRecorder.AudioSource.MIC)) }
    var showAudioSourceMenu by remember { mutableStateOf(false) }

    var hwSuppressor by remember { mutableStateOf(prefs.getBoolean("enable_hw_suppressor", true)) }

//... existing UI code
            // --- AUDIO QUALITY SECTION ---
            SectionHeader("Audio Quality")

            SettingsCard {
                // Sample Rate Setting
                Box(modifier = Modifier.fillMaxWidth()) {
                    SettingsItem(
                        icon = Icons.Outlined.GraphicEq,
                        title = "Sample Rate",
                        subtitle = "$sampleRate Hz",
                        onClick = { showSampleRateMenu = true }
                    )

                    // FIX: Align the menu to the Right (End) side of the row
                    Box(modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp) // Align with the text margin
                    ) {
                        DropdownMenu(
                            expanded = showSampleRateMenu,
                            onDismissRequest = { showSampleRateMenu = false },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            // FIX: Use 'surface' color to contrast against the 'surfaceContainerHigh' card
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            // FIX: Adjust offset to position it nicely near the value text
                            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 0.dp)
                        ) {
                            listOf("16000", "44100", "48000").forEach { rate ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "$rate Hz",
                                            fontWeight = if (sampleRate == rate) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        sampleRate = rate
                                        prefs.edit().putString("sample_rate", rate).apply()
                                        showSampleRateMenu = false
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = if (sampleRate == rate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Audio Source Setting
                Box(modifier = Modifier.fillMaxWidth()) {
                    SettingsItem(
                        icon = Icons.Outlined.SettingsVoice,
                        title = "Mic Source",
                        subtitle = audioSourceMap[audioSource] ?: "Unknown",
                        onClick = { showAudioSourceMenu = true }
                    )

                    Box(modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                    ) {
                        DropdownMenu(
                            expanded = showAudioSourceMenu,
                            onDismissRequest = { showAudioSourceMenu = false },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            audioSourceMap.forEach { (key, value) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = value,
                                            fontWeight = if (audioSource == key) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        audioSource = key
                                        prefs.edit().putInt("audio_source", key).apply()
                                        showAudioSourceMenu = false
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = if (audioSource == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
//... rest of the file

            // --- NOISE PROCESSING SECTION ---
            SectionHeader("Noise Processing")

            SettingsCard {
                // Hardware Suppressor Switch
                SwitchItem(
                    icon = Icons.Outlined.Hearing,
                    title = "Hardware Suppression",
                    subtitle = "Uses Samsung built-in noise canceler",
                    checked = hwSuppressor,
                    onCheckedChange = {
                        hwSuppressor = it
                        prefs.edit().putBoolean("enable_hw_suppressor", it).apply()
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Noise Gate Slider
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBox(Icons.Outlined.MicOff)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Noise Gate Threshold", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${noiseGate.toInt()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                        Slider(
                            value = noiseGate,
                            onValueChange = {
                                noiseGate = it
                                prefs.edit().putInt("noise_gate_threshold", it.toInt()).apply()
                            },
                            valueRange = 0f..300f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Silences audio below this volume level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("Developer")
            SettingsCard {
                SettingsItem(
                    icon = Icons.Outlined.BugReport,
                    title = "View Debug Console",
                    subtitle = "Inspect logs and errors",
                    onClick = onOpenDebug
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // --- DIALOGS ---

    if (showPortDialog) {
        var tempPort by remember { mutableStateOf(port) }
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("Server Port") },
            text = {
                OutlinedTextField(
                    value = tempPort,
                    onValueChange = { if (it.all { char -> char.isDigit() }) tempPort = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Port Number") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    port = tempPort
                    prefs.edit().putString("server_port", tempPort).apply()
                    showPortDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// --- REUSABLE UI COMPONENTS ---

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        content = content
    )
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun IconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
