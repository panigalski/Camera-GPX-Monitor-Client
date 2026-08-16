package com.labpano.gpxclient

enum class ReportType(val apiValue: String, val displayName: String) {
    ERROR("error", "Errors"),
    FAILED("failed", "Failed"),
    GOOD("good", "Good")
}

data class ReportEntry(val timestamp: String, val path: String, val message: String)
data class TransferEntry(
    val id: String,
    val sourceName: String,
    val destinationName: String,
    val copiedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val phase: String
)
data class StorageUsage(
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val usedPercent: Int,
    val error: String
)

data class CameraRecordingStatus(
    val available: Boolean,
    val recording: Boolean,
    val videoName: String,
    val updatedAt: Long,
    val source: String,
    /** Camera still owns/finalizes the MP4, but live image capture has stopped. */
    val finalizing: Boolean = false,
    /** Monotonic Main-App Camera lifecycle generation. 0 means legacy/filesystem fallback. */
    val generation: Long = 0L
)


data class FragmentStorageMode(
    val known: Boolean = false,
    val enabled: Boolean = false,
    val rawValue: String = "",
    val displayValue: String = "Unknown",
    /** Structured Camera value. "size" is the 4/6/8/10 GB Fragment Storage selector. */
    val limitType: String = "unknown",
    val sizeGb: Int? = null,
    val durationMinutes: Int? = null
)

data class FragmentStorageStatus(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val display: String = "Unavailable",
    val updatedAt: Long = 0L,
    val revision: Long = 0L,
    val source: String = "Unavailable",
    val error: String = "",
    /** Strong Camera-derived recording-family hint; blank when stock Camera exposes no safe signal. */
    val mode: String = "",
    val modeSource: String = "unknown",
    val modeUpdatedAt: Long = 0L,
    /** Exact Camera setting, e.g. 4gb, 6gb, 8gb, 10gb, 10min, 30min, 1h, 2h. */
    val rawValue: String = "",
    val limitType: String = "unknown",
    val sizeGb: Int? = null,
    val durationMinutes: Int? = null,
    /** Main-App process epoch expressed in device elapsed realtime. */
    val processStartedElapsedRealtime: Long = 0L,
    val stitched: FragmentStorageMode = FragmentStorageMode(),
    val streetView: FragmentStorageMode = FragmentStorageMode(),
    val unstitched: FragmentStorageMode = FragmentStorageMode(),
    val timeLapse: FragmentStorageMode = FragmentStorageMode()
)

data class LiveStatus(
    val generatedAt: Long,
    val outputFolder: String,
    val cameraRecording: CameraRecordingStatus,
    val fragmentStorage: FragmentStorageStatus = FragmentStorageStatus(),
    val monitoring: MonitoringStatus,
    val transfers: List<TransferEntry>,
    /** Main-App uptime when this response was built; monotonic within one Main-App process. */
    val generatedElapsedRealtime: Long = 0L,
    /** Main-App process epoch in Pilot device uptime. */
    val processStartedElapsedRealtime: Long = 0L,
    /** Opaque Main-App process identity; robust even when Pilot device uptime resets after reboot. */
    val processInstanceId: String = ""
)

data class MonitoringStatus(
    val available: Boolean,
    val requested: Boolean,
    val serviceRunning: Boolean,
    val lastStatus: String
)

data class ReportFileHealth(
    val name: String,
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val sizeBytes: Long
)

data class ReportHealth(
    val supported: Boolean,
    val destination: String,
    val destinationType: String,
    val available: Boolean,
    val writable: Boolean,
    val ioHealthy: Boolean,
    val files: List<ReportFileHealth>,
    val lastSuccessAt: Long,
    val lastFailureAt: Long,
    val lastOperation: String,
    val lastError: String
)

data class BatteryStatus(
    val available: Boolean,
    val percent: Int,
    val charging: Boolean,
    val full: Boolean,
    val status: String,
    val powerSource: String,
    val temperatureC: Double?,
    val temperatureSource: String,
    val voltageMillivolts: Int,
    val health: String,
    val error: String
)

data class StorageWriteAlert(
    val id: String,
    val occurredAt: Long,
    val storageType: String,
    val videoName: String,
    val destination: String,
    val operation: String,
    val message: String
)



data class BluetoothDeviceDiagnostics(
    val name: String,
    val address: String,
    val transport: String,
    val likelyGps: Boolean,
    val rssiDbm: Int?,
    val rssiObservedAt: Long,
    val rssiNote: String
)

data class BluetoothDiagnostics(
    val available: Boolean,
    val enabled: Boolean,
    val devices: List<BluetoothDeviceDiagnostics>,
    val error: String
)

data class LocationSourceDiagnostics(
    val available: Boolean,
    val permissionGranted: Boolean,
    val fresh: Boolean,
    val sourceType: String,
    val sourceLabel: String,
    val provider: String,
    val mocked: Boolean,
    val lastFixAt: Long,
    val accuracyMeters: Double?,
    val inferredExternalBluetoothDevice: String
)

data class GnssSignalDiagnostics(
    val supported: Boolean,
    val permissionGranted: Boolean,
    val running: Boolean,
    val fresh: Boolean,
    val satellitesVisible: Int,
    val satellitesUsedInFix: Int,
    val averageCn0DbHz: Double?,
    val maxCn0DbHz: Double?,
    val firstFixMs: Int?,
    val updatedAt: Long,
    val activeLocationMocked: Boolean,
    val signalMatchesActiveLocationSource: Boolean,
    val constellations: Map<String, Int>,
    val usedConstellations: Map<String, Int>
)

data class DeviceDiagnostics(
    val bluetooth: BluetoothDiagnostics,
    val location: LocationSourceDiagnostics,
    val gnss: GnssSignalDiagnostics
)

data class Dashboard(
    val appVersion: String,
    val monitoringDirectory: String,
    val outputFolder: String,
    val internalStorage: StorageUsage,
    val externalStorage: StorageUsage?,
    val battery: BatteryStatus,
    val cameraRecording: CameraRecordingStatus,
    val fragmentStorage: FragmentStorageStatus = FragmentStorageStatus(),
    val monitoring: MonitoringStatus,
    val reportHealth: ReportHealth,
    val errors: List<ReportEntry>,
    val failed: List<ReportEntry>,
    val good: List<ReportEntry>,
    val transfers: List<TransferEntry>,
    val storageWriteAlerts: List<StorageWriteAlert>,
    val storageWriteAlertsSupported: Boolean,
    val deviceDiagnostics: DeviceDiagnostics? = null,
    /** Legacy server wall-clock creation time; kept for Main Apps without monotonic API fields. */
    val generatedAt: Long = 0L,
    /** Main-App uptime when this response was built; preferred for response ordering. */
    val generatedElapsedRealtime: Long = 0L,
    /** Main-App process epoch in Pilot device uptime. */
    val processStartedElapsedRealtime: Long = 0L,
    /** Opaque Main-App process identity; robust even when Pilot device uptime resets after reboot. */
    val processInstanceId: String = ""
)


data class PendingGpxItem(
    val id: String,
    val status: String,
    val completedAt: String,
    val videoName: String,
    val videoPath: String,
    val gpxName: String,
    val gpxPath: String,
    val gpxSizeBytes: Long,
    val downloadUrl: String
)
