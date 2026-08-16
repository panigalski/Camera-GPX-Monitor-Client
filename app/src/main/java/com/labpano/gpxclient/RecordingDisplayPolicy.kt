package com.labpano.gpxclient

/**
 * Final UI guard for Pilot One recording state.
 *
 * Main App 0.5.30 publishes a monotonic Camera lifecycle generation. For that explicit lifecycle,
 * transfer progress is deliberately irrelevant: copying/finalizing an already-completed MP4 cannot
 * start or stop Camera capture. Legacy Main App versions still use the older filesystem safeguards.
 */
object RecordingDisplayPolicy {
    fun shouldShowRecording(
        status: CameraRecordingStatus,
        activeTransfers: List<TransferEntry>
    ): Boolean {
        if (!status.available) return false

        // 0.5.30+: Camera lifecycle is authoritative and completely independent from output copies.
        if (status.generation > 0L) {
            return status.recording && !status.finalizing
        }

        val legacyInferredStop = status.finalizing && isLegacyInferredStopSource(status.source)
        if (!status.recording && !legacyInferredStop) return false
        if (status.finalizing && !legacyInferredStop) return false

        val recordingName = recordingIdentity(status.videoName)
        if (recordingName.isNotBlank()) {
            val matchingTransfer = activeTransfers.any { transfer ->
                recordingIdentity(transfer.sourceName).equals(recordingName, ignoreCase = true) ||
                    recordingIdentity(transfer.destinationName).equals(recordingName, ignoreCase = true)
            }
            if (matchingTransfer) return false
        }

        // Legacy Camera start lifecycle (or an inferred-stop state whose old ownership latch is still
        // active) remains Recording unless that same legacy file is already transferring.
        if (status.source.contains("pilot-camera", ignoreCase = true)) return true

        // Filesystem fallback without a filename is ambiguous while any completed file is moving.
        if (recordingName.isBlank()) return activeTransfers.isEmpty()
        return true
    }

    private fun isLegacyInferredStopSource(source: String): Boolean =
        source.equals("pilot-camera-write-idle", ignoreCase = true) ||
            source.equals("pilot-camera-file-close", ignoreCase = true) ||
            source.equals("pilot-camera-imu-close", ignoreCase = true)

    private fun recordingIdentity(value: String): String {
        var name = value.trim().replace('\\', '/').substringAfterLast('/').lowercase()
        while (name.endsWith(".part") || name.endsWith(".tmp")) {
            name = name.substringBeforeLast('.')
        }
        if (name.endsWith(".mp4") || name.endsWith(".sti")) {
            name = name.substringBeforeLast('.')
        }
        return name
    }
}
