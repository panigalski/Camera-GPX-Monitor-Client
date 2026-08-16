package com.labpano.gpxclient

/**
 * Process-local snapshot updated by CameraConnectionService from background threads. Activities only read it.
 * A monotonically increasing revision lets the UI render each meaningful dashboard change once,
 * without using wall-clock timestamps as a render trigger. Storage-write history from the Camera
 * App is deliberately kept separate from alerts that are NEW in the current client connection.
 *
 * Live status and the full dashboard come from different HTTP requests. A full dashboard is much
 * heavier and can finish after a newer /live-status response. Main App 0.5.40 publishes device-
 * uptime timestamps and a process epoch, so ordering no longer depends on Pilot wall time (which can
 * move backward after GPS/NTP correction). Older Main Apps fall back to generatedAt wall time.
 */
object ClientSessionState {
    @Volatile var connectedAddress: String? = null
        private set
    @Volatile var lastDashboard: Dashboard? = null
        private set
    @Volatile var lastDashboardUpdatedAt: Long = 0L
        private set
    @Volatile var lastDashboardRevision: Long = 0L
        private set
    @Volatile var sessionStorageWriteAlerts: List<StorageWriteAlert> = emptyList()
        private set

    @Volatile private var realtimeGeneratedAt: Long = 0L
    @Volatile private var realtimeGeneratedElapsedRealtime: Long = 0L
    @Volatile private var serverProcessStartedElapsedRealtime: Long = 0L
    @Volatile private var serverProcessInstanceId: String = ""
    private val retiredProcessInstanceIds = LinkedHashSet<String>()

    @Synchronized
    fun beginConnection(address: String, dashboard: Dashboard) {
        connectedAddress = address
        lastDashboard = dashboard
        lastDashboardUpdatedAt = System.currentTimeMillis()
        retiredProcessInstanceIds.clear()
        serverProcessInstanceId = ""
        serverProcessStartedElapsedRealtime = 0L
        setRealtimeWatermark(dashboard)
        sessionStorageWriteAlerts = emptyList()
        lastDashboardRevision++
    }

    @Synchronized
    fun update(address: String, dashboard: Dashboard) {
        val addressChanged = connectedAddress != address
        val current = if (addressChanged) null else lastDashboard
        val processOrder = if (current == null) 0 else compareProcessEpoch(dashboard.processStartedElapsedRealtime, dashboard.processInstanceId)
        val fullPollOlderThanRealtime = current != null && (
            processOrder < 0 || (processOrder == 0 && isOlderThanRealtime(
                dashboard.generatedAt,
                dashboard.generatedElapsedRealtime
            ))
        )

        val monotonicOrderingAvailable = current != null && processOrder == 0 &&
            dashboard.generatedElapsedRealtime > 0L && realtimeGeneratedElapsedRealtime > 0L
        val mergedDashboard = when {
            current == null -> dashboard
            // A delayed response from a dead Main-App process must not update even slow fields.
            processOrder < 0 -> current
            // A newer Main-App process is a new truth epoch. Do not preserve stale Camera/Fragment
            // values merely because its lifecycle/revision counters restarted from small numbers.
            processOrder > 0 -> dashboard
            fullPollOlderThanRealtime -> dashboard.copy(
                outputFolder = current.outputFolder.ifBlank { dashboard.outputFolder },
                cameraRecording = current.cameraRecording,
                fragmentStorage = current.fragmentStorage,
                monitoring = current.monitoring,
                transfers = current.transfers,
                generatedAt = current.generatedAt,
                generatedElapsedRealtime = current.generatedElapsedRealtime,
                processStartedElapsedRealtime = current.processStartedElapsedRealtime
            )
            // With Main App 0.5.40+, the whole HTTP snapshot has already been ordered by Pilot
            // uptime. Its Camera/Fragment values are therefore the newer truth even if wall time or
            // per-component generation/revision moved backward.
            monotonicOrderingAvailable -> dashboard
            else -> dashboard.copy(
                cameraRecording = fresherCameraStatus(current.cameraRecording, dashboard.cameraRecording),
                fragmentStorage = fresherFragmentStorage(current.fragmentStorage, dashboard.fragmentStorage)
            )
        }

        val displayDashboard = if (current != null && processOrder == 0) {
            // Transport ordering metadata changes on every poll and is not UI state. Keep the
            // initially-stored values so equality/revision reflects meaningful dashboard changes.
            mergedDashboard.copy(
                generatedAt = current.generatedAt,
                generatedElapsedRealtime = current.generatedElapsedRealtime,
                processStartedElapsedRealtime = current.processStartedElapsedRealtime,
                processInstanceId = current.processInstanceId
            )
        } else {
            mergedDashboard
        }
        val dashboardChanged = lastDashboard != displayDashboard
        connectedAddress = address
        lastDashboard = displayDashboard
        lastDashboardUpdatedAt = System.currentTimeMillis()

        when {
            addressChanged -> {
                retiredProcessInstanceIds.clear()
                serverProcessInstanceId = ""
                serverProcessStartedElapsedRealtime = 0L
                setRealtimeWatermark(dashboard)
            }
            current == null || processOrder > 0 -> setRealtimeWatermark(dashboard)
            !fullPollOlderThanRealtime -> advanceRealtimeWatermark(
                dashboard.generatedAt,
                dashboard.generatedElapsedRealtime,
                dashboard.processStartedElapsedRealtime,
                dashboard.processInstanceId
            )
        }
        if (addressChanged) sessionStorageWriteAlerts = emptyList()
        if (addressChanged || dashboardChanged) lastDashboardRevision++
    }

