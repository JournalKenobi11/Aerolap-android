package com.runwise.app.util

import java.util.Locale
import kotlin.math.roundToInt

data class GpsTrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double = 0.0,
    val timestampIso: String,
    val cadence: Int? = null,
    val speedMps: Double? = null
)

object PolylineUtils {

    fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val poly = ArrayList<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        try {
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dlat

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lng += dlng

                poly.add(Pair(lat / 1E5, lng / 1E5))
            }
        } catch (_: Exception) {
        }
        return poly
    }

    fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLng = 0

        for (point in points) {
            val lat = (point.first * 1E5).roundToInt()
            val lng = (point.second * 1E5).roundToInt()

            val dLat = lat - lastLat
            val dLng = lng - lastLng

            encodeValue(dLat, result)
            encodeValue(dLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeValue(value: Int, result: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            result.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        result.append((v + 63).toChar())
    }

    fun generateGpxXml(
        activityName: String,
        sportType: String,
        startTimeIso: String,
        routePoints: List<Pair<Double, Double>>,
        elevationMeters: Int = 0,
        movingTimeSeconds: Long = 0L
    ): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val startEpoch = try {
            val clean = startTimeIso.replace("Z", "")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(clean)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }

        val totalPts = routePoints.size
        val effectiveDurationSec = if (movingTimeSeconds > 0) movingTimeSeconds else totalPts.toLong()

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Aerolap Android\"\n")
        sb.append("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"\n")
        sb.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(activityName)).append("</name>\n")
        sb.append("    <time>").append(startTimeIso).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(activityName)).append("</name>\n")
        sb.append("    <type>").append(sportType.uppercase(Locale.US)).append("</type>\n")
        sb.append("    <trkseg>\n")
        for ((i, pt) in routePoints.withIndex()) {
            val ptOffsetSec = if (totalPts > 1) (i.toDouble() / (totalPts - 1) * effectiveDurationSec).toLong() else 0L
            val ptTimeIso = sdf.format(java.util.Date(startEpoch + ptOffsetSec * 1000L))
            sb.append("      <trkpt lat=\"").append(pt.first).append("\" lon=\"").append(pt.second).append("\">\n")
            sb.append("        <ele>").append(elevationMeters.toDouble()).append("</ele>\n")
            sb.append("        <time>").append(ptTimeIso).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun generateRichGpxXml(
        activityName: String,
        sportType: String,
        startTimeIso: String,
        trackPoints: List<GpsTrackPoint>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Aerolap Android\"\n")
        sb.append("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"\n")
        sb.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(activityName)).append("</name>\n")
        sb.append("    <time>").append(startTimeIso).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(activityName)).append("</name>\n")
        sb.append("    <type>").append(sportType.uppercase(Locale.US)).append("</type>\n")
        sb.append("    <trkseg>\n")
        for (pt in trackPoints) {
            sb.append("      <trkpt lat=\"").append(pt.lat).append("\" lon=\"").append(pt.lon).append("\">\n")
            sb.append("        <ele>").append(pt.ele).append("</ele>\n")
            sb.append("        <time>").append(pt.timestampIso).append("</time>\n")
            if (pt.cadence != null || pt.speedMps != null) {
                sb.append("        <extensions>\n")
                sb.append("          <gpxtpx:TrackPointExtension>\n")
                if (pt.cadence != null && pt.cadence > 0) {
                    sb.append("            <gpxtpx:cad>").append(pt.cadence).append("</gpxtpx:cad>\n")
                }
                if (pt.speedMps != null && pt.speedMps > 0.0) {
                    sb.append("            <gpxtpx:speed>").append(String.format(Locale.US, "%.2f", pt.speedMps)).append("</gpxtpx:speed>\n")
                }
                sb.append("          </gpxtpx:TrackPointExtension>\n")
                sb.append("        </extensions>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
