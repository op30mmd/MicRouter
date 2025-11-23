package com.example.microuter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var audioData: ByteArray? = null
    private val paint = Paint()

    init {
        paint.color = Color.BLUE
        paint.strokeWidth = 5f
        paint.isAntiAlias = true
    }

    fun updateData(data: ByteArray) {
        this.audioData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        audioData?.let { data ->
            val width = width.toFloat()
            val height = height.toFloat()
            val centerY = height / 2f

            val step = 4
            val spacing = width / (data.size / step).toFloat()
            var currentX = 0f

            for (i in data.indices step step) {
                val value = data[i].toInt()

                val magnitude = (value / 128f) * (height / 2f)

                canvas.drawLine(currentX, centerY - magnitude, currentX, centerY + magnitude, paint)
                currentX += spacing
            }
        }
    }
}
