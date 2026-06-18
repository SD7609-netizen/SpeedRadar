package com.hudspeed.android.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CameraRepository(private val dao: CameraDao) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Быстрое обновление вблизи текущей позиции (онлайн-режим)
    suspend fun fetchCamerasNear(lat: Double, lon: Double, radiusMeters: Int = 5000) {
        withContext(Dispatchers.IO) {
            val query = """
                [out:json][timeout:25];
                (
                  node["highway"="speed_camera"](around:$radiusMeters,$lat,$lon);
                  node["enforcement"="maxspeed"](around:$radiusMeters,$lat,$lon);
                );
                out body;
            """.trimIndent()
            val cameras = runOverpassQuery(query)
            if (cameras.isNotEmpty()) {
                dao.deleteOlderThan(System.currentTimeMillis() - 7 * 24 * 3600 * 1000L)
                dao.insertAll(cameras)
            }
            Log.d("CameraRepo", "Обновлено вблизи: ${cameras.size}")
        }
    }

    /**
     * Скачать ВСЕ камеры страны чанками по сетке 5°×5°.
     * onProgress(downloaded, total_chunks) — для прогресс-бара.
     */
    suspend fun downloadCountry(
        countryCode: String,
        onProgress: (chunksDown: Int, chunksTotal: Int, camerasTotal: Int) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val bounds = countryBounds[countryCode] ?: return@withContext
            val (minLat, maxLat, minLon, maxLon) = bounds

            val step = 5.0
            val chunks = mutableListOf<DoubleArray>()
            var la = minLat
            while (la < maxLat) {
                var lo = minLon
                while (lo < maxLon) {
                    chunks.add(doubleArrayOf(la, minOf(la + step, maxLat), lo, minOf(lo + step, maxLon)))
                    lo += step
                }
                la += step
            }

            var downloaded = 0
            var totalCameras = 0

            for (chunk in chunks) {
                val (cMinLat, cMaxLat, cMinLon, cMaxLon) = chunk
                try {
                    val query = """
                        [out:json][timeout:60];
                        (
                          node["highway"="speed_camera"]($cMinLat,$cMinLon,$cMaxLat,$cMaxLon);
                          node["enforcement"="maxspeed"]($cMinLat,$cMinLon,$cMaxLat,$cMaxLon);
                        );
                        out body;
                    """.trimIndent()
                    val cameras = runOverpassQuery(query)
                    if (cameras.isNotEmpty()) dao.insertAll(cameras)
                    totalCameras += cameras.size
                } catch (e: Exception) {
                    Log.e("CameraRepo", "Ошибка чанка: ${e.message}")
                }
                downloaded++
                withContext(Dispatchers.Main) {
                    onProgress(downloaded, chunks.size, totalCameras)
                }
                Thread.sleep(300) // пауза чтобы не флудить API
            }
            Log.d("CameraRepo", "Скачано для $countryCode: $totalCameras камер")
        }
    }

    private fun runOverpassQuery(query: String): List<Camera> {
        val url = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: return emptyList()
        return parseOverpassResponse(body)
    }

    private fun parseOverpassResponse(json: String): List<Camera> {
        val cameras = mutableListOf<Camera>()
        try {
            val elements = JSONObject(json).getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                val id = el.getLong("id")
                val lat = el.getDouble("lat")
                val lon = el.getDouble("lon")
                val tags = el.optJSONObject("tags")
                val direction = tags?.optString("direction")?.toFloatOrNull() ?: -1f
                val maxSpeed = tags?.optString("maxspeed")
                    ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
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
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<Camera> = dao.getCamerasInBounds(minLat, maxLat, minLon, maxLon)

    suspend fun count(): Int = dao.count()

    suspend fun deleteAll() = dao.deleteOlderThan(Long.MAX_VALUE)

    companion object {
        // Границы стран (minLat, maxLat, minLon, maxLon)
        val countryBounds = mapOf(
            "RU" to doubleArrayOf(41.0, 82.0, 27.0, 170.0),
            "BY" to doubleArrayOf(51.0, 56.5, 23.5, 32.5),
            "KZ" to doubleArrayOf(40.5, 56.0, 50.0, 87.5),
            "UA" to doubleArrayOf(44.0, 52.5, 22.0, 40.5),
        )

        val countryNames = mapOf(
            "RU" to "Россия",
            "BY" to "Беларусь",
            "KZ" to "Казахстан",
            "UA" to "Украина",
        )
    }
}

// Деструктуризация для DoubleArray
operator fun DoubleArray.component1() = this[0]
operator fun DoubleArray.component2() = this[1]
operator fun DoubleArray.component3() = this[2]
operator fun DoubleArray.component4() = this[3]
