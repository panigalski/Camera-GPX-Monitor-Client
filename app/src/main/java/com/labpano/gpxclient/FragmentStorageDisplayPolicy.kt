package com.labpano.gpxclient

/**
 * User-facing rendering for Camera 5.18.11 Fragment Storage.
 *
 * Main App 0.5.39+ reports a recording family only when it has a strong Camera-derived signal.
 * When Camera's idle mode is not externally observable, all persisted per-mode Fragment Storage
 * values are shown rather than incorrectly defaulting to Stitched or retaining a stale selection.
 */
object FragmentStorageDisplayPolicy {
    data class Display(
        val recordingType: String,
        val fragmentStorage: String
    )

    fun describe(status: FragmentStorageStatus?): Display {
        if (status == null) return Display("Unknown", "--")
        val type = recordingType(status.mode)
        return Display(
            recordingType = type,
            fragmentStorage = if (type == "Unknown" && status.available) {
                allVideoModeValues(status)
            } else {
                fragmentStorageValue(status)
            }
        )
    }

    fun recordingType(mode: String): String = when (mode.trim().lowercase()) {
        "stitched" -> "Stitched"
        "unstitched", "fisheye", "fish_eye", "fisheyevideo" -> "Unstitched"
        "streetview", "street_view", "google street view", "googlestreetview" -> "Google Street View"
        "timelapse", "time_lapse", "time lapse" -> "Time Lapse"
        else -> "Unknown"
    }

    fun fragmentStorageValue(status: FragmentStorageStatus): String = when {
        status.available && !status.enabled -> "Off (Unlimited)"
        status.available && status.limitType == "size" && status.sizeGb != null -> "${status.sizeGb} GB"
        status.available && status.limitType == "time" && status.durationMinutes != null -> durationDisplay(status.durationMinutes)
        status.available -> status.display.ifBlank { "Enabled" }
        else -> unavailableDisplay(status.error)
    }

    /** Truthful fallback when Camera does not expose its idle highlighted mode cross-process. */
    fun allVideoModeValues(status: FragmentStorageStatus): String {
        val values = listOf(
            "Stitched" to status.stitched,
            "Unstitched" to status.unstitched,
            "Google Street View" to status.streetView
        ).mapNotNull { (label, mode) ->
            if (!mode.known) null else "$label: ${modeValue(mode)}"
        }
        return if (values.isNotEmpty()) values.joinToString(" • ") else unavailableDisplay(status.error)
    }

    private fun modeValue(mode: FragmentStorageMode): String = when {
        !mode.enabled -> "Off (Unlimited)"
        mode.limitType == "size" && mode.sizeGb != null -> "${mode.sizeGb} GB"
        mode.limitType == "time" && mode.durationMinutes != null -> durationDisplay(mode.durationMinutes)
        else -> mode.displayValue.ifBlank { "Enabled" }
    }

    private fun durationDisplay(minutes: Int): String = when {
        minutes == 60 -> "1 Hour"
        minutes % 60 == 0 -> "${minutes / 60} Hours"
        else -> "$minutes min"
    }

    private fun unavailableDisplay(error: String): String {
        val reason = error.replace(Regex("\\s+"), " ").trim().take(80)
        return if (reason.isBlank()) "Unavailable" else "Unavailable — $reason"
    }
}