    /** Merge the lightweight Main App live payload without discarding slower dashboard fields. */
    @Synchronized
    fun mergeLive(address: String, live: LiveStatus): Boolean {
        if (connectedAddress != address) return false
        val current = lastDashboard ?: return false
        val processOrder = compareProcessEpoch(live.processStartedElapsedRealtime, live.processInstanceId)

        // Delayed response from a previous Main-App process or an older request in this process.
        if (processOrder < 0) return false
        if (processOrder == 0 && isOlderThanRealtime(live.generatedAt, live.generatedElapsedRealtime)) return false

        val monotonicOrderingAvailable = processOrder == 0 &&
            live.generatedElapsedRealtime > 0L && realtimeGeneratedElapsedRealtime > 0L
        val merged = when {
            processOrder > 0 -> current.copy(
                // New Main-App process: take its realtime truth directly and let the next full poll
                // refresh the slower storage/report fields retained from the old dashboard.
                outputFolder = live.outputFolder.ifBlank { current.outputFolder },
                cameraRecording = live.cameraRecording,
                fragmentStorage = live.fragmentStorage,
                monitoring = live.monitoring,
                transfers = live.transfers,
                generatedAt = live.generatedAt,
                generatedElapsedRealtime = live.generatedElapsedRealtime,
                processStartedElapsedRealtime = live.processStartedElapsedRealtime,
                processInstanceId = live.processInstanceId
            )
            monotonicOrderingAvailable -> current.copy(
                outputFolder = live.outputFolder.ifBlank { current.outputFolder },
                cameraRecording = live.cameraRecording,
                fragmentStorage = live.fragmentStorage,
                monitoring = live.monitoring,
                transfers = live.transfers
            )
            else -> current.copy(
                outputFolder = live.outputFolder.ifBlank { current.outputFolder },
                cameraRecording = fresherCameraStatus(current.cameraRecording, live.cameraRecording),
                fragmentStorage = fresherFragmentStorage(current.fragmentStorage, live.fragmentStorage),
                monitoring = live.monitoring,
                transfers = live.transfers
            )
        }

        if (processOrder > 0) {
            realtimeGeneratedAt = live.generatedAt.coerceAtLeast(0L)
            realtimeGeneratedElapsedRealtime = live.generatedElapsedRealtime.coerceAtLeast(0L)
            val oldId = serverProcessInstanceId
            if (oldId.isNotBlank() && oldId != live.processInstanceId) retireProcessId(oldId)
            serverProcessStartedElapsedRealtime = live.processStartedElapsedRealtime.coerceAtLeast(0L)
            serverProcessInstanceId = live.processInstanceId.trim()
        } else {
            advanceRealtimeWatermark(
                live.generatedAt,
                live.generatedElapsedRealtime,
                live.processStartedElapsedRealtime,
                live.processInstanceId
            )
        }
        if (merged == current) return false
        lastDashboard = merged
        lastDashboardUpdatedAt = System.currentTimeMillis()
        lastDashboardRevision++
        return true
    }

    /** 1=newer process, -1=retired/older process, 0=same/legacy-unknown process. */
    private fun compareProcessEpoch(incomingElapsed: Long, incomingId: String): Int {
        val normalizedId = incomingId.trim()
        val currentId = serverProcessInstanceId
        if (normalizedId.isNotBlank() && currentId.isNotBlank()) {
            if (normalizedId == currentId) return 0
            if (retiredProcessInstanceIds.contains(normalizedId)) return -1
            // First response from an unseen process becomes the new epoch. Retire the process that
            // was current so any delayed in-flight response from it is rejected afterwards.
            return 1
        }

        val currentElapsed = serverProcessStartedElapsedRealtime
        if (incomingElapsed <= 0L || currentElapsed <= 0L || incomingElapsed == currentElapsed) return 0
        return if (incomingElapsed > currentElapsed) 1 else -1
    }

    private fun isOlderThanRealtime(incomingWall: Long, incomingElapsed: Long): Boolean {
        // Uptime is the authoritative order only when both sides supplied it. It cannot move
        // backward because of GPS/NTP changes.
        if (incomingElapsed > 0L && realtimeGeneratedElapsedRealtime > 0L) {
            return incomingElapsed < realtimeGeneratedElapsedRealtime
        }
        return incomingWall > 0L && realtimeGeneratedAt > 0L && incomingWall < realtimeGeneratedAt
    }

