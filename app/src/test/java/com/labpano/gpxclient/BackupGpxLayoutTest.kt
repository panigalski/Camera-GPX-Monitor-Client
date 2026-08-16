package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.TimeZone

class BackupGpxLayoutTest {
    private val warsaw = TimeZone.getTimeZone("Europe/Warsaw")

    @Test
    fun createsRequestedDailyGlobalName() {
        assertEquals(
            "PHONE_GPX_BACKUP_16-08-2026.gpx",
            BackupGpxLayout.dailyGlobalFileName("16-08-2026")
        )
    }

    @Test
    fun createsRequestedPerVideoName() {
        assertEquals(
            "260816_102735266_backup.gpx",
            BackupGpxLayout.perVideoFileName("260816_102735266.mp4")
        )
    }

    @Test
    fun usesVideoFilenameDateForFolder() {
        val capture = BackupGpxLayout.captureTimeFromVideoName("260816_102735266.mp4", warsaw)
        assertNotNull(capture)
        assertEquals(
            "16-08-2026",
            BackupGpxLayout.dateFolderName("260816_102735266.mp4", null, 0L, warsaw)
        )
    }

    @Test
    fun fallsBackToCompletionTimeWhenFilenameHasNoTimestamp() {
        // 2026-08-17 00:00:00 UTC == 02:00 in Warsaw in August.
        val completion = 1_786_924_800_000L
        assertEquals(
            "17-08-2026",
            BackupGpxLayout.dateFolderName("video.mp4", completion, 0L, TimeZone.getTimeZone("UTC"))
        )
    }
}
