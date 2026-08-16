package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAlertPolicyTest {
    private fun alert(id: String, at: Long) = StorageWriteAlert(
        id = id,
        occurredAt = at,
        storageType = "INTERNAL",
        videoName = "test.mp4",
        destination = "/storage/test.mp4",
        operation = "copy",
        message = "write failed"
    )

    @Test
    fun firstPollIgnoresHistoryFromBeforeConnection() {
        val selected = StorageAlertPolicy.newAlerts(
            alerts = listOf(alert("old", 1_000), alert("new", 2_100)),
            initialized = false,
            seenIds = emptySet(),
            connectionStartedAt = 2_000,
            now = 2_200,
            clockSkewMs = 5_000
        )
        assertEquals(listOf("new"), selected.map { it.id })
    }

    @Test
    fun laterPollOnlyReturnsUnseenIds() {
        val selected = StorageAlertPolicy.newAlerts(
            alerts = listOf(alert("seen", 2_100), alert("fresh", 2_200)),
            initialized = true,
            seenIds = setOf("seen"),
            connectionStartedAt = 2_000,
            now = 2_300,
            clockSkewMs = 5_000
        )
        assertEquals(listOf("fresh"), selected.map { it.id })
    }

    @Test
    fun retainedIdsAreBoundedAndIgnoreBlankIds() {
        val retained = StorageAlertPolicy.retainedIds(
            listOf(alert("a", 1), alert("", 2), alert("b", 3), alert("c", 4)),
            2
        )
        assertEquals(2, retained.size)
        assertTrue("b" in retained)
        assertTrue("c" in retained)
    }
}
