package com.labpano.gpxclient

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads track points written by [DailyPhoneGpxWriter]. The daily global GPX is the durable,
 * user-visible archive for Automatic Backup, so it can recover a per-video phone backup after
 * the shorter-lived internal CSV timeline has been pruned.
 */
object ArchivedPhoneGpxReader {
    private val trackPoint = Regex("<trkpt\\s+lat=\\\"([^\\\"]+)\\\"\\s+lon=\\\"([^\\\"]+)\\\">(.*)</trkpt>")
    private val elevation = Regex("<ele>([^<]+)</ele>")
    private val time = Regex("<time>([^<]+)</time>")
    private val accuracy = Regex("<labpano:accuracyMeters>([^<]+)</labpano:accuracyMeters>")
    private val provider = Regex("<labpano:provider>([^<]*)</labpano:provider>")
    private val speed = Regex("<labpano:speedMetersPerSecond>([^<]+)</labpano:speedMetersPerSecond>")
    private val bearing = Regex("<labpano:bearingDegrees>([^<]+)</labpano:bearingDegrees>")

    fun parseTrackPointLine(line: String): PhoneGpsPoint? {
        val match = trackPoint.find(line.trim()) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        val body = match.groupValues[3]
        val timestamp = time.find(body)?.groupValues?.get(1)?.let(::parseIso) ?: return null
        return PhoneGpsPoint(
            time = timestamp,
            latitude = latitude,
            longitude = longitude,
            altitude = elevation.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() },
            accuracyMeters = accuracy.find(body)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() },
            provider = provider.find(body)?.groupValues?.get(1)?.let(::xmlUnescape).orEmpty().ifBlank { "unknown" },
            speedMetersPerSecond = speed.find(body)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() },
            bearingDegrees = bearing.find(body)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() }
        )
    }

    private fun parseIso(value: String): Long? {
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun xmlUnescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
}
