package com.example.microuter

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

    private var audioData: ByteArray? = null
    private val paint = Paint()
    private val barRect = RectF()

    // SETTINGS
    private val TARGET_BARS = 50 // Fixed number of bars (looks cleaner)
    private val SMOOTHING = 0.5f // Animation smoothing (optional, kept simple for now)

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
    }

    fun updateData(data: ByteArray) {
        this.audioData = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Modern Gradient: Cyan -> Blue -> Purple
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#00E5FF"),
                Color.parseColor("#2979FF"),
                Color.parseColor("#D500F9")
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        audioData?.let { data ->
            val width = width.toFloat()
            val height = height.toFloat()
            val centerY = height / 2f

            // 1. Calculate how wide each bar area is
            val totalBarWidth = width / TARGET_BARS

            // 2. Calculate actual bar width (leave 30% gap)
            val barWidth = totalBarWidth * 0.7f
            val gap = totalBarWidth * 0.3f

            // 3. How many raw bytes represent ONE bar?
            // If we have 4000 bytes and want 50 bars, chunk size = 80 bytes
            val chunkSize = data.size / TARGET_BARS

            var currentX = gap / 2 // Start with a little padding

            // Loop exactly 50 times
            for (i in 0 until TARGET_BARS) {

                // 4. Calculate Average Amplitude for this chunk
                // This "smoothes" the noise out
                var sum = 0L
                val startIdx = i * chunkSize
                val endIdx = startIdx + chunkSize

                if (startIdx < data.size) {
                    for (j in startIdx until endIdx.coerceAtMost(data.size)) {
                        sum += abs(data[j].toInt())
                    }
                }

                // Average value for this chunk (0-128 range mostly)
                val average = if (chunkSize > 0) sum / chunkSize else 0

                // 5. Scale to screen height
                // Multiplier 2.5f makes it look more "alive"
                var magnitude = (average / 64f) * (height / 2f) * 2.5f

                // Constraints
                if (magnitude > centerY) magnitude = centerY - 10f
                if (magnitude < 6f) magnitude = 6f // Minimum pill size

                // 6. Draw the Pill
                barRect.set(
                    currentX,
                    centerY - magnitude,
                    currentX + barWidth,
                    centerY + magnitude
                )

                canvas.drawRoundRect(barRect, barWidth, barWidth, paint)

                currentX += totalBarWidth
            }
        }
    }
}
