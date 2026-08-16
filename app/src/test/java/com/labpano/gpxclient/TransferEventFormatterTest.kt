package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferEventFormatterTest {
    private fun transfer(phase: String, name: String = "video.mp4") =
        TransferEntry("id", name, name, 50L, 100L, 50, phase)

    private fun dashboard(
        transfers: List<TransferEntry> = emptyList(),
        lastStatus: String = "monitoring"
    ) = Dashboard(
        appVersion = "0.5.25",
        monitoringDirectory = "/video",
        outputFolder = "/output",
        internalStorage = StorageUsage("/", 100, 90, 10, 10, ""),
        externalStorage = null,
        battery = BatteryStatus(true, 80, false, false, "Not charging", "Battery", 47.0, "thermal_zone0", 8000, "Good", ""),
        cameraRecording = CameraRecordingStatus(true, false, "", 0L, "pilot-camera-broadcast"),
        monitoring = MonitoringStatus(true, true, true, lastStatus),
        reportHealth = ReportHealth(true, "/output", "filesystem", true, true, true, emptyList(), 0L, 0L, "", ""),
        errors = emptyList(),
        failed = emptyList(),
        good = emptyList(),
        transfers = transfers,
        storageWriteAlerts = emptyList(),
        storageWriteAlertsSupported = true,
        deviceDiagnostics = null
    )

    @Test fun copyingShowsMoving() {
        assertEquals("Moving: video.mp4", TransferEventFormatter.format(dashboard(listOf(transfer("COPYING")))))
    }

    @Test fun verifyingShowsVerifying() {
        assertEquals("Verifying: video.mp4", TransferEventFormatter.format(dashboard(listOf(transfer("VERIFYING")))))
    }

    @Test fun finalizingShowsFinalizing() {
        assertEquals("Finalizing: video.mp4", TransferEventFormatter.format(dashboard(listOf(transfer("FINALIZING")))))
    }

    @Test fun processingStatusShowsGpxStageBeforeTransfer() {
        assertEquals(
            "Processing / generating GPX: clip.mp4",
            TransferEventFormatter.format(dashboard(lastStatus = "processing:clip.mp4"))
        )
    }

    @Test fun idleMonitoringHidesLine() {
        assertEquals("", TransferEventFormatter.format(dashboard()))
    }

    @Test
    fun directTransferLineContainsActivityAndFilename() {
        assertEquals("Moving: video.mp4", TransferEventFormatter.formatTransfer(transfer("MOVING")))
    }
}
