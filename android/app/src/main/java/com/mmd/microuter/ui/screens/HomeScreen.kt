package com.mmd.microuter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmd.microuter.MainViewModel
import com.mmd.microuter.ui.components.Waveform

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel
) {
    val audioData by viewModel.audioData.collectAsState()
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val sampleRate by viewModel.displaySampleRate.collectAsState()

    val context = LocalContext.current

    // Helper to determine status color and text
    val (statusText, statusColor) = when (serviceStatus) {
        "WAITING_FOR_PC" -> "Waiting for PC..." to Color(0xFFFFB300) // Amber
        "PC_CONNECTED" -> "Streaming Active" to Color(0xFF00E676) // Green
        "STOPPED" -> "Service Stopped" to MaterialTheme.colorScheme.onSurface
        else -> serviceStatus to MaterialTheme.colorScheme.error // Red for errors
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MicRouter",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.weight(1f))

        // Visualizer Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Waveform(audioData = audioData)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Dynamic Status Text
        Text(
            text = statusText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = statusColor
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.toggleService(context) },
            modifier = Modifier.size(width = 200.dp, height = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isRunning) "Stop Server" else "Start Server", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info Row
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Badge(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text("Port: $serverPort", modifier = Modifier.padding(4.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Badge(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text("$sampleRate Hz", modifier = Modifier.padding(4.dp))
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}
