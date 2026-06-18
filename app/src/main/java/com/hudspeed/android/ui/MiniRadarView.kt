package com.hudspeed.android.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.hudspeed.android.data.Camera
import com.hudspeed.android.utils.GeoUtils
import kotlin.math.*

/**
 * Компактный радар для оверлея поверх навигатора.
 * Показывает кольца 500/1000/1500м, камеры и стрелку направления.
 */
class MiniRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var cameras: List<Camera> = emptyList()
        set(value) { field = value; invalidate() }
    var carLat: Double = 0.0
        set(value) { field = value; invalidate() }
    var carLon: Double = 0.0
        set(value) { field = value; invalidate() }
    var carBearing: Float = 0f
        set(value) { field = value; invalidate() }
    var radarRangeMeters: Float = 1500f

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0A0A0A")
        style = Paint.Style.FILL
    }
    private val circlePaint = Paint().apply {
        color = Color.parseColor("#2A3A2A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#4A7A4A")
        textSize = 16f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val cameraDotPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val nearestDotPaint = Paint().apply {
        color = Color.parseColor("#FF8800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val arrowPaint = Paint().apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.9f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Кольца с подписями
        val rings = 3
        val labels = listOf("500 м", "1 км", "1,5 км")
        for (i in 1..rings) {
            val r = radius * i / rings
            canvas.drawCircle(cx, cy, r, circlePaint)
            canvas.drawText(labels[i - 1], cx, cy - r + 14f, labelPaint)
        }

        // Камеры
        if (carLat != 0.0) {
            val nearest = GeoUtils.nearestCamera(cameras, carLat, carLon)
            cameras.forEach { cam ->
                val dist = GeoUtils.distanceBetween(carLat, carLon, cam.lat, cam.lon)
                if (dist > radarRangeMeters) return@forEach
                val (sx, sy) = GeoUtils.cameraToScreenPos(
                    carLat, carLon, carBearing,
                    cam.lat, cam.lon, dist, radius, radarRangeMeters
                )
                val isNearest = nearest?.first?.id == cam.id
                canvas.drawCircle(cx + sx, cy + sy, if (isNearest) 5f else 3f,
                    if (isNearest) nearestDotPaint else cameraDotPaint)
            }
        }

        // Стрелка-машина в центре
        val path = Path().apply {
            moveTo(cx, cy - 10f)
            lineTo(cx - 6f, cy + 8f)
            lineTo(cx + 6f, cy + 8f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}
