package com.hudspeed.android.utils

import android.location.Location
import com.hudspeed.android.data.Camera
import kotlin.math.*

object GeoUtils {

    // Расстояние между двумя точками в метрах
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }

    // Азимут от точки A к точке B (в градусах, 0=север)
    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)).toFloat() + 360f) % 360f)
    }

    // Координаты bounding box для запроса в БД
    fun boundingBox(lat: Double, lon: Double, radiusMeters: Double): DoubleArray {
        val earthRadius = 6371000.0
        val dLat = Math.toDegrees(radiusMeters / earthRadius)
        val dLon = Math.toDegrees(radiusMeters / (earthRadius * cos(Math.toRadians(lat))))
        return doubleArrayOf(
            lat - dLat,  // minLat
            lat + dLat,  // maxLat
            lon - dLon,  // minLon
            lon + dLon   // maxLon
        )
    }

    // Камера направлена в нашу сторону? (смотрит ли она на нас)
    fun isCameraFacingUs(cameraLat: Double, cameraLon: Double, cameraDirection: Float,
                          carLat: Double, carLon: Double): Boolean {
        if (cameraDirection < 0) return true // обе стороны
        val bearingFromCamera = bearingTo(cameraLat, cameraLon, carLat, carLon)
        val diff = abs(((cameraDirection - bearingFromCamera) + 180) % 360 - 180)
        return diff < 45f // ±45° от направления камеры
    }

    // Позиция камеры на экране радара (относительно центра)
    // Возвращает (x, y) в пикселях от центра
    fun cameraToScreenPos(
        carLat: Double, carLon: Double, carBearing: Float,
        cameraLat: Double, cameraLon: Double,
        distanceMeters: Float, radarRadiusPx: Float, radarRangeMeters: Float
    ): Pair<Float, Float> {
        val bearing = bearingTo(carLat, carLon, cameraLat, cameraLon)
        val relBearing = Math.toRadians((bearing - carBearing).toDouble())
        val scale = (distanceMeters / radarRangeMeters) * radarRadiusPx
        val x = (sin(relBearing) * scale).toFloat()
        val y = -(cos(relBearing) * scale).toFloat()
        return Pair(x, y)
    }

    // Ближайшая камера из списка
    fun nearestCamera(
        cameras: List<Camera>,
        carLat: Double, carLon: Double
    ): Pair<Camera, Float>? {
        if (cameras.isEmpty()) return null
        return cameras.map { cam ->
            Pair(cam, distanceBetween(carLat, carLon, cam.lat, cam.lon))
        }.minByOrNull { it.second }
    }

    fun formatDistance(meters: Float): String = when {
        meters < 1000 -> "${meters.toInt()} м"
        else -> String.format("%.1f км", meters / 1000f)
    }
}
