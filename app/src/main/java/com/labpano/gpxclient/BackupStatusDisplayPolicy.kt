package com.labpano.gpxclient

/**
 * Single display policy for the Automatic Backup status label.
 *
 * The Activity has multiple refresh paths (dashboard updates and the periodic preferences sync).
 * They must render the same text for the same persisted backup state; otherwise the label can
 * visibly alternate between two values while nothing actually changes.
 */
object BackupStatusDisplayPolicy {
    fun display(enabled: Boolean, persistedStatus: String?): String {
        val status = persistedStatus?.trim().orEmpty()
        return when {
            status.isNotEmpty() -> status
            enabled -> "Automatic backup is running"
            else -> "Automatic backup is off"
        }
    }
}
