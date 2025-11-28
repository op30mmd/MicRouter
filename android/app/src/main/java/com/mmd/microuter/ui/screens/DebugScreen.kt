package com.mmd.microuter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmd.microuter.utils.AppLogger
import com.mmd.microuter.utils.LogLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBackClick: () -> Unit) {
    val logs by AppLogger.logs.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Console", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val logText = logs.joinToString("\n") { log ->
                            "${log.timestamp} [${log.tag}] ${log.message}"
                        }
                        clipboardManager.setText(AnnotatedString(logText))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy All")
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
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
                .background(Color(0xFF0F0F0F)) // Terminal Black
        ) {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    reverseLayout = true
                ) {
                    items(logs) { log ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            // Timestamp
                            Text(
                                text = log.timestamp,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(60.dp)
                            )

                            // Tag
                            Text(
                                text = "[${log.tag}] ",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )

                            // Message
                            Text(
                                text = log.message,
                                color = when (log.level) {
                                    LogLevel.ERROR -> Color(0xFFFF5252) // Red
                                    LogLevel.WARN -> Color(0xFFFFD740)  // Yellow
                                    else -> Color(0xFFEEEEEE)           // White
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

