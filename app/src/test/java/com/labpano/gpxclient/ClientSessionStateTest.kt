package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSessionStateTest {
    private fun dashboard(alerts: List<StorageWriteAlert> = emptyList(), percent: Int = 10) = Dashboard(
        appVersion = "0.5.9",
        monitoringDirectory = "/video",
        outputFolder = "/output",
        internalStorage = StorageUsage("/", 100, 90, 10, percent, ""),
        externalStorage = null,
        battery = BatteryStatus(true, 80, false, false, "Not charging", "Battery", 47.0, "thermal_zone0", 8000, "Good", ""),
        cameraRecording = CameraRecordingStatus(true, false, "", 0L, "mp4-file-events"),
        monitoring = MonitoringStatus(true, true, true, "monitoring"),
        reportHealth = ReportHealth(true, "/video", "filesystem", true, true, true, emptyList(), 0L, 0L, "", ""),
        errors = emptyList(), failed = emptyList(), good = emptyList(), transfers = emptyList(),
        storageWriteAlerts = alerts,
        storageWriteAlertsSupported = true
    )

    private fun alert(id: String, at: Long) = StorageWriteAlert(id, at, "EXTERNAL", "x.mp4", "/x", "WRITE", "failed")

    @Test
    fun identicalDashboardDoesNotCauseContinuousUiRevisionChanges() {
        ClientSessionState.clear()
        val value = dashboard()
        ClientSessionState.beginConnection("http://camera:1100", value)
        val revision = ClientSessionState.lastDashboardRevision
        ClientSessionState.update("http://camera:1100", value)
        assertEquals(revision, ClientSessionState.lastDashboardRevision)
    }

    @Test
    fun historicalDashboardAlertsAreNotAutomaticallyCurrentSessionAlerts() {
        ClientSessionState.clear()
        ClientSessionState.beginConnection("http://camera:1100", dashboard(listOf(alert("old", 1))))
        assertTrue(ClientSessionState.sessionStorageWriteAlerts.isEmpty())
        ClientSessionState.addSessionStorageWriteAlerts(listOf(alert("new", 2)))
        assertEquals(listOf("new"), ClientSessionState.sessionStorageWriteAlerts.map { it.id })
    }

    @Test
    fun transientUnavailableFragmentStorageDoesNotEraseConcreteValue() {
        ClientSessionState.clear()
        val known = FragmentStorageStatus(
            available = true,
            enabled = true,
            display = "4 GB (observed)",
            updatedAt = 200L,
            source = "fragment-rollover-observed"
        )
        val initial = dashboard().copy(fragmentStorage = known, generatedAt = 200L)
        ClientSessionState.beginConnection("http://camera:1100", initial)

        val unavailable = FragmentStorageStatus(
            available = false,
            enabled = false,
            display = "Unavailable",
            updatedAt = 300L,
            source = "pilot-control-protocol",
            error = "Connection refused"
        )
        ClientSessionState.mergeLive(
            "http://camera:1100",
            LiveStatus(
                generatedAt = 300L,
                outputFolder = "/output",
                cameraRecording = initial.cameraRecording,
                fragmentStorage = unavailable,
                monitoring = initial.monitoring,
                transfers = emptyList()
            )
        )

        assertEquals("4 GB (observed)", ClientSessionState.lastDashboard?.fragmentStorage?.display)
    }
    @Test
    fun newerFragmentStorageRevisionWinsEvenIfPilotWallClockMovedBackward() {
        ClientSessionState.clear()
        val initial = dashboard().copy(
            fragmentStorage = FragmentStorageStatus(
                available = true, enabled = true, display = "4 GB",
                updatedAt = 10_000L, revision = 3L, source = "camera-efs-video.properties"
            ),
            generatedAt = 10_000L
        )
        ClientSessionState.beginConnection("http://camera:1100", initial)

        ClientSessionState.mergeLive(
            "http://camera:1100",
            LiveStatus(
                generatedAt = 10_001L,
                outputFolder = "/output",
                cameraRecording = initial.cameraRecording,
                fragmentStorage = FragmentStorageStatus(
                    available = true, enabled = true, display = "6 GB",
                    updatedAt = 9_000L, revision = 4L, source = "camera-efs-video.properties"
                ),
                monitoring = initial.monitoring,
                transfers = emptyList()
            )
        )

        assertEquals("6 GB", ClientSessionState.lastDashboard?.fragmentStorage?.display)
        assertEquals(4L, ClientSessionState.lastDashboard?.fragmentStorage?.revision)
    }

    @Test
    fun newerMainProcessEpochWinsEvenWhenRevisionAndWallClockAreLower() {
        ClientSessionState.clear()
        val initial = dashboard().copy(
            fragmentStorage = FragmentStorageStatus(
                available = true,
                enabled = true,
                display = "4 GB",
                updatedAt = 10_000L,
                revision = 20L,
                source = "camera-efs-video.properties",
                rawValue = "4gb",
                limitType = "size",
                sizeGb = 4,
                processStartedElapsedRealtime = 100_000L
            ),
            generatedAt = 10_000L
        )
        ClientSessionState.beginConnection("http://camera:1100", initial)

        ClientSessionState.mergeLive(
            "http://camera:1100",
            LiveStatus(
                generatedAt = 10_001L,
                outputFolder = "/output",
                cameraRecording = initial.cameraRecording,
                fragmentStorage = FragmentStorageStatus(
                    available = true,
                    enabled = true,
                    display = "8 GB",
                    updatedAt = 9_000L,
                    revision = 1L,
                    source = "camera-efs-video.properties",
                    rawValue = "8gb",
                    limitType = "size",
                    sizeGb = 8,
                    processStartedElapsedRealtime = 200_000L
                ),
                monitoring = initial.monitoring,
                transfers = emptyList()
            )
        )

        assertEquals(8, ClientSessionState.lastDashboard?.fragmentStorage?.sizeGb)
        assertEquals(1L, ClientSessionState.lastDashboard?.fragmentStorage?.revision)
        assertEquals(200_000L, ClientSessionState.lastDashboard?.fragmentStorage?.processStartedElapsedRealtime)
    }

    @Test
    fun monotonicResponseTimeWinsWhenPilotWallClockMovesBackward() {
        ClientSessionState.clear()
        val initial = dashboard().copy(
            fragmentStorage = FragmentStorageStatus(available = true, enabled = true, display = "6 GB", sizeGb = 6),
            generatedAt = 50_000L,
            generatedElapsedRealtime = 10_000L,
            processStartedElapsedRealtime = 1_000L,
            processInstanceId = "process-a"
        )
        ClientSessionState.beginConnection("http://camera:1100", initial)

        val accepted = ClientSessionState.mergeLive(
            "http://camera:1100",
            LiveStatus(
                generatedAt = 40_000L, // wall clock rolled backward
                generatedElapsedRealtime = 10_100L,
                processStartedElapsedRealtime = 1_000L,
                processInstanceId = "process-a",
                outputFolder = "/output",
                cameraRecording = initial.cameraRecording,
                fragmentStorage = initial.fragmentStorage.copy(display = "8 GB", sizeGb = 8),
                monitoring = initial.monitoring,
                transfers = emptyList()
            )
        )

        assertEquals(true, accepted)
        assertEquals(8, ClientSessionState.lastDashboard?.fragmentStorage?.sizeGb)
    }

    @Test
    fun opaqueProcessIdAcceptsPilotRebootEvenWhenUptimeResetsAndRejectsDelayedOldResponse() {
        ClientSessionState.clear()
        val initial = dashboard().copy(
            fragmentStorage = FragmentStorageStatus(available = true, enabled = true, display = "10 GB", sizeGb = 10),
            generatedAt = 50_000L,
            generatedElapsedRealtime = 90_000L,
            processStartedElapsedRealtime = 80_000L,
            processInstanceId = "before-reboot"
        )
        ClientSessionState.beginConnection("http://camera:1100", initial)

        val afterReboot = LiveStatus(
            generatedAt = 10_000L,
            generatedElapsedRealtime = 2_000L,
            processStartedElapsedRealtime = 1_000L,
            processInstanceId = "after-reboot",
            outputFolder = "/output",
            cameraRecording = initial.cameraRecording.copy(generation = 1L),
            fragmentStorage = FragmentStorageStatus(available = false),
            monitoring = initial.monitoring,
            transfers = emptyList()
        )
        assertEquals(true, ClientSessionState.mergeLive("http://camera:1100", afterReboot))
        assertEquals(false, ClientSessionState.lastDashboard?.fragmentStorage?.available)
        assertEquals("after-reboot", ClientSessionState.lastDashboard?.processInstanceId)

        val delayedOld = afterReboot.copy(
            generatedElapsedRealtime = 95_000L,
            processStartedElapsedRealtime = 80_000L,
            processInstanceId = "before-reboot",
            fragmentStorage = initial.fragmentStorage
        )
        assertEquals(false, ClientSessionState.mergeLive("http://camera:1100", delayedOld))
        assertEquals(false, ClientSessionState.lastDashboard?.fragmentStorage?.available)
    }

    @Test
    fun transportTimestampsAloneDoNotCauseUiRevisionChanges() {
        ClientSessionState.clear()
        val initial = dashboard().copy(
            generatedAt = 10_000L,
            generatedElapsedRealtime = 2_000L,
            processStartedElapsedRealtime = 1_000L,
            processInstanceId = "process-a"
        )
        ClientSessionState.beginConnection("http://camera:1100", initial)
        val revision = ClientSessionState.lastDashboardRevision

        ClientSessionState.update(
            "http://camera:1100",
            initial.copy(generatedAt = 10_500L, generatedElapsedRealtime = 2_500L)
        )
        assertEquals(revision, ClientSessionState.lastDashboardRevision)

        val merged = ClientSessionState.mergeLive(
            "http://camera:1100",
            LiveStatus(
                generatedAt = 11_000L,
                generatedElapsedRealtime = 3_000L,
                processStartedElapsedRealtime = 1_000L,
                processInstanceId = "process-a",
                outputFolder = initial.outputFolder,
                cameraRecording = initial.cameraRecording,
                fragmentStorage = initial.fragmentStorage,
                monitoring = initial.monitoring,
                transfers = initial.transfers
            )
        )
        assertEquals(false, merged)
        assertEquals(revision, ClientSessionState.lastDashboardRevision)
    }

}
