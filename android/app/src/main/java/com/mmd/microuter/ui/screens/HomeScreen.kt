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
    // REMOVED: onNavigateToSettings callback is no longer needed here
) {
    val audioData by viewModel.audioData.collectAsState()
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MicRouter") }
                // REMOVED: The actions block (Gear Icon)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ... (Rest of your visualizer and button code remains exactly the same) ...
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

            Text(
                text = if (isRunning) "Status: Streaming" else "Status: Stopped",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
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

            Text("Port: $serverPort", color = Color.Gray)
        }
    }
}
