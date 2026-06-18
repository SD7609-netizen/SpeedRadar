package com.hudspeed.android.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.hudspeed.android.data.Camera
import com.hudspeed.android.data.CameraType
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

object ImportManager {

    private const val TAG = "ImportManager"

    fun parseFile(context: Context, uri: Uri): List<Camera> {
        val name = getFileName(context, uri).lowercase()
        Log.d(TAG, "Импорт файла: $name")
        return when {
            name.endsWith(".kml") -> parseKml(context, uri)
            name.endsWith(".gpx") -> parseGpx(context, uri)
            name.endsWith(".csv") || name.endsWith(".txt") -> parseCsv(context, uri)
            else -> {
                // Определяем формат по содержимому
                val header = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readLine()?.trim() ?: ""
                when {
                    header.startsWith("<?xml") && header.contains("kml", ignoreCase = true) -> parseKml(context, uri)
                    header.startsWith("<?xml") -> parseGpx(context, uri)
                    else -> parseCsv(context, uri)
                }
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment ?: "unknown"
    }

    // ───────────── KML ─────────────
    fun parseKml(context: Context, uri: Uri): List<Camera> {
        val cameras = mutableListOf<Camera>()
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
            val placemarks = doc.getElementsByTagName("Placemark")

            for (i in 0 until placemarks.length) {
                val pm = placemarks.item(i) as? Element ?: continue
                val coordsText = pm.getElementsByTagName("coordinates").item(0)
                    ?.textContent?.trim() ?: continue

                // KML: lon,lat,alt
                val parts = coordsText.split(",")
                if (parts.size < 2) continue
                val lon = parts[0].trim().toDoubleOrNull() ?: continue
                val lat = parts[1].trim().toDoubleOrNull() ?: continue
                if (!isValidLatLon(lat, lon)) continue

                var maxSpeed = 0
                var direction = -1f
                var type = CameraType.SPEED

                // ExtendedData
                val dataNodes = pm.getElementsByTagName("Data")
                for (j in 0 until dataNodes.length) {
                    val d = dataNodes.item(j) as? Element ?: continue
                    val attrName = d.getAttribute("name").lowercase()
                    val value = d.getElementsByTagName("value").item(0)?.textContent ?: continue
                    when {
                        "speed" in attrName -> maxSpeed = value.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                        "direction" in attrName || "heading" in attrName ->
                            direction = value.toFloatOrNull() ?: -1f
                        "type" in attrName -> type = detectType(value)
                    }
                }

                // description как резерв для скорости
                if (maxSpeed == 0) {
                    val desc = pm.getElementsByTagName("description").item(0)?.textContent ?: ""
                    maxSpeed = extractSpeed(desc)
                }

                cameras.add(Camera(stableId(lat, lon), lat, lon, direction, maxSpeed, type))
            }
            Log.d(TAG, "KML: ${cameras.size} камер")
        } catch (e: Exception) {
            Log.e(TAG, "KML ошибка: ${e.message}")
        }
        return cameras
    }

    // ───────────── GPX ─────────────
    fun parseGpx(context: Context, uri: Uri): List<Camera> {
        val cameras = mutableListOf<Camera>()
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
            val waypoints = doc.getElementsByTagName("wpt")

            for (i in 0 until waypoints.length) {
                val wpt = waypoints.item(i) as? Element ?: continue
                val lat = wpt.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = wpt.getAttribute("lon").toDoubleOrNull() ?: continue
                if (!isValidLatLon(lat, lon)) continue

                val cmt = wpt.getElementsByTagName("cmt").item(0)?.textContent ?: ""
                val desc = wpt.getElementsByTagName("desc").item(0)?.textContent ?: ""
                val name = wpt.getElementsByTagName("name").item(0)?.textContent ?: ""

                val maxSpeed = extractSpeed(cmt).takeIf { it > 0 }
                    ?: extractSpeed(desc).takeIf { it > 0 }
                    ?: extractSpeed(name)

                cameras.add(Camera(stableId(lat, lon), lat, lon, -1f, maxSpeed, CameraType.SPEED))
            }
            Log.d(TAG, "GPX: ${cameras.size} камер")
        } catch (e: Exception) {
            Log.e(TAG, "GPX ошибка: ${e.message}")
        }
        return cameras
    }

    // ───────────── CSV ─────────────
    // Поддерживает форматы:
    //   lat,lon[,name,speed,...]          — стандартный
    //   lon,lat[,name,speed,...]          — Garmin POI / radarinfo.ru
    //   lat;lon;speed                     — разделитель ;
    //   "lon","lat","name","desc"         — с кавычками
    fun parseCsv(context: Context, uri: Uri): List<Camera> {
        val rawLines = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.readLines() ?: return emptyList()
        return parseCsvLines(rawLines)
    }

