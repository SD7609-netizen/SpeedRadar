package com.hudspeed.android.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.hudspeed.android.data.Camera
import com.hudspeed.android.data.CameraType
import com.hudspeed.android.utils.GeoUtils
import kotlin.math.*

class RadarView @JvmOverloads constructor(
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

    // Краски
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0A0A0A")
        style = Paint.Style.FILL
    }
    private val circlePaint = Paint().apply {
        color = Color.parseColor("#2A3A2A")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val circleTextPaint = Paint().apply {
        color = Color.parseColor("#4A7A4A")
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val northLinePaint = Paint().apply {
        color = Color.parseColor("#1A4A1A")
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val carPaint = Paint().apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val cameraPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val cameraConePaint = Paint().apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val nearestCameraPaint = Paint().apply {
        color = Color.parseColor("#FF8800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val nearestCameraConePaint = Paint().apply {
        color = Color.parseColor("#80FF8800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.88f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Концентрические окружности с правильными подписями
        val ringLabels = listOf("500 м", "1 км", "1,5 км")
        for (i in 1..3) {
            val r = radius * i / 3
            canvas.drawCircle(cx, cy, r, circlePaint)
            canvas.drawText(ringLabels[i - 1], cx, cy - r + 36f, circleTextPaint)
        }

        // Камеры
        if (carLat != 0.0 && carLon != 0.0) {
            val nearest = GeoUtils.nearestCamera(cameras, carLat, carLon)

            cameras.forEach { cam ->
                val dist = GeoUtils.distanceBetween(carLat, carLon, cam.lat, cam.lon)
                if (dist > radarRangeMeters) return@forEach

                val (screenX, screenY) = GeoUtils.cameraToScreenPos(
                    carLat, carLon, carBearing,
                    cam.lat, cam.lon,
                    dist, radius, radarRangeMeters
                )

                val isNearest = nearest?.first?.id == cam.id
                drawCamera(canvas, cx + screenX, cy + screenY, cam, isNearest)
            }
        }

        // Машина в центре (треугольник вверх = направление движения)
        drawCar(canvas, cx, cy)
    }

    private fun drawCamera(canvas: Canvas, x: Float, y: Float, cam: Camera, isNearest: Boolean) {
        val paint = if (isNearest) nearestCameraPaint else cameraPaint
        val conePaint = if (isNearest) nearestCameraConePaint else cameraConePaint

        // Конус направления камеры
        if (cam.direction >= 0) {
            val coneAngle = 40f
            val coneLen = 60f
            val startAngle = cam.direction - carBearing - coneAngle / 2 - 90f
            val path = Path().apply {
                moveTo(x, y)
                arcTo(
                    x - coneLen, y - coneLen, x + coneLen, y + coneLen,
                    startAngle, coneAngle, false
                )
                close()
            }
            canvas.drawPath(path, conePaint)
        }

        // Иконка камеры (прямоугольник)
        val size = if (isNearest) 16f else 12f
        val rect = RectF(x - size, y - size * 0.7f, x + size, y + size * 0.7f)
        canvas.drawRoundRect(rect, 4f, 4f, paint)

        // Объектив
        canvas.drawCircle(x + size * 0.4f, y, size * 0.35f, Paint().apply {
            color = Color.parseColor("#111111")
            style = Paint.Style.FILL
        })
    }

    private fun drawCar(canvas: Canvas, cx: Float, cy: Float) {
        val path = Path().apply {
            moveTo(cx, cy - 18f)       // нос
            lineTo(cx - 12f, cy + 14f) // левый
            lineTo(cx + 12f, cy + 14f) // правый
            close()
        }
        canvas.drawPath(path, carPaint)
        // Белая точка в центре
        canvas.drawCircle(cx, cy, 4f, Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
    }
}
