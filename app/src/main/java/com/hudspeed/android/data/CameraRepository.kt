package com.hudspeed.android.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CameraRepository(private val dao: CameraDao) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Загружаем камеры из OpenStreetMap Overpass API в радиусе 5км
    suspend fun fetchCamerasNear(lat: Double, lon: Double, radiusMeters: Int = 5000) {
        withContext(Dispatchers.IO) {
            try {
                val query = """
                    [out:json][timeout:25];
                    (
                      node["highway"="speed_camera"](around:$radiusMeters,$lat,$lon);
                      node["enforcement"="maxspeed"](around:$radiusMeters,$lat,$lon);
                    );
                    out body;
                """.trimIndent()

                val url = "https://overpass-api.de/api/interpreter?data=${
                    java.net.URLEncoder.encode(query, "UTF-8")
                }"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) return@withContext

                val body = response.body?.string() ?: return@withContext
                val cameras = parseOverpassResponse(body)

                // Удаляем старые записи (старше 7 дней)
                val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                dao.deleteOlderThan(weekAgo)
                dao.insertAll(cameras)

                Log.d("CameraRepo", "Загружено камер: ${cameras.size}")
            } catch (e: Exception) {
                Log.e("CameraRepo", "Ошибка загрузки камер: ${e.message}")
            }
        }
    }

    private fun parseOverpassResponse(json: String): List<Camera> {
        val cameras = mutableListOf<Camera>()
        try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")

            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val id = el.getLong("id")
                val lat = el.getDouble("lat")
                val lon = el.getDouble("lon")
                val tags = el.optJSONObject("tags")

                val direction = tags?.optString("direction")?.toFloatOrNull() ?: -1f
                val maxSpeed = tags?.optString("maxspeed")
                    ?.replace(" mph", "")
                    ?.toIntOrNull() ?: 0

                val type = when (tags?.optString("enforcement")) {
                    "maxspeed" -> CameraType.SPEED
                    "traffic_signals" -> CameraType.RED_LIGHT
                    "average_speed" -> CameraType.AVERAGE_SPEED
                    else -> CameraType.SPEED
                }

                cameras.add(Camera(id, lat, lon, direction, maxSpeed, type))
            }
        } catch (e: Exception) {
            Log.e("CameraRepo", "Ошибка парсинга: ${e.message}")
        }
        return cameras
    }

    suspend fun getCamerasInBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<Camera> = dao.getCamerasInBounds(minLat, maxLat, minLon, maxLon)

    suspend fun getAll(): List<Camera> = dao.getAll()

    suspend fun count(): Int = dao.count()
}
