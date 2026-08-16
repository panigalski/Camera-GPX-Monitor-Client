package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPhoneGpxWriterTest {
    @Test
    fun writesAllPointsInTimestampOrderWithQualityFields() {
        val later = PhoneGpsPoint(2_000L, 51.2, 22.6, 190.5, 3.5f, "gps", 12.25f, 91.0f)
        val earlier = PhoneGpsPoint(1_000L, 51.1, 22.5, null, 4.0f, "network", null, null)
        val xml = DailyPhoneGpxWriter.build("09-08-2026", listOf(later, earlier))

        assertEquals(2, Regex("<trkpt ").findAll(xml).count())
        assertTrue(xml.indexOf("51.100000000") < xml.indexOf("51.200000000"))
        assertTrue(xml.contains("<labpano:accuracyMeters>3.50</labpano:accuracyMeters>"))
        assertTrue(xml.contains("<labpano:speedMetersPerSecond>12.2500</labpano:speedMetersPerSecond>"))
        assertTrue(xml.contains("<labpano:bearingDegrees>91.000</labpano:bearingDegrees>"))
        assertTrue(xml.contains("<labpano:provider>network</labpano:provider>"))
    }
}