    private fun setRealtimeWatermark(dashboard: Dashboard) {
        realtimeGeneratedAt = dashboard.generatedAt.coerceAtLeast(0L)
        realtimeGeneratedElapsedRealtime = dashboard.generatedElapsedRealtime.coerceAtLeast(0L)
        serverProcessStartedElapsedRealtime = dashboard.processStartedElapsedRealtime.coerceAtLeast(0L)
        val incomingId = dashboard.processInstanceId.trim()
        if (incomingId.isNotBlank()) {
            val oldId = serverProcessInstanceId
            if (oldId.isNotBlank() && oldId != incomingId) retireProcessId(oldId)
            serverProcessInstanceId = incomingId
        }
    }

    private fun advanceRealtimeWatermark(
        wall: Long,
        elapsed: Long,
        processEpoch: Long,
        processInstanceId: String
    ) {
        realtimeGeneratedAt = maxOf(realtimeGeneratedAt, wall.coerceAtLeast(0L))
        realtimeGeneratedElapsedRealtime = maxOf(realtimeGeneratedElapsedRealtime, elapsed.coerceAtLeast(0L))
        if (processEpoch > 0L) serverProcessStartedElapsedRealtime = processEpoch
        val incomingId = processInstanceId.trim()
        if (incomingId.isNotBlank() && incomingId != serverProcessInstanceId) {
            if (serverProcessInstanceId.isNotBlank()) retireProcessId(serverProcessInstanceId)
            serverProcessInstanceId = incomingId
        }
    }

    private fun retireProcessId(id: String) {
        if (id.isBlank()) return
        retiredProcessInstanceIds += id
        while (retiredProcessInstanceIds.size > MAX_RETIRED_PROCESS_IDS) {
            val oldest = retiredProcessInstanceIds.iterator().next()
            retiredProcessInstanceIds.remove(oldest)
        }
    }

    private fun fresherFragmentStorage(
        current: FragmentStorageStatus,
        incoming: FragmentStorageStatus
    ): FragmentStorageStatus {
        // Process epoch must be checked BEFORE availability. A restarted Main App may legitimately
        // report Unavailable while it is establishing a fresh Camera-settings baseline; retaining an
        // old 8 GB value from the dead process would be misleading.
        val incomingEpoch = incoming.processStartedElapsedRealtime
        val currentEpoch = current.processStartedElapsedRealtime
        if (incomingEpoch > 0L && currentEpoch > 0L && incomingEpoch != currentEpoch) {
            return if (incomingEpoch > currentEpoch) incoming else current
        }

        if (incoming.available && !current.available) return incoming
        if (current.available && !incoming.available) return current

        if (incoming.revision > 0L || current.revision > 0L) {
            if (incoming.revision > current.revision) return incoming
            if (incoming.revision < current.revision) return current
        }

        if (incoming.updatedAt > current.updatedAt) return incoming
        if (current.updatedAt > incoming.updatedAt) return current
        return if (incoming.available) incoming else current.copy(
            error = incoming.error.ifBlank { current.error }
        )
    }

    /**
     * Pilot Camera lifecycle is monotonic. Within one generation, a completed/Ready state cannot
     * legitimately revert to Recording. Across generations, the newer generation always wins.
     * Legacy generation=0 falls back to the Main-App event timestamp.
     */
    private fun fresherCameraStatus(
        current: CameraRecordingStatus,
        incoming: CameraRecordingStatus
    ): CameraRecordingStatus {
        val currentGeneration = current.generation
        val incomingGeneration = incoming.generation

        if (currentGeneration > 0L || incomingGeneration > 0L) {
            if (incomingGeneration < currentGeneration) {
                // In a known Main-App process lifecycle generation cannot decrease. Process restart
                // is handled at the HTTP snapshot epoch before this legacy component fallback runs.
                if (serverProcessStartedElapsedRealtime > 0L) return current
                if (incoming.updatedAt > current.updatedAt) return incoming
                return current
            }
            if (incomingGeneration > currentGeneration) return incoming
        }

        if (incoming.updatedAt < current.updatedAt) return current
        if (incoming.updatedAt > current.updatedAt) return incoming

        val currentExplicitReady = currentGeneration > 0L && !current.recording &&
            current.source.contains("pilot-camera", ignoreCase = true)
        if (currentExplicitReady && incoming.recording) return current
        return incoming
    }

    @Synchronized
    fun addSessionStorageWriteAlerts(alerts: List<StorageWriteAlert>) {
        if (alerts.isEmpty()) return
        val merged = (alerts + sessionStorageWriteAlerts)
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedByDescending { it.occurredAt }
            .take(MAX_SESSION_STORAGE_ALERTS)
            .toList()
        if (merged != sessionStorageWriteAlerts) {
            sessionStorageWriteAlerts = merged
            lastDashboardRevision++
        }
    }

    @Synchronized
    fun clear() {
        connectedAddress = null
        lastDashboard = null
        lastDashboardUpdatedAt = 0L
        realtimeGeneratedAt = 0L
        realtimeGeneratedElapsedRealtime = 0L
        serverProcessStartedElapsedRealtime = 0L
        serverProcessInstanceId = ""
        retiredProcessInstanceIds.clear()
        sessionStorageWriteAlerts = emptyList()
        lastDashboardRevision++
    }

    private const val MAX_SESSION_STORAGE_ALERTS = 20
    private const val MAX_RETIRED_PROCESS_IDS = 8
}
