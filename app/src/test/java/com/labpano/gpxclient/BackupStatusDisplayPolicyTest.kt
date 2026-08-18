package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupStatusDisplayPolicyTest {
    @Test
    fun stoppedStatusRemainsStableWhenBackupIsDisabled() {
        assertEquals(
            "Automatic backup stopped",
            BackupStatusDisplayPolicy.display(false, "Automatic backup stopped")
        )
    }

    @Test
    fun disabledBackupWithoutTerminalStatusShowsOff() {
        assertEquals(
            "Automatic backup is off",
            BackupStatusDisplayPolicy.display(false, null)
        )
    }

    @Test
    fun runningBackupUsesPersistedOperationalStatus() {
        assertEquals(
            "Automatic backup enabled • collecting phone GPS",
            BackupStatusDisplayPolicy.display(true, "Automatic backup enabled • collecting phone GPS")
        )
    }

    @Test
    fun enabledBackupWithoutStatusFallsBackToRunning() {
        assertEquals(
            "Automatic backup is running",
            BackupStatusDisplayPolicy.display(true, "   ")
        )
    }

    @Test
    fun configurationFailureRemainsVisibleWhenDisabled() {
        assertEquals(
            "Backup folder permission was lost; select the folder again",
            BackupStatusDisplayPolicy.display(false, "Backup folder permission was lost; select the folder again")
        )
    }
}
