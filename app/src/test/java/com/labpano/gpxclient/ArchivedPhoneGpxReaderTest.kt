package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArchivedPhoneGpxReaderTest {
    @Test
    fun roundTripsWriterTrackPointWithQualityFields() {
        val source = PhoneGpsPoint(1_786_974_345_678L, 51.23456789, 22.34567891, 191.25, 3.25f, "gps&fused", 10.5f, 92.25f)
        val xml = DailyPhoneGpxWriter.build("18-08-2026", listOf(source))
        val line = xml.lineSequence().first { it.contains("<trkpt ") }
        val parsed = ArchivedPhoneGpxReader.parseTrackPointLine(line)
        assertNotNull(parsed)
        parsed!!
        assertEquals(source.time, parsed.time)
        assertEquals(source.latitude, parsed.latitude, 0.000000001)
        assertEquals(source.longitude, parsed.longitude, 0.000000001)
        assertEquals(source.altitude!!, parsed.altitude!!, 0.001)
        assertEquals(source.accuracyMeters!!, parsed.accuracyMeters!!, 0.01f)
        assertEquals(source.provider, parsed.provider)
        assertEquals(source.speedMetersPerSecond!!, parsed.speedMetersPerSecond!!, 0.0001f)
        assertEquals(source.bearingDegrees!!, parsed.bearingDegrees!!, 0.001f)
    }

    @Test
    fun ignoresNonTrackPointAndInvalidCoordinates() {
        assertNull(ArchivedPhoneGpxReader.parseTrackPointLine("<metadata/>"))
        assertNull(ArchivedPhoneGpxReader.parseTrackPointLine("<trkpt lat=\"99\" lon=\"20\"><time>2026-08-18T00:00:00.000Z</time></trkpt>"))
    }
}
