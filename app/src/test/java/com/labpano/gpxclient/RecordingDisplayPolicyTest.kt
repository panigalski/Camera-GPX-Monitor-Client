package com.labpano.gpxclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDisplayPolicyTest {
    private fun status(source: String, name: String, recording: Boolean = true, finalizing: Boolean = false) =
        CameraRecordingStatus(true, recording, name, 1L, source, finalizing)

    private fun transfer(name: String) =
        TransferEntry("1", name, name, 50L, 100L, 50, "COPYING")

    @Test
    fun matchingFilesystemSignalDuringTransferIsNotShownAsRecording() {
        assertFalse(
            RecordingDisplayPolicy.shouldShowRecording(
                status("mp4-growth-scan", "video.mp4"),
                listOf(transfer("video.mp4"))
            )
        )
    }

    @Test
    fun sameFileTransferSuppressesEvenStalePilotBroadcast() {
        assertFalse(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-broadcast", "video.mp4"),
                listOf(transfer("video.mp4"))
            )
        )
    }

    @Test
    fun genuineNewPilotBroadcastStillShowsDuringOldFileTransfer() {
        assertTrue(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-broadcast", "new.mp4"),
                listOf(transfer("old.mp4"))
            )
        )
    }

    @Test
    fun legacyPilotWriteIdleDoesNotDropAnActiveRecording() {
        assertTrue(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-write-idle", "video.mp4", recording = false, finalizing = true),
                emptyList()
            )
        )
    }

    @Test
    fun legacyPilotWriteIdleIsStillSuppressedBySameFileTransfer() {
        assertFalse(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-write-idle", "video.mp4", recording = false, finalizing = true),
                listOf(transfer("video.mp4"))
            )
        )
    }

    @Test
    fun legacyPilotFileCloseDoesNotDropAnActiveRecording() {
        assertTrue(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-file-close", "video.mp4", recording = false, finalizing = true),
                emptyList()
            )
        )
    }

    @Test
    fun legacyPilotImuCloseDoesNotDropAnActiveRecording() {
        assertTrue(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-imu-close", "video.mp4", recording = false, finalizing = true),
                emptyList()
            )
        )
    }


    @Test
    fun temporaryAliasMatchesFinalTransferName() {
        assertFalse(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-broadcast", "video.mp4.part", recording = true, finalizing = false),
                listOf(transfer("video.mp4"))
            )
        )
    }

    @Test
    fun explicitPilotAddFileStillRendersReady() {
        assertFalse(
            RecordingDisplayPolicy.shouldShowRecording(
                status("pilot-camera-add-file", "video.mp4", recording = false, finalizing = true),
                emptyList()
            )
        )
    }

    @Test
    fun differentFilesystemVideoIsNotSuppressed() {
        assertTrue(
            RecordingDisplayPolicy.shouldShowRecording(
                status("mp4-file-events", "new.mp4"),
                listOf(transfer("old.mp4"))
            )
        )
    }
}
