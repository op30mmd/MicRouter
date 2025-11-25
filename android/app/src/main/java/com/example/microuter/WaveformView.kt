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
    private val TARGET_BARS = 50

    // MEMORY FOR SMOOTHING
    // We store the current height of every bar here so they don't reset every frame
    private val barHeights = FloatArray(TARGET_BARS)

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
    }

    fun updateData(data: ByteArray) {
        this.audioData = data
        // Trigger a redraw
        invalidate()
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

        // If we have data, process it.
        // Note: Even if audioData is null, we might still want to draw the falling bars (animation),
        // but for simplicity, we only draw on updates here.
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
                // 1. Calculate Raw Target Height
                var sum = 0L
                val startIdx = i * chunkSize
                val endIdx = startIdx + chunkSize

                if (startIdx < data.size) {
                    for (j in startIdx until endIdx.coerceAtMost(data.size)) {
                        sum += abs(data[j].toInt())
                    }
                }

                val rawAverage = if (chunkSize > 0) sum / chunkSize else 0

                // Noise Gate & Scaling (from previous steps)
                val cleanAverage = if (rawAverage < 10) 0 else rawAverage
                val normalized = cleanAverage / 128f
                val curved = normalized * normalized * normalized
                val targetHeight = curved * (height / 2f) * 15f

                // --- SMOOTHING LOGIC ---

                val currentHeight = barHeights[i]

                if (targetHeight > currentHeight) {
                    // RISE FAST: Move 60% of the way to the target instantly
                    // This makes it feel responsive to beats
                    barHeights[i] += (targetHeight - currentHeight) * 0.6f
                } else {
                    // FALL SLOW (Gravity): Move only 10% of the way down
                    // This creates the smooth "fading" effect
                    barHeights[i] += (targetHeight - currentHeight) * 0.15f
                }

                // Use the smoothed value for drawing
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
    }
}
