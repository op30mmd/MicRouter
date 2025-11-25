package com.mmd.microuter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint()
    private val barRect = RectF()

    private val TARGET_BARS = 50
    private val barHeights = FloatArray(TARGET_BARS)
    private val targetHeights = FloatArray(TARGET_BARS)

    private var isAnimating = false

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        // Note: For simple Canvas drawing, default Layer type is often best.
        // HARDWARE layer is good for Alpha animations, but sometimes uses more GPU memory
        // for rapid invalidation. We can stick to default here.
    }

    fun updateData(data: ByteArray) {
        // Safety check: Don't calculate if view has no size yet
        if (height == 0 || data.isEmpty()) return

        calculateTargetHeights(data)

        if (!isAnimating) {
            isAnimating = true
            postOnAnimation(animationRunnable)
        }
    }

    private fun calculateTargetHeights(data: ByteArray) {
        val maxHeight = height / 2f

        // 1. Calculate chunk size safely
        // If data is smaller than 50 bytes, default to 1 byte per chunk
        val chunkSize = (data.size / TARGET_BARS).coerceAtLeast(1)

        for (i in 0 until TARGET_BARS) {
            var sum = 0L
            val startIdx = i * chunkSize
            val endIdx = (startIdx + chunkSize).coerceAtMost(data.size)

            // Safety check for array bounds
            if (startIdx < data.size) {
                for (j in startIdx until endIdx) {
                    sum += abs(data[j].toInt())
                }
            }

            // Calculate Average
            val rawAverage = if (chunkSize > 0) sum / chunkSize else 0

            // Noise Gate (10/128)
            val cleanAverage = if (rawAverage < 10) 0 else rawAverage

            // Cubic Scaling (Non-linear)
            val normalized = cleanAverage / 128f
            val curved = normalized * normalized * normalized

            targetHeights[i] = curved * maxHeight * 15f
        }
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            var needsMoreFrames = false

            for (i in 0 until TARGET_BARS) {
                val target = targetHeights[i]
                val current = barHeights[i]

                // If the difference is visible (> 0.5 pixels)
                if (abs(target - current) > 0.5f) {
                    needsMoreFrames = true

                    // Rise Fast (0.6), Fall Slow (0.15)
                    val change = if (target > current) {
                        (target - current) * 0.6f
                    } else {
                        (target - current) * 0.15f
                    }
                    barHeights[i] += change
                }
            }

            // Draw the new frame
            invalidate()

            if (needsMoreFrames) {
                postOnAnimation(this)
            } else {
                isAnimating = false
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#00E5FF"), // Cyan
                Color.parseColor("#2979FF"), // Blue
                Color.parseColor("#D500F9")  // Purple
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f

        val totalBarWidth = width / TARGET_BARS
        val barWidth = totalBarWidth * 0.7f
        var currentX = (totalBarWidth * 0.3f) / 2

        for (i in 0 until TARGET_BARS) {
            // No math here! Just drawing.
            var drawMagnitude = barHeights[i]

            // Constraints
            if (drawMagnitude > centerY) drawMagnitude = centerY - 10f
            if (drawMagnitude < 6f) drawMagnitude = 6f

            barRect.set(
                currentX,
                centerY - drawMagnitude,
                currentX + barWidth,
                centerY + drawMagnitude
            )

            canvas.drawRoundRect(barRect, barWidth, barWidth, paint)
            currentX += totalBarWidth
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop animation if the view is destroyed
        removeCallbacks(animationRunnable)
        isAnimating = false
    }
}