    // Парсинг CSV из списка строк (используется и для файлов, и для HTTP-ответов)
    fun parseCsvLines(rawLines: List<String>): List<Camera> {
        val cameras = mutableListOf<Camera>()
        try {
            // Снимаем BOM с первой строки
            val lines = rawLines.mapIndexed { idx, line ->
                if (idx == 0) line.trimStart('﻿') else line
            }

            // Разделитель определяем один раз по первым числовым строкам
            val sep = detectCsvSeparator(lines)
            Log.d(TAG, "CSV разделитель: '${sep.replace("\t", "TAB")}'")

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("//") ||
                    trimmed.startsWith("#")) continue

                val parts = trimmed.split(sep).map { it.trim().removeSurrounding("\"") }
                if (parts.size < 2) continue

                val a = parts[0].toDoubleOrNull() ?: continue
                val b = parts[1].toDoubleOrNull() ?: continue

                val (lat, lon) = resolveLatLon(a, b) ?: continue
                if (!isValidLatLon(lat, lon)) continue

                val maxSpeed = parts.drop(2).firstNotNullOfOrNull {
                    it.replace(Regex("[^0-9]"), "").toIntOrNull()?.takeIf { s -> s in 20..200 }
                } ?: 0

                cameras.add(Camera(stableId(lat, lon), lat, lon, -1f, maxSpeed, CameraType.SPEED))
            }
            Log.d(TAG, "CSV: ${cameras.size} камер")
        } catch (e: Exception) {
            Log.e(TAG, "CSV ошибка: ${e.message}")
        }
        return cameras
    }

    // Определяем разделитель один раз — по первым строкам с числовыми данными.
    // Фикс бага: раньше определялось PER LINE, и если в названии камеры был ";",
    // строка дробилась не так и lat/lon не парсились.
    private fun detectCsvSeparator(lines: List<String>): String {
        for (line in lines.take(30)) {
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#") || t.startsWith("//")) continue
            for (sep in listOf("\t", ";", ",")) {
                val parts = t.split(sep).map { it.trim().removeSurrounding("\"") }
                if (parts.size >= 2 &&
                    parts[0].toDoubleOrNull() != null &&
                    parts[1].toDoubleOrNull() != null) return sep
            }
        }
        return ","
    }

    // Определяем порядок lat/lon по диапазону широт России/СНГ (41-82°).
    // Если значение НЕ попадает в 41-82 — оно не может быть широтой → это долгота.
    // Москва: lon=37.6 не входит в 41-82 → долгота; lat=55.8 входит → широта. ✓
    private fun resolveLatLon(a: Double, b: Double): Pair<Double, Double>? {
        // Значения > 90 — только долгота
        if (a > 90.0) return if (b in -90.0..90.0) Pair(b, a) else null
        if (b > 90.0) return if (a in -90.0..90.0) Pair(a, b) else null

        val aCouldBeLat = a in 41.0..82.0
        val bCouldBeLat = b in 41.0..82.0
        return when {
            !aCouldBeLat && bCouldBeLat -> Pair(b, a)   // только b — широта; a — долгота
            !bCouldBeLat && aCouldBeLat -> Pair(a, b)   // только a — широта; b — долгота
            // Оба или ни один в 41-82 (неоднозначно) → стандартный порядок lat,lon
            a in -90.0..90.0 && b in -180.0..180.0 -> Pair(a, b)
            b in -90.0..90.0 && a in -180.0..180.0 -> Pair(b, a)
            else -> null
        }
    }

    // ───────────── Вспомогательные ─────────────

    private fun isValidLatLon(lat: Double, lon: Double) =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    private fun extractSpeed(text: String): Int {
        val m = Regex("""(\d{2,3})\s*(?:км/?ч|kmh|km/h|kph)?""", RegexOption.IGNORE_CASE)
            .find(text)
        return m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 20..200 } ?: 0
    }

    private fun detectType(value: String) = when {
        "average" in value.lowercase() || "section" in value.lowercase()
            || "средн" in value.lowercase() -> CameraType.AVERAGE_SPEED
        "light" in value.lowercase() || "светоф" in value.lowercase() -> CameraType.RED_LIGHT
        else -> CameraType.SPEED
    }

    // Стабильный ID из координат (отрицательный, чтобы не пересекаться с OSM)
    private fun stableId(lat: Double, lon: Double): Long =
        -("%.6f:%.6f".format(lat, lon).hashCode().toLong().and(0x7FFF_FFFF_FFFFL) + 1L)
}
