package com.mmd.microuter.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

@Composable
fun Waveform(
    audioData: ByteArray?,
    modifier: Modifier = Modifier
) {
    val targetBars = 50
    // We use a state list to hold the animated heights
    val barHeights = remember { mutableStateListOf<Float>().apply { repeat(targetBars) { add(0f) } } }

    // This side-effect runs whenever new audio data arrives
    LaunchedEffect(audioData) {
        if (audioData == null || audioData.isEmpty()) return@LaunchedEffect

        val chunkSize = (audioData.size / targetBars).coerceAtLeast(1)

        for (i in 0 until targetBars) {
            var sum = 0L
            val startIdx = i * chunkSize
            val endIdx = (startIdx + chunkSize).coerceAtMost(audioData.size)

            for (j in startIdx until endIdx) {
                sum += abs(audioData[j].toInt())
            }

            val rawAverage = if (chunkSize > 0) sum / chunkSize else 0
            // Noise Gate & Cubic Scaling
            val cleanAverage = if (rawAverage < 10) 0 else rawAverage
            val normalized = cleanAverage / 128f
            val curved = normalized * normalized * normalized
            val targetHeight = curved * 15f // We will scale this to pixel height in DrawScope

            // Smooth Animation Logic
            val current = barHeights[i]
            if (targetHeight > current) {
                barHeights[i] += (targetHeight - current) * 0.6f // Rise fast
            } else {
                barHeights[i] += (targetHeight - current) * 0.15f // Fall slow
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val totalBarWidth = width / targetBars
        val barWidth = totalBarWidth * 0.7f

        val brush = Brush.horizontalGradient(
            colors = listOf(Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFFD500F9))
        )

        for (i in 0 until targetBars) {
            // Convert our abstract height (0-15) to pixels based on View height
            var magnitude = (barHeights[i] * (height / 2f)).coerceIn(6f, centerY - 10f)

            val topLeft = Offset(
                x = (i * totalBarWidth) + (totalBarWidth * 0.15f),
                y = centerY - magnitude
            )
            val barSize = Size(width = barWidth, height = magnitude * 2)

            drawRoundRect(
                brush = brush,
                topLeft = topLeft,
                size = barSize,
                cornerRadius = CornerRadius(16f, 16f)
            )
        }
    }
}
