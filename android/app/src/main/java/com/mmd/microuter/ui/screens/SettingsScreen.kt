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
    // REMOVED: onBackClick callback
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
    var showSampleRateMenu by remember { mutableStateOf(false) }

    var hwSuppressor by remember { mutableStateOf(prefs.getBoolean("enable_hw_suppressor", true)) }

    // Slider stores Float for UI, converts to Int for Prefs
    var noiseGate by remember { mutableStateOf(prefs.getInt("noise_gate_threshold", 100).toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                // REMOVED: navigationIcon block (Back Arrow)
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {

            // --- CONNECTION SECTION ---
            SectionHeader("Connection")

            SettingsCard {
                // Port Setting
                SettingsItem(
                    icon = Icons.Outlined.Router,
                    title = "Server Port",
                    subtitle = port,
                    onClick = { showPortDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- AUDIO QUALITY SECTION ---
            SectionHeader("Audio Quality")

            SettingsCard {
                // Sample Rate Setting
                Box {
                    SettingsItem(
                        icon = Icons.Outlined.GraphicEq,
                        title = "Sample Rate",
                        subtitle = "$sampleRate Hz",
                        onClick = { showSampleRateMenu = true }
                    )

                    // Dropdown Menu for Sample Rate
                    DropdownMenu(
                        expanded = showSampleRateMenu,
                        onDismissRequest = { showSampleRateMenu = false },
                        // 1. Make it rounded (16.dp matches your small elements)
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        // 2. Use a lighter color than the card so it "pops" out
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        // 3. Add shadow/elevation
                        tonalElevation = 8.dp,
                        // 4. Add a small offset so it doesn't cover the text perfectly
                        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 8.dp)
                    ) {
                        listOf("16000", "44100", "48000").forEach { rate ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "$rate Hz",
                                        fontWeight = if(sampleRate == rate) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    sampleRate = rate
                                    prefs.edit().putString("sample_rate", rate).apply()
                                    showSampleRateMenu = false
                                },
                                // Optional: Highlight the selected item
                                colors = MenuDefaults.itemColors(
                                    textColor = if(sampleRate == rate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
