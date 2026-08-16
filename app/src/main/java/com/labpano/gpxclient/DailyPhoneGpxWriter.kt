package com.labpano.gpxclient

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PhoneGpsPoint(
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float?,
    val provider: String,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?
)

object DailyPhoneGpxWriter {
    fun build(dateName: String, points: List<PhoneGpsPoint>): String {
        val sorted = points.sortedBy { it.time }
        val out = StringBuilder(sorted.size * 180 + 512)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Labpano GPX Client\" ")
        out.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        out.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        out.append("xmlns:labpano=\"https://labpano.com/gpx-client\" ")
        out.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        out.append("  <metadata><name>Phone GPS ").append(xmlEscape(dateName)).append("</name>")
        sorted.firstOrNull()?.let { out.append("<time>").append(iso(it.time)).append("</time>") }
        out.append("</metadata>\n")
        out.append("  <trk><name>Phone GPS ").append(xmlEscape(dateName)).append("</name><trkseg>\n")
        for (point in sorted) {
            out.append(String.format(Locale.US, "    <trkpt lat=\"%.9f\" lon=\"%.9f\">", point.latitude, point.longitude))
            point.altitude?.takeIf { it.isFinite() }?.let {
                out.append(String.format(Locale.US, "<ele>%.3f</ele>", it))
            }
            out.append("<time>").append(iso(point.time)).append("</time>")
            out.append("<extensions>")
            point.accuracyMeters?.takeIf { it.isFinite() }?.let {
                out.append(String.format(Locale.US, "<labpano:accuracyMeters>%.2f</labpano:accuracyMeters>", it))
            }
            out.append("<labpano:provider>").append(xmlEscape(point.provider.ifBlank { "unknown" })).append("</labpano:provider>")
            point.speedMetersPerSecond?.takeIf { it.isFinite() }?.let {
                out.append(String.format(Locale.US, "<labpano:speedMetersPerSecond>%.4f</labpano:speedMetersPerSecond>", it))
            }
            point.bearingDegrees?.takeIf { it.isFinite() }?.let {
                out.append(String.format(Locale.US, "<labpano:bearingDegrees>%.3f</labpano:bearingDegrees>", it))
            }
            out.append("</extensions></trkpt>\n")
        }
        out.append("  </trkseg></trk>\n</gpx>\n")
        return out.toString()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun iso(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(time))
}
