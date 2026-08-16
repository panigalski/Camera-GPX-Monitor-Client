package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Test

class FragmentStorageDisplayPolicyTest {
    @Test
    fun showsStitchedAndSelectedSize() {
        val display = FragmentStorageDisplayPolicy.describe(
            FragmentStorageStatus(
                available = true,
                enabled = true,
                mode = "stitched",
                rawValue = "4gb",
                limitType = "size",
                sizeGb = 4
            )
        )
        assertEquals("Stitched", display.recordingType)
        assertEquals("4 GB", display.fragmentStorage)
    }

    @Test
    fun showsUnstitchedAndSelectedSize() {
        val display = FragmentStorageDisplayPolicy.describe(
            FragmentStorageStatus(
                available = true,
                enabled = true,
                mode = "unstitched",
                rawValue = "8gb",
                limitType = "size",
                sizeGb = 8
            )
        )
        assertEquals("Unstitched", display.recordingType)
        assertEquals("8 GB", display.fragmentStorage)
    }

    @Test
    fun showsGoogleStreetViewAndSelectedSize() {
        val display = FragmentStorageDisplayPolicy.describe(
            FragmentStorageStatus(
                available = true,
                enabled = true,
                mode = "streetView",
                rawValue = "10gb",
                limitType = "size",
                sizeGb = 10
            )
        )
        assertEquals("Google Street View", display.recordingType)
        assertEquals("10 GB", display.fragmentStorage)
    }

    @Test
    fun showsUnlimitedWithoutLosingRecordingType() {
        val display = FragmentStorageDisplayPolicy.describe(
            FragmentStorageStatus(
                available = true,
                enabled = false,
                mode = "stitched",
                limitType = "unlimited"
            )
        )
        assertEquals("Stitched", display.recordingType)
        assertEquals("Off (Unlimited)", display.fragmentStorage)
    }

    @Test
    fun supportsTimeBasedFragmentSelection() {
        val display = FragmentStorageDisplayPolicy.describe(
            FragmentStorageStatus(
                available = true,
                enabled = true,
                mode = "streetView",
                rawValue = "2h",
                limitType = "time",
                durationMinutes = 120
            )
        )
        assertEquals("Google Street View", display.recordingType)
        assertEquals("2 Hours", display.fragmentStorage)
    }
    @Test
    fun unknownModeShowsAllCameraFragmentValuesInsteadOfStaleSelection() {
        val display = FragmentStorageDisplayPolicy.describe(
            FragmentStorageStatus(
                available = true,
                mode = "",
                stitched = FragmentStorageMode(true, true, "6gb", "6 GB", "size", 6),
                unstitched = FragmentStorageMode(true, true, "8gb", "8 GB", "size", 8),
                streetView = FragmentStorageMode(true, true, "10gb", "10 GB", "size", 10)
            )
        )
        assertEquals("Unknown", display.recordingType)
        assertEquals(
            "Stitched: 6 GB • Unstitched: 8 GB • Google Street View: 10 GB",
            display.fragmentStorage
        )
    }

}
