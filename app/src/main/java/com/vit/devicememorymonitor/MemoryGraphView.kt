package com.vit.devicememorymonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class MemoryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dataPoints = mutableListOf<Float>()
    private val maxPoints = 30

    private val linePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#332196F3")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#33888888")
        strokeWidth = 1f
    }

    fun addDataPoint(percent: Int) {
        dataPoints.add(percent.toFloat())
        if (dataPoints.size > maxPoints) {
            dataPoints.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 0..4) {
            val y = h * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (dataPoints.size < 2) return

        val stepX = w / (maxPoints - 1)
        val path = android.graphics.Path()
        val fillPath = android.graphics.Path()

        val startX = w - (dataPoints.size - 1) * stepX
        dataPoints.forEachIndexed { index, value ->
            val x = startX + index * stepX
            val y = h - (value / 100f * h)
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(startX + (dataPoints.size - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}