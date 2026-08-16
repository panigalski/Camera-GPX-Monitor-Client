package com.labpano.gpxclient

/**
 * Pure policy for deciding which persisted Camera App storage-write alerts are new to the
 * current client session. Keeping this logic free of Android APIs makes stale-alert behavior
 * deterministic and directly testable.
 */
object StorageAlertPolicy {
    fun newAlerts(
        alerts: List<StorageWriteAlert>,
        initialized: Boolean,
        seenIds: Set<String>,
        connectionStartedAt: Long,
        now: Long,
        clockSkewMs: Long
    ): List<StorageWriteAlert> {
        return if (initialized) {
            alerts.filter { it.id.isNotBlank() && it.id !in seenIds }
        } else {
            alerts.filter {
                it.id.isNotBlank() &&
                    it.occurredAt > 0L &&
                    it.occurredAt >= connectionStartedAt &&
                    it.occurredAt <= now + clockSkewMs
            }
        }
    }

    fun retainedIds(alerts: List<StorageWriteAlert>, maxEntries: Int): Set<String> =
        alerts.asReversed()
            .asSequence()
            .map { it.id }
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxEntries.coerceAtLeast(0))
            .toSet()
}
