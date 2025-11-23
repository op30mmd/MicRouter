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

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var audioData: ByteArray? = null
    private val paint = Paint()
    private val barRect = RectF() // Reusable rect to avoid garbage collection

    init {
        // We will set the color/gradient in onSizeChanged or onDraw
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
    }

    fun updateData(data: ByteArray) {
        this.audioData = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Create a cool Horizontal Gradient (Cyan -> Purple)
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

        audioData?.let { data ->
            val width = width.toFloat()
            val height = height.toFloat()
            val centerY = height / 2f

            // SETTINGS FOR THE LOOK
            val step = 8             // Skip more bytes to make bars wider (less clutter)
            val density = data.size / step
            val totalBarSpace = width / density
            val barWidth = totalBarSpace * 0.6f // Bars take 60% of space, 40% gap
            var currentX = 0f

            for (i in 0 until data.size step step) {
                // Convert byte to positive int
                val value = data[i].toInt()

                // Calculate height (Amplitude)
                // We divide by 128 to normalize, then multiply by height/2
                // Added * 1.5f to make the waves a bit taller/more active
                var magnitude = (value / 128f) * (height / 2f) * 1.5f

                // Cap the height so it doesn't go off screen
                if (magnitude > centerY) magnitude = centerY - 2f
                // Ensure a minimum height so silent bars are visible (little dots)
                if (magnitude < 2f) magnitude = 2f

                // Draw a Rounded Rectangle (Pill shape)
                barRect.set(
                    currentX,
                    centerY - magnitude,
                    currentX + barWidth,
                    centerY + magnitude
                )

                // The last two arguments (barWidth, barWidth) make the corners fully round
                canvas.drawRoundRect(barRect, barWidth, barWidth, paint)

                currentX += totalBarSpace
            }
        }
    }
}
