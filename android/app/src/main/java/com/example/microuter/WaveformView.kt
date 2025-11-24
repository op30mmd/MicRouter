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
                // Calculate Average Amplitude
                var sum = 0L
                val startIdx = i * chunkSize
                val endIdx = startIdx + chunkSize

                if (startIdx < data.size) {
                    for (j in startIdx until endIdx.coerceAtMost(data.size)) {
                        sum += abs(data[j].toInt())
                    }
                }

                val rawAverage = if (chunkSize > 0) sum / chunkSize else 0

                // --- FIX STARTS HERE ---

                // 1. Noise Gate: Ignore background static (amplitude < 5)
                val cleanAverage = if (rawAverage < 5) 0 else rawAverage

                // 2. Non-Linear Scaling (Squaring):
                // This squashes low volume (noise) to be tiny,
                // but keeps high volume (speech/music) tall.
                val normalized = cleanAverage / 128f // 0.0 to 1.0
                val curved = normalized * normalized // Squaring it (0.5 becomes 0.25)

                // 3. Scale to Height
                // Multiplier 20f restores the height for loud sounds
                var magnitude = curved * (height / 2f) * 20f

                // --- FIX ENDS HERE ---

                // Constraints
                if (magnitude > centerY) magnitude = centerY - 10f
                if (magnitude < 4f) magnitude = 4f // Tiny dots for silence

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
