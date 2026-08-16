package com.labpano.gpxclient

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Naming/date rules for the user-selected Automatic Backup folder. */
object BackupGpxLayout {
    private const val TWO_DIGIT_YEAR_START_MILLIS = 946_684_800_000L // 2000-01-01 UTC
    private val filenameTimestampRegex = Regex("\\d{6}_\\d{9}")

    fun dailyGlobalFileName(dateFolderName: String): String =
        "PHONE_GPX_BACKUP_${dateFolderName}.gpx"

    fun perVideoFileName(videoName: String): String {
        val name = File(videoName).name
        return if (name.endsWith(".mp4", ignoreCase = true)) {
            name.dropLast(4) + "_backup.gpx"
        } else {
            name.substringBeforeLast('.', name) + "_backup.gpx"
        }
    }

    fun captureTimeFromVideoName(
        videoName: String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        val match = filenameTimestampRegex.find(File(videoName).name) ?: return null
        val formatter = SimpleDateFormat("yyMMdd_HHmmssSSS", Locale.US).apply {
            isLenient = false
            this.timeZone = timeZone
            set2DigitYearStart(Date(TWO_DIGIT_YEAR_START_MILLIS))
        }
        return runCatching { formatter.parse(match.value)?.time }.getOrNull()
    }

    fun dateFolderName(
        videoName: String,
        completedAtMillis: Long?,
        fallbackMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val captureMillis = captureTimeFromVideoName(videoName, timeZone)
            ?: completedAtMillis
            ?: fallbackMillis
        return SimpleDateFormat("dd-MM-yyyy", Locale.US).apply {
            this.timeZone = timeZone
        }.format(Date(captureMillis))
    }
}
