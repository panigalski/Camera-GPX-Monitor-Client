package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGpsPointDensifierTest {
    private fun point(time: Long, lat: Double = 51.0, lon: Double = 22.0) = PhoneGpsPoint(
        time = time,
        latitude = lat,
        longitude = lon,
        altitude = 100.0,
        accuracyMeters = 4f,
        provider = "gps",
        speedMetersPerSecond = null,
        bearingDegrees = null
    )

    @Test
    fun fillsNormalOneSecondPhoneCadenceAt250msWithoutDroppingRealFixes() {
        val result = PhoneGpsPointDensifier().densify(listOf(point(0L), point(1_000L, 51.001, 22.001)))
        assertEquals(listOf(0L, 250L, 500L, 750L, 1_000L), result.points.map { it.time })
        assertEquals(3, result.interpolatedPointCount)
        assertEquals("gps", result.points.first().provider)
        assertEquals("interpolated", result.points[1].provider)
        assertEquals("gps", result.points.last().provider)
    }

    @Test
    fun neverHidesPhoneGpsGapLongerThanFiveSeconds() {
        val result = PhoneGpsPointDensifier().densify(listOf(point(0L), point(5_001L, 51.001, 22.001)))
        assertEquals(listOf(0L, 5_001L), result.points.map { it.time })
        assertEquals(0, result.interpolatedPointCount)
    }

    @Test
    fun interpolatesAcrossExactlyFiveSecondsBecauseThatStillPassesGapPolicy() {
        val result = PhoneGpsPointDensifier().densify(listOf(point(0L), point(5_000L, 51.005, 22.005)))
        assertTrue(result.points.size > 2)
        assertEquals(5_000L, result.points.last().time)
    }
}
