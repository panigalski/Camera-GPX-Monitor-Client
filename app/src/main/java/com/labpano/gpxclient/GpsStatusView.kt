package com.labpano.gpxclient

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GpsStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 55, 55)
        textAlign = Paint.Align.CENTER
    }
    private var connectedSatelliteCount: Int = 0
    private var availableSatelliteCount: Int = 0
    private var fillColor: Int = Color.rgb(158, 158, 158)
    private var details: String = "GPS status unavailable"

    fun update(connectedCount: Int, availableCount: Int, color: Int, statusDetails: String) {
        connectedSatelliteCount = connectedCount
        availableSatelliteCount = availableCount
        fillColor = color
        details = statusDetails
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val circleRadius = 42f * density
        val centerX = width / 2f
        val centerY = paddingTop + circleRadius
        circlePaint.color = fillColor
        canvas.drawCircle(centerX, centerY, circleRadius, circlePaint)

        numberPaint.textSize = 24f * density
        val numberY = centerY - (numberPaint.ascent() + numberPaint.descent()) / 2f
        canvas.drawText("$connectedSatelliteCount/$availableSatelliteCount", centerX, numberY, numberPaint)

        labelPaint.textSize = 12f * density
        val maxWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val lines = wrapText(details, labelPaint, maxWidth)
        var y = centerY + circleRadius + 18f * density
        lines.take(2).forEach {
            canvas.drawText(it, centerX, y, labelPaint)
            y += 16f * density
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isBlank()) current = candidate
            else {
                lines += current
                current = word
            }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }
}
