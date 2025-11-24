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

    private val TARGET_BARS = 50

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

            val totalBarWidth = width / TARGET_BARS
            val barWidth = totalBarWidth * 0.7f
            val gap = totalBarWidth * 0.3f

            val chunkSize = data.size / TARGET_BARS
            var currentX = gap / 2

            for (i in 0 until TARGET_BARS) {
                var sum = 0L
                val startIdx = i * chunkSize
                val endIdx = startIdx + chunkSize

                if (startIdx < data.size) {
                    for (j in startIdx until endIdx.coerceAtMost(data.size)) {
                        sum += abs(data[j].toInt())
                    }
                }

                val rawAverage = if (chunkSize > 0) sum / chunkSize else 0

                // --- TUNING ADJUSTMENTS ---

                // 1. Stricter Noise Gate (Was 5, Now 10)
                // Any volume below 10/128 is treated as absolute silence.
                val cleanAverage = if (rawAverage < 10) 0 else rawAverage

                // 2. Cubic Scaling (Power of 3)
                // This pushes small numbers WAY down.
                // Example: 10% volume -> 0.1 * 0.1 * 0.1 = 0.001 (0.1% height)
                val normalized = cleanAverage / 128f
                val curved = normalized * normalized * normalized

                // 3. Adjusted Multiplier (Was 20f, Now 15f)
                // Since we are cubing, we need a decent multiplier for loud sounds,
                // but 20 was a bit too aggressive.
                var magnitude = curved * (height / 2f) * 15f

                // --- END ADJUSTMENTS ---

                if (magnitude > centerY) magnitude = centerY - 10f
                if (magnitude < 6f) magnitude = 6f

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
