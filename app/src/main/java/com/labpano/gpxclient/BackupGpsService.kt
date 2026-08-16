package com.labpano.gpxclient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs

/**
 * Collects a bounded smartphone location timeline and creates camera-GPX backups whose
 * timestamps/XML structure are preserved while every timestamped coordinate is replaced.
 */
class BackupGpsService : Service(), LocationListener {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val timelineLock = ReentrantReadWriteLock()
    private val memoryPoints = ArrayDeque<Location>()

    private lateinit var locationManager: LocationManager
    private lateinit var locationThread: HandlerThread
    private lateinit var prefs: SharedPreferences
    private lateinit var timelineFile: File
    private lateinit var dailyTimelineDirectory: File
    private val dailySyncedSignatures = mutableMapOf<String, Pair<Long, Long>>()

    private var gpsFixCount = 0L
    private var lastGpsFixAt = 0L
    private var connectedSatelliteCount = 0
    private var availableSatelliteCount = 0
    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedGpsWallClock = 0L
    private var cameraPollFailures = 0
    private var nextCameraPollAt = 0L
    private var terminalStatus: String? = null
    private var lastEnsuredBackupDate = ""

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var connected = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) connected++
            }
            publishSatelliteCounts(connected, status.satelliteCount)
        }

        override fun onStopped() = publishSatelliteCounts(0, 0)
    }

    @Suppress("DEPRECATION")
    private val legacyGpsStatusListener = GpsStatus.Listener { event ->
        if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
            val gpsStatus = runCatching { locationManager.getGpsStatus(null) }.getOrNull()
            val satellites = gpsStatus?.satellites?.toList().orEmpty()
            publishSatelliteCounts(satellites.count { it.usedInFix() }, satellites.size)
        } else if (event == GpsStatus.GPS_EVENT_STOPPED) {
            publishSatelliteCounts(0, 0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        timelineFile = File(filesDir, "phone_gps_timeline.csv")
        dailyTimelineDirectory = File(filesDir, "phone_gps_daily").apply { mkdirs() }
        createChannel()
        val cameraAttached = !prefs.getString(KEY_ACTIVE_CAMERA_ADDRESS, "").isNullOrBlank()
        startForeground(
            NOTIFICATION_ID,
            notification(
                if (cameraAttached) "Collecting smartphone GPS • waiting for camera GPX"
                else "Collecting contingency smartphone GPS • camera not connected"
            )
        )

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationThread = HandlerThread("phone-gps-location").apply { start() }
        if (!hasLocationPermission()) {
            stopForConfigurationError("Location permission missing; automatic backup stopped")
            return
        }
        if (!hasBackupFolderGrant()) {
            stopForConfigurationError("Backup folder permission was lost; select the folder again")
            return
        }

        val locationLooper = locationThread.looper
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, locationLooper)
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, this, locationLooper)
        }
        val gnssRegistered = runCatching {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, Handler(locationLooper))
        }.getOrDefault(false)
        if (!gnssRegistered) {
            @Suppress("DEPRECATION")
            runCatching { locationManager.addGpsStatusListener(legacyGpsStatusListener) }
        }

        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(
                KEY_STATUS,
                if (cameraAttached) {
                    "Automatic backup enabled • collecting phone GPS • waiting for finalized camera GPX"
                } else {
                    "Automatic backup enabled • collecting contingency phone GPS • camera not connected"
                }
            )
            .apply()
        publishGpsReceiverState()
        executor.scheduleWithFixedDelay({ pollCompletedCameraFiles() }, 0, 3, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({ syncDailyPhoneGpxArchives() }, 5, 30, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({ pruneTimeline() }, 2, 30, TimeUnit.MINUTES)
    }

    private fun hasBackupFolderGrant(): Boolean {
        val folderText = prefs.getString(KEY_FOLDER, null) ?: return false
        val folderUri = runCatching { Uri.parse(folderText) }.getOrNull() ?: return false
        return contentResolver.persistedUriPermissions.any {
            it.uri == folderUri && it.isReadPermission && it.isWritePermission
        }
    }

    private fun stopForConfigurationError(message: String) {
        terminalStatus = message
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putString(KEY_STATUS, message)
            .apply()
        updateNotification(message)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        val manager = if (::locationManager.isInitialized) locationManager else null
        val callbackThread = if (::locationThread.isInitialized) locationThread else null
        if (manager != null) {
            // Some vendor LocationManager implementations can block during listener removal.
            // Do not perform that IPC on the app main thread while an Activity may be starting.
            Thread({
                runCatching { manager.removeUpdates(this) }
                runCatching { manager.unregisterGnssStatusCallback(gnssStatusCallback) }
                @Suppress("DEPRECATION")
                runCatching { manager.removeGpsStatusListener(legacyGpsStatusListener) }
                callbackThread?.let { thread ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) thread.quitSafely() else thread.quit()
                }
            }, "gps-listener-cleanup").apply { isDaemon = true }.start()
        } else {
            callbackThread?.let { thread ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) thread.quitSafely() else thread.quit()
            }
        }
        executor.shutdownNow()
        if (::prefs.isInitialized) {
            // The periodic daily archive can be up to 30 seconds behind the durable internal CSV.
            // Perform one final best-effort SAF sync off the main thread when the service stops so
            // an intentional Stop normally leaves the selected Backup folder fully current.
            Thread({
                runCatching { syncDailyPhoneGpxArchives() }
            }, "daily-gpx-final-sync").apply { isDaemon = false }.start()

            prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .putString(KEY_STATUS, terminalStatus ?: "Automatic backup stopped")
                .apply()
        }
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        val copy = Location(location)
        if (!isAcceptableLocation(copy)) return

        gpsFixCount++
        lastGpsFixAt = System.currentTimeMillis()
        timelineLock.write {
            memoryPoints.addLast(copy)
            appendTimelineLocked(copy)
            appendDailyTimelineLocked(copy)
            val cutoff = System.currentTimeMillis() - MEMORY_RETENTION_MS
            while (memoryPoints.isNotEmpty() && memoryPoints.peekFirst().time < cutoff) {
                memoryPoints.removeFirst()
            }
        }

        prefs.edit()
            .putFloat(KEY_ACCURACY, if (copy.hasAccuracy()) copy.accuracy else -1f)
            .putLong(KEY_GPS_FIX_COUNT, gpsFixCount)
            .putLong(KEY_GPS_LAST_FIX_AT, lastGpsFixAt)
            .putString(KEY_GPS_PROVIDER, copy.provider ?: "Unknown")
            .putString(KEY_GPS_COORDINATES, String.format(Locale.US, "%.6f, %.6f", copy.latitude, copy.longitude))
            .putString(KEY_GPS_STATE, "Receiving quality-filtered location fixes • temporary timeline active")
            .apply()
    }

    private fun isAcceptableLocation(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return false
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
        if (location.time <= 0L) return false
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) return false

        val provider = location.provider.orEmpty()
        val now = System.currentTimeMillis()
        if (provider.equals(LocationManager.NETWORK_PROVIDER, ignoreCase = true) &&
            now - lastAcceptedGpsWallClock < NETWORK_SUPPRESSION_AFTER_GPS_MS
        ) {
            return false
        }

        val previous = lastAcceptedLocation
        if (previous != null && location.time > previous.time) {
            val seconds = (location.time - previous.time) / 1_000.0
            if (seconds > 0.0) {
                val distance = previous.distanceTo(location).toDouble()
                val uncertainty = (if (previous.hasAccuracy()) previous.accuracy else 0f) +
                    (if (location.hasAccuracy()) location.accuracy else 0f)
                if (distance / seconds > MAX_REASONABLE_SPEED_METERS_PER_SECOND &&
                    distance > uncertainty * 2.0
                ) {
                    return false
                }
            }
        }

        if (provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)) {
            lastAcceptedGpsWallClock = now
        }
        lastAcceptedLocation = Location(location)
        return true
    }

    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = publishGpsReceiverState()
    override fun onProviderEnabled(provider: String) = publishGpsReceiverState()
    override fun onProviderDisabled(provider: String) = publishGpsReceiverState()

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun publishSatelliteCounts(connected: Int, available: Int) {
        connectedSatelliteCount = connected.coerceAtLeast(0)
        availableSatelliteCount = available.coerceAtLeast(connectedSatelliteCount)
        prefs.edit()
            .putInt(KEY_GPS_CONNECTED_SATELLITE_COUNT, connectedSatelliteCount)
            .putInt(KEY_GPS_AVAILABLE_SATELLITE_COUNT, availableSatelliteCount)
            .apply()
    }

    private fun publishGpsReceiverState() {
        if (!::locationManager.isInitialized || !::prefs.isInitialized) return
        if (!hasLocationPermission()) {
            stopForConfigurationError("Location permission was revoked; automatic backup stopped")
            return
        }
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val state = when {
            !hasLocationPermission() -> "Location permission missing"
            gpsEnabled -> if (lastGpsFixAt > 0L) "GPS enabled • receiving fixes • timeline active" else "GPS enabled • waiting for first fix"
            networkEnabled -> if (lastGpsFixAt > 0L) "Network location • receiving fixes • timeline active" else "GPS disabled • waiting for network location"
            else -> "Phone location providers are disabled"
        }
        prefs.edit()
            .putBoolean(KEY_GPS_PROVIDER_ENABLED, gpsEnabled || networkEnabled)
            .putString(KEY_GPS_STATE, state)
            .putLong(KEY_GPS_FIX_COUNT, gpsFixCount)
            .putLong(KEY_GPS_LAST_FIX_AT, lastGpsFixAt)
            .putInt(KEY_GPS_CONNECTED_SATELLITE_COUNT, connectedSatelliteCount)
            .putInt(KEY_GPS_AVAILABLE_SATELLITE_COUNT, availableSatelliteCount)
            .apply()
    }

    private fun pollCompletedCameraFiles() {
        val now = System.currentTimeMillis()
        val rawAddress = prefs.getString(KEY_ACTIVE_CAMERA_ADDRESS, "").orEmpty()
        if (rawAddress.isBlank()) {
            // Camera connectivity is optional. The phone GPS timeline and daily archive are the
            // contingency source and continue uninterrupted while the backup toggle is enabled.
            cameraPollFailures = 0
            nextCameraPollAt = 0L
            publishGpsReceiverState()
            setStatus("Phone GPS timeline active • camera not connected • collecting contingency GPS")
            updateNotification("Collecting contingency phone GPS • camera not connected")
            return
        }
        if (now < nextCameraPollAt) return
        if (!hasBackupFolderGrant()) {
            stopForConfigurationError("Backup folder permission was lost; automatic backup stopped")
            return
        }

        try {
            val base = DashboardClient().normalizeAddress(rawAddress)
            publishGpsReceiverState()
            val queue = DashboardClient().fetchPendingGpx(base).sortedBy { it.completedAt }
            cameraPollFailures = 0
            nextCameraPollAt = 0L

            val processed = processedSet()
            val unprocessed = queue.filterNot { processed.contains(itemKey(it)) }
            if (unprocessed.isEmpty()) {
                if (queue.isEmpty()) {
                    setStatus("Phone GPS timeline active • waiting for finalized camera GPX")
                    updateNotification("Collecting phone GPS • waiting for camera GPX")
                } else {
                    setStatus("Phone GPS timeline active • all finalized GPX files processed • latest: ${queue.last().videoName}")
                }
                return
            }

            val retries = loadRetryStates()
            val pending = unprocessed.firstOrNull { (retries[itemKey(it)]?.nextAttemptAt ?: 0L) <= now }
            if (pending == null) {
                val nextRetry = unprocessed.mapNotNull { retries[itemKey(it)]?.nextAttemptAt }.minOrNull() ?: now
                val waitSeconds = ((nextRetry - now).coerceAtLeast(0L) + 999L) / 1_000L
                setStatus("Phone GPS timeline active • ${unprocessed.size} camera GPX item(s) waiting for retry • next in ${waitSeconds}s")
                return
            }

            try {
                processPendingGpx(base, pending)
                clearRetryState(itemKey(pending))
            } catch (error: Throwable) {
                val retry = registerRetryFailure(itemKey(pending), error)
                val videoName = pending.videoName.ifBlank { pending.gpxName }
                setStatus(
                    "Backup deferred for $videoName: ${error.message ?: error.javaClass.simpleName} • " +
                        "attempt ${retry.attempts}, retrying later while other files continue"
                )
                updateNotification("Deferred camera GPX: $videoName")
            }
        } catch (error: Throwable) {
            cameraPollFailures++
            val exponent = (cameraPollFailures - 1).coerceIn(0, 6)
            val delay = (CAMERA_FAILURE_BASE_DELAY_MS shl exponent).coerceAtMost(CAMERA_FAILURE_MAX_DELAY_MS)
            nextCameraPollAt = now + delay
            setStatus("Backup waiting: ${friendlyConnectionError(error)} • retrying in ${delay / 1_000L}s")
        }
    }


    private fun friendlyConnectionError(error: Throwable): String {
        val names = generateSequence(error as Throwable?) { it.cause }.map { it.javaClass.simpleName }.toSet()
        return when {
            "SocketTimeoutException" in names -> "camera connection timed out"
            "ConnectException" in names || "NoRouteToHostException" in names -> "camera is not reachable"
            "UnknownHostException" in names -> "camera address could not be resolved"
            else -> "camera connection is temporarily unavailable"
        }
    }

    private fun processPendingGpx(base: String, item: PendingGpxItem) {
        require(item.downloadUrl.isNotBlank()) { "Camera queue item has no GPX download URL" }
        val key = itemKey(item)
        val videoName = item.videoName.ifBlank { item.gpxName.replace(Regex("(?i)\\.gpx$"), ".mp4") }
        val absoluteUrl = if (item.downloadUrl.startsWith("http://", true) || item.downloadUrl.startsWith("https://", true)) {
            item.downloadUrl
        } else {
            base.trimEnd('/') + "/" + item.downloadUrl.trimStart('/')
        }

        setStatus("Camera finalized ${item.status}: $videoName • downloading ${item.gpxName}")
        updateNotification("Downloading camera GPX for $videoName")

        val sourceBytes = getBytes(absoluteUrl)
        if (item.gpxSizeBytes > 0L) {
            require(sourceBytes.size.toLong() == item.gpxSizeBytes) {
                "Downloaded GPX size ${sourceBytes.size} bytes does not match camera size ${item.gpxSizeBytes} bytes"
            }
        }
        val sourceGpx = sourceBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require(sourceGpx.contains("<gpx", true)) { "Downloaded file is not GPX" }

        val timestamps = extractTrackPointTimes(sourceGpx)
        require(timestamps.isNotEmpty()) { "Camera GPX contains no timestamped track points" }
        val phonePoints = loadTimeline(
            timestamps.minOrNull()!! - MATCH_MARGIN_MS,
            timestamps.maxOrNull()!! + MATCH_MARGIN_MS
        )
        require(phonePoints.isNotEmpty()) {
            "No smartphone GPS points cover ${iso(timestamps.first())} to ${iso(timestamps.last())}"
        }

        setStatus("Building phone backup for $videoName • ${timestamps.size} camera timestamps • ${phonePoints.size} quality phone fixes")
        val exactReplacement = runCatching { replaceCoordinates(sourceGpx, phonePoints) }.getOrNull()
        val backupXml: String
        val backupSummary: String
        if (exactReplacement != null) {
            val replacedTimes = extractTrackPointTimes(exactReplacement.xml)
            require(replacedTimes == timestamps) {
                "Backup GPX timestamp verification failed; camera GPX timing was not preserved"
            }
            backupXml = exactReplacement.xml
            backupSummary = "${exactReplacement.replaced}/${exactReplacement.timestampedPoints} camera timestamps matched"
        } else {
            // A temporary phone-GPS gap must not make the entire MP4 disappear from Automatic
            // Backup. Preserve only truthful fixes actually collected during this video's Camera
            // timestamp interval instead of inventing coordinates for unmatched timestamps.
            val videoStart = timestamps.minOrNull()!!
            val videoEnd = timestamps.maxOrNull()!!
            val directPhonePoints = phonePoints.filter { it.time in videoStart..videoEnd }
            require(directPhonePoints.isNotEmpty()) {
                "No smartphone GPS fixes were collected during ${iso(videoStart)} to ${iso(videoEnd)}"
            }
            backupXml = buildPhoneOnlyVideoGpx(videoName, directPhonePoints)
            backupSummary = "${directPhonePoints.size} phone fixes saved (direct phone-track fallback)"
        }

        val requestedName = BackupGpxLayout.perVideoFileName(videoName)
        val videoDateFolder = BackupGpxLayout.dateFolderName(
            videoName = videoName,
            completedAtMillis = parseIso(item.completedAt)
        )
        val savedPath = writePerVideoGpx(
            dateFolderName = videoDateFolder,
            requestedName = requestedName,
            bytes = backupXml.toByteArray(Charsets.UTF_8)
        )
        markProcessed(key)
        setStatus("Saved $savedPath • $backupSummary • source ${item.status}")
        updateNotification("Saved $savedPath")
    }

    private data class Replacement(val xml: String, val replaced: Int, val timestampedPoints: Int)

    private fun buildPhoneOnlyVideoGpx(videoName: String, points: List<Location>): String {
        val trackName = File(videoName).nameWithoutExtension.ifBlank { "video" } + " backup"
        val phonePoints = points.sortedBy { it.time }.map { point ->
            PhoneGpsPoint(
                time = point.time,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = point.altitude.takeIf { point.hasAltitude() && it.isFinite() },
                accuracyMeters = point.accuracy.takeIf { point.hasAccuracy() && it.isFinite() },
                provider = point.provider ?: "unknown",
                speedMetersPerSecond = point.speed.takeIf { point.hasSpeed() && it.isFinite() },
                bearingDegrees = point.bearing.takeIf { point.hasBearing() && it.isFinite() }
            )
        }
        return DailyPhoneGpxWriter.build(trackName, phonePoints)
    }

    private fun replaceCoordinates(source: String, phonePoints: List<Location>): Replacement {
        val pointRegex = Regex("""<trkpt\b([^>]*)>(.*?)</trkpt>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val timeRegex = Regex("""<time>\s*([^<]+?)\s*</time>""", RegexOption.IGNORE_CASE)
        val latRegex = Regex("""\blat\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)
        val lonRegex = Regex("""\blon\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)
        val eleRegex = Regex("""<ele>\s*[^<]*\s*</ele>""", RegexOption.IGNORE_CASE)
        var replaced = 0
        var timestamped = 0
        var cursor = 0
        val out = StringBuilder(source.length + 256)

        pointRegex.findAll(source).forEach { match ->
            out.append(source, cursor, match.range.first)
            var attributes = match.groupValues[1]
            var body = match.groupValues[2]
            val timestamp = timeRegex.find(body)?.groupValues?.get(1)?.let(::parseIso)
            if (timestamp != null) {
                timestamped++
                val nearest = nearestLocation(phonePoints, timestamp)
                if (nearest != null && abs(nearest.time - timestamp) <= MAX_MATCH_DISTANCE_MS) {
                    val lat = String.format(Locale.US, "lat=\"%.8f\"", nearest.latitude)
                    val lon = String.format(Locale.US, "lon=\"%.8f\"", nearest.longitude)
                    attributes = if (latRegex.containsMatchIn(attributes)) attributes.replace(latRegex, lat) else "$attributes $lat"
                    attributes = if (lonRegex.containsMatchIn(attributes)) attributes.replace(lonRegex, lon) else "$attributes $lon"
                    if (nearest.hasAltitude()) {
                        val ele = String.format(Locale.US, "<ele>%.3f</ele>", nearest.altitude)
                        body = if (eleRegex.containsMatchIn(body)) body.replace(eleRegex, ele) else ele + body
                    }
                    replaced++
                }
            }
            out.append("<trkpt").append(attributes).append('>').append(body).append("</trkpt>")
            cursor = match.range.last + 1
        }
        out.append(source, cursor, source.length)
        require(timestamped > 0) { "Camera GPX contains no parseable track-point timestamps" }
        require(replaced == timestamped) {
            "Smartphone timeline matched $replaced of $timestamped timestamped camera points"
        }
        return Replacement(out.toString(), replaced, timestamped)
    }

    private fun extractTrackPointTimes(gpx: String): List<Long> {
        val pointRegex = Regex("""<trkpt\b[^>]*>(.*?)</trkpt>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val timeRegex = Regex("""<time>\s*([^<]+?)\s*</time>""", RegexOption.IGNORE_CASE)
        return pointRegex.findAll(gpx).mapNotNull { point ->
            timeRegex.find(point.groupValues[1])?.groupValues?.get(1)?.let(::parseIso)
        }.toList()
    }

    private fun nearestLocation(sorted: List<Location>, time: Long): Location? {
        if (sorted.isEmpty()) return null
        var low = 0
        var high = sorted.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (sorted[mid].time < time) low = mid + 1 else high = mid - 1
        }
        val from = (low - 3).coerceAtLeast(0)
        val to = (low + 3).coerceAtMost(sorted.lastIndex)
        return (from..to).map { sorted[it] }.minByOrNull { point ->
            val accuracyPenalty = if (point.hasAccuracy()) point.accuracy.toDouble() * ACCURACY_SCORE_MILLISECONDS_PER_METER else 0.0
            abs(point.time - time).toDouble() + accuracyPenalty
        }
    }

    private fun appendTimelineLocked(location: Location) {
        runCatching {
            timelineFile.parentFile?.mkdirs()
            timelineFile.appendText(
                String.format(
                    Locale.US,
                    "%d,%.9f,%.9f,%.4f,%.2f,%s\n",
                    location.time,
                    location.latitude,
                    location.longitude,
                    if (location.hasAltitude()) location.altitude else Double.NaN,
                    if (location.hasAccuracy()) location.accuracy else Float.NaN,
                    location.provider ?: "unknown"
                ),
                Charsets.UTF_8
            )
        }.onFailure {
            setStatus("Phone GPS received but temporary timeline write failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun appendDailyTimelineLocked(location: Location) {
        runCatching {
            dailyTimelineDirectory.mkdirs()
            val dateName = SimpleDateFormat(DATE_FOLDER_PATTERN, Locale.US).format(Date(location.time))
            val file = File(dailyTimelineDirectory, "$dateName.csv")
            file.appendText(
                String.format(
                    Locale.US,
                    "%d,%.9f,%.9f,%.4f,%.2f,%s,%.4f,%.3f\n",
                    location.time,
                    location.latitude,
                    location.longitude,
                    if (location.hasAltitude()) location.altitude else Double.NaN,
                    if (location.hasAccuracy()) location.accuracy else Float.NaN,
                    (location.provider ?: "unknown").replace(',', '_'),
                    if (location.hasSpeed()) location.speed else Float.NaN,
                    if (location.hasBearing()) location.bearing else Float.NaN
                ),
                Charsets.UTF_8
            )
        }.onFailure {
            prefs.edit().putString(
                KEY_DAILY_GPX_ERROR,
                "Daily phone GPS log write failed: ${it.message ?: it.javaClass.simpleName}"
            ).apply()
        }
    }

    private fun syncDailyPhoneGpxArchives() {
        if (!hasBackupFolderGrant()) return
        ensureCurrentDateBackupFolder()
        val files = timelineLock.read {
            dailyTimelineDirectory.listFiles { file -> file.isFile && file.extension.equals("csv", true) }
                .orEmpty()
                .sortedBy { it.name }
                .toList()
        }
        for (file in files) {
            val signature = file.length() to file.lastModified()
            if (dailySyncedSignatures[file.absolutePath] == signature) continue
            try {
                val points = readDailyTimeline(file)
                if (points.isEmpty()) continue
                val dateName = file.nameWithoutExtension
                val bytes = DailyPhoneGpxWriter.build(dateName, points).toByteArray(Charsets.UTF_8)
                val savedPath = writeDailyPhoneGpx(dateName, bytes)
                dailySyncedSignatures[file.absolutePath] = signature
                prefs.edit()
                    .putString(KEY_DAILY_GPX_LAST_PATH, savedPath)
                    .putLong(KEY_DAILY_GPX_LAST_SYNC_AT, System.currentTimeMillis())
                    .putString(KEY_DAILY_GPX_ERROR, "")
                    .apply()
            } catch (error: Throwable) {
                prefs.edit().putString(
                    KEY_DAILY_GPX_ERROR,
                    "Daily phone GPX sync failed for ${file.nameWithoutExtension}: ${error.message ?: error.javaClass.simpleName}"
                ).apply()
            }
        }
    }

    private fun ensureCurrentDateBackupFolder() {
        val dateName = SimpleDateFormat(DATE_FOLDER_PATTERN, Locale.US).format(Date())
        if (lastEnsuredBackupDate == dateName) return
        runCatching {
            val treeText = prefs.getString(KEY_FOLDER, null) ?: error("Select a smartphone backup folder")
            val tree = Uri.parse(treeText)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            getOrCreateDirectory(root, dateName)
            lastEnsuredBackupDate = dateName
        }.onFailure { error ->
            prefs.edit().putString(
                KEY_DAILY_GPX_ERROR,
                "Backup date folder creation failed for $dateName: ${error.message ?: error.javaClass.simpleName}"
            ).apply()
        }
    }

    private fun readDailyTimeline(file: File): List<PhoneGpsPoint> = timelineLock.read {
        if (!file.isFile) return@read emptyList()
        val result = mutableListOf<PhoneGpsPoint>()
        file.useLines(Charsets.UTF_8) { lines ->
            lines.forEach { line ->
                val parts = line.split(',', limit = 8)
                if (parts.size < 6) return@forEach
                val time = parts[0].toLongOrNull() ?: return@forEach
                val latitude = parts[1].toDoubleOrNull() ?: return@forEach
                val longitude = parts[2].toDoubleOrNull() ?: return@forEach
                if (!latitude.isFinite() || !longitude.isFinite()) return@forEach
                val altitude = parts.getOrNull(3)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                val accuracy = parts.getOrNull(4)?.toFloatOrNull()?.takeIf { it.isFinite() }
                val provider = parts.getOrNull(5).orEmpty().ifBlank { "unknown" }
                val speed = parts.getOrNull(6)?.toFloatOrNull()?.takeIf { it.isFinite() }
                val bearing = parts.getOrNull(7)?.toFloatOrNull()?.takeIf { it.isFinite() }
                result += PhoneGpsPoint(
                    time = time,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracyMeters = accuracy,
                    provider = provider,
                    speedMetersPerSecond = speed,
                    bearingDegrees = bearing
                )
            }
        }
        result.sortedBy { it.time }
    }

    private fun loadTimeline(from: Long, to: Long): List<Location> = timelineLock.read {
        val result = mutableListOf<Location>()
        if (timelineFile.exists()) {
            timelineFile.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { line ->
                    val parts = line.split(',', limit = 6)
                    if (parts.size < 6) return@forEach
                    val time = parts[0].toLongOrNull() ?: return@forEach
                    if (time !in from..to) return@forEach
                    val latitude = parts[1].toDoubleOrNull() ?: return@forEach
                    val longitude = parts[2].toDoubleOrNull() ?: return@forEach
                    val altitude = parts[3].toDoubleOrNull()
                    val accuracy = parts[4].toFloatOrNull()
                    result += Location(parts[5]).apply {
                        this.time = time
                        this.latitude = latitude
                        this.longitude = longitude
                        if (altitude != null && altitude.isFinite()) this.altitude = altitude
                        if (accuracy != null && accuracy.isFinite()) this.accuracy = accuracy
                    }
                }
            }
        }
        memoryPoints.filterTo(result) { it.time in from..to }
        result
            .filter { it.latitude.isFinite() && it.longitude.isFinite() }
            .distinctBy { listOf(it.time, it.latitude, it.longitude, it.provider) }
            .sortedBy { it.time }
    }

    private fun pruneTimeline() {
        timelineLock.write {
            if (!timelineFile.exists()) return@write
            val cutoff = System.currentTimeMillis() - TIMELINE_RETENTION_MS
            val temporary = File(timelineFile.parentFile, timelineFile.name + ".prune.tmp")
            runCatching {
                timelineFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                        reader.forEachLine { line ->
                            val timestamp = line.substringBefore(',').toLongOrNull()
                            if (timestamp != null && timestamp >= cutoff) {
                                writer.append(line).append('\n')
                            }
                        }
                    }
                }
                val previous = File(timelineFile.parentFile, timelineFile.name + ".prune.previous")
                previous.delete()
                if (timelineFile.renameTo(previous)) {
                    if (temporary.renameTo(timelineFile)) {
                        previous.delete()
                    } else {
                        previous.renameTo(timelineFile)
                        error("Could not atomically replace the GPS timeline")
                    }
                } else {
                    // Internal app storage normally supports rename. This locked fallback
                    // is used only for unusual filesystems where rename is unavailable.
                    temporary.inputStream().use { input ->
                        FileOutputStream(timelineFile, false).use { output -> input.copyTo(output) }
                    }
                    temporary.delete()
                }
            }.onFailure { temporary.delete() }

            val dailyCutoff = System.currentTimeMillis() - DAILY_INTERNAL_RETENTION_MS
            dailyTimelineDirectory.listFiles { file -> file.isFile && file.extension.equals("csv", true) }
                .orEmpty()
                .filter { it.lastModified() < dailyCutoff }
                .forEach { oldFile ->
                    if (oldFile.delete()) dailySyncedSignatures.remove(oldFile.absolutePath)
                }
        }
    }

    /**
     * Saves one phone-coordinate backup GPX for the matching MP4 directly in that MP4's
     * local-date folder, e.g. selected-folder/16-08-2026/260816_102735266_backup.gpx.
     */
    private fun writePerVideoGpx(dateFolderName: String, requestedName: String, bytes: ByteArray): String {
        val treeText = prefs.getString(KEY_FOLDER, null) ?: error("Select a smartphone backup folder")
        val tree = Uri.parse(treeText)
        val hasGrant = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        require(hasGrant) { "Backup folder permission was lost; select the folder again" }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val parent = getOrCreateDirectory(root, dateFolderName)

        val token = UUID.randomUUID().toString().take(8)
        val tempName = "_tmp_${token}_${requestedName}"
        val tempUri = createDocument(parent, tempName)
        try {
            writeBytes(tempUri, bytes)
            verifyDocument(tempUri, bytes)

            val existing = findChild(parent, requestedName)
            var oldBackupUri: Uri? = null
            var finalName = requestedName
            if (existing != null) {
                val oldName = "_previous_${token}_${requestedName}"
                oldBackupUri = runCatching { DocumentsContract.renameDocument(contentResolver, existing, oldName) }.getOrNull()
                if (oldBackupUri == null) {
                    // If this SAF provider cannot rename the existing valid file, preserve it and
                    // create a collision-safe new backup instead of deleting data first.
                    finalName = nextAvailableName(parent, requestedName)
                }
            }

            try {
                val finalUri = finalizeTemporaryDocument(parent, tempUri, finalName, bytes)
                verifyDocument(finalUri, bytes)
                oldBackupUri?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
                return "$dateFolderName/$finalName"
            } catch (error: Throwable) {
                if (oldBackupUri != null) {
                    runCatching { DocumentsContract.renameDocument(contentResolver, oldBackupUri, requestedName) }
                }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, tempUri) }
            throw error
        }
    }

    /**
     * Saves the complete quality-filtered smartphone track for one local calendar day directly
     * in the selected Backup folder, e.g. selected-folder/PHONE_GPX_BACKUP_16-08-2026.gpx.
     */
    private fun writeDailyPhoneGpx(dateFolderName: String, bytes: ByteArray): String {
        val treeText = prefs.getString(KEY_FOLDER, null) ?: error("Select a smartphone backup folder")
        val tree = Uri.parse(treeText)
        val hasGrant = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        require(hasGrant) { "Backup folder permission was lost; select the folder again" }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // Keep the per-video destination ready for the same local day even before the first MP4
        // finishes. The global daily GPX itself intentionally lives at the Backup root.
        getOrCreateDirectory(root, dateFolderName)
        val parent = root
        val requestedName = BackupGpxLayout.dailyGlobalFileName(dateFolderName)
        val token = UUID.randomUUID().toString().take(8)
        val tempUri = createDocument(parent, "_tmp_daily_${token}_$requestedName")
        try {
            writeBytes(tempUri, bytes)
            verifyDocument(tempUri, bytes)
            val existing = findChild(parent, requestedName)
            if (existing == null) {
                val finalUri = finalizeTemporaryDocument(parent, tempUri, requestedName, bytes)
                verifyDocument(finalUri, bytes)
                return requestedName
            }

            val previousName = "_previous_daily_${token}_$requestedName"
            val previous = runCatching {
                DocumentsContract.renameDocument(contentResolver, existing, previousName)
            }.getOrNull()
            if (previous != null) {
                try {
                    val finalUri = finalizeTemporaryDocument(parent, tempUri, requestedName, bytes)
                    verifyDocument(finalUri, bytes)
                    runCatching { DocumentsContract.deleteDocument(contentResolver, previous) }
                    return requestedName
                } catch (error: Throwable) {
                    runCatching { DocumentsContract.renameDocument(contentResolver, previous, requestedName) }
                    throw error
                }
            }

            // Some SAF providers cannot rename. The verified internal daily log remains the source
            // of truth, so an in-place rewrite is recoverable on the next periodic sync if needed.
            writeBytes(existing, bytes)
            verifyDocument(existing, bytes)
            runCatching { DocumentsContract.deleteDocument(contentResolver, tempUri) }
            return requestedName
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, tempUri) }
            throw error
        }
    }

    private fun getOrCreateDirectory(parent: Uri, displayName: String): Uri {
        val existing = findChildInfo(parent, displayName)
        if (existing != null) {
            require(existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                "Cannot create folder $displayName because a non-folder item already has that name"
            }
            return existing.uri
        }

        val created = runCatching {
            DocumentsContract.createDocument(
                contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                displayName
            )
        }.getOrNull() ?: error("Cannot create folder $displayName in the selected backup folder")

        val createdMimeType = queryMimeType(created)
        require(createdMimeType == null || createdMimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            "Storage provider did not create $displayName as a folder"
        }
        return created
    }

    private data class ChildInfo(val uri: Uri, val mimeType: String?)

    private fun findChildInfo(parent: Uri, displayName: String): ChildInfo? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return runCatching {
            contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (idIndex >= 0 && nameIndex >= 0 && cursor.getString(nameIndex) == displayName) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
                        val mimeType = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else null
                        return@use ChildInfo(uri, mimeType)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun queryMimeType(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()

    private fun finalizeTemporaryDocument(parent: Uri, tempUri: Uri, finalName: String, bytes: ByteArray): Uri {
        val renamed = runCatching { DocumentsContract.renameDocument(contentResolver, tempUri, finalName) }.getOrNull()
        if (renamed != null) return renamed

        val finalUri = createDocument(parent, finalName)
        try {
            writeBytes(finalUri, bytes)
            verifyDocument(finalUri, bytes)
            runCatching { DocumentsContract.deleteDocument(contentResolver, tempUri) }
            return finalUri
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, finalUri) }
            throw error
        }
    }

    private fun createDocument(parent: Uri, name: String): Uri =
        runCatching { DocumentsContract.createDocument(contentResolver, parent, "application/gpx+xml", name) }.getOrNull()
            ?: runCatching { DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", name) }.getOrNull()
            ?: error("Cannot create $name in the selected folder")

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: contentResolver.openOutputStream(uri, "w")
            ?: error("Cannot open output document for writing")
        output.use { it.write(bytes); it.flush() }
    }

    private fun verifyDocument(uri: Uri, expected: ByteArray) {
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expected)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val input = contentResolver.openInputStream(uri) ?: error("Cannot verify saved GPX")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                count += read
                digest.update(buffer, 0, read)
            }
        }
        require(count == expected.size.toLong()) { "Saved GPX verification failed: expected ${expected.size} bytes, read $count" }
        require(digest.digest().contentEquals(expectedDigest)) { "Saved GPX verification failed: content checksum mismatch" }
    }

    private fun findChild(parent: Uri, displayName: String): Uri? =
        findChildInfo(parent, displayName)?.takeUnless {
            it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }?.uri

    private fun nextAvailableName(parent: Uri, requestedName: String): String {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (index in 1..999) {
            val candidate = "$base ($index)$extension"
            if (findChild(parent, candidate) == null) return candidate
        }
        return "${base}_${System.currentTimeMillis()}$extension"
    }

    private fun getBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7_000
        connection.readTimeout = 12_000
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("Camera returned HTTP $code for GPX")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_GPX_DOWNLOAD_BYTES) { "Camera GPX exceeds the safety limit" }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private data class RetryState(val attempts: Int, val nextAttemptAt: Long, val lastError: String)

    private fun loadRetryStates(): MutableMap<String, RetryState> {
        val result = mutableMapOf<String, RetryState>()
        val root = runCatching { JSONObject(prefs.getString(KEY_RETRY_STATES, "{}").orEmpty()) }.getOrElse { JSONObject() }
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = root.optJSONObject(key) ?: continue
            result[key] = RetryState(
                attempts = value.optInt("attempts", 0),
                nextAttemptAt = value.optLong("nextAttemptAt", 0L),
                lastError = value.optString("lastError")
            )
        }
        return result
    }

    private fun saveRetryStates(states: Map<String, RetryState>) {
        val root = JSONObject()
        states.entries.toList().takeLast(MAX_RETRY_STATE_ENTRIES).forEach { (key, value) ->
            root.put(key, JSONObject().apply {
                put("attempts", value.attempts)
                put("nextAttemptAt", value.nextAttemptAt)
                put("lastError", value.lastError)
            })
        }
        prefs.edit().putString(KEY_RETRY_STATES, root.toString()).apply()
    }

    private fun registerRetryFailure(key: String, error: Throwable): RetryState {
        val states = loadRetryStates()
        val previous = states[key]
        val attempts = (previous?.attempts ?: 0) + 1
        val exponent = (attempts - 1).coerceIn(0, 8)
        val delay = (ITEM_RETRY_BASE_DELAY_MS shl exponent).coerceAtMost(ITEM_RETRY_MAX_DELAY_MS)
        val state = RetryState(attempts, System.currentTimeMillis() + delay, error.message ?: error.javaClass.simpleName)
        states[key] = state
        saveRetryStates(states)
        return state
    }

    private fun clearRetryState(key: String) {
        val states = loadRetryStates()
        if (states.remove(key) != null) saveRetryStates(states)
    }

    private fun itemKey(item: PendingGpxItem): String =
        (item.id.ifBlank {
            "${item.completedAt}|${item.gpxPath}|${item.gpxName}|${item.gpxSizeBytes}|${item.videoName}"
        }).lowercase(Locale.US)

    private fun processedSet(): LinkedHashSet<String> =
        LinkedHashSet(prefs.getStringSet(KEY_PROCESSED, emptySet()).orEmpty())

    private fun isProcessed(key: String): Boolean = processedSet().contains(key)

    private fun markProcessed(key: String) {
        val set = processedSet()
        set.remove(key)
        set.add(key)
        while (set.size > MAX_PROCESSED_ENTRIES) set.remove(set.first())
        prefs.edit().putStringSet(KEY_PROCESSED, set).apply()
    }

    private fun parseIso(value: String): Long? {
        val candidates = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in candidates) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value.trim())?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun setStatus(value: String) = prefs.edit().putString(KEY_STATUS, value).apply()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setContentTitle("Automatic GPX backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "Automatic GPX backup", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun iso(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(time))

    companion object {
        const val PREFS = "client_settings"
        const val KEY_ACTIVE_CAMERA_ADDRESS = "backup_server_address"
        const val KEY_FOLDER = "backup_folder_uri"
        const val KEY_STATUS = "backup_status"
        const val KEY_ENABLED = "backup_enabled"
        const val KEY_ACCURACY = "backup_accuracy"
        const val KEY_GPS_STATE = "backup_gps_state"
        const val KEY_GPS_FIX_COUNT = "backup_gps_fix_count"
        const val KEY_GPS_CONNECTED_SATELLITE_COUNT = "backup_gps_connected_satellite_count"
        const val KEY_GPS_AVAILABLE_SATELLITE_COUNT = "backup_gps_available_satellite_count"
        const val KEY_GPS_LAST_FIX_AT = "backup_gps_last_fix_at"
        const val KEY_GPS_PROVIDER = "backup_gps_provider"
        const val KEY_GPS_COORDINATES = "backup_gps_coordinates"
        const val KEY_GPS_PROVIDER_ENABLED = "backup_gps_provider_enabled"
        const val KEY_DAILY_GPX_LAST_PATH = "backup_daily_gpx_last_path"
        const val KEY_DAILY_GPX_LAST_SYNC_AT = "backup_daily_gpx_last_sync_at"
        const val KEY_DAILY_GPX_ERROR = "backup_daily_gpx_error"

        private const val KEY_PROCESSED = "backup_processed_camera_videos_v2"
        private const val KEY_RETRY_STATES = "backup_camera_item_retry_states_v1"
        private const val DATE_FOLDER_PATTERN = "dd-MM-yyyy"
        private const val CHANNEL = "backup_gps"
        private const val NOTIFICATION_ID = 44
        private const val MAX_PROCESSED_ENTRIES = 6_000
        private const val MAX_RETRY_STATE_ENTRIES = 500
        private const val TIMELINE_RETENTION_MS = 24L * 60L * 60L * 1_000L
        private const val DAILY_INTERNAL_RETENTION_MS = 14L * 24L * 60L * 60L * 1_000L
        private const val MEMORY_RETENTION_MS = 2L * 60L * 60L * 1_000L
        private const val MATCH_MARGIN_MS = 2L * 60L * 1_000L
        private const val MAX_MATCH_DISTANCE_MS = 30L * 1_000L
        private const val MAX_ACCEPTED_ACCURACY_METERS = 100f
        private const val NETWORK_SUPPRESSION_AFTER_GPS_MS = 15_000L
        private const val MAX_REASONABLE_SPEED_METERS_PER_SECOND = 120.0
        private const val ACCURACY_SCORE_MILLISECONDS_PER_METER = 50.0
        private const val MAX_GPX_DOWNLOAD_BYTES = 100L * 1024L * 1024L
        private const val CAMERA_FAILURE_BASE_DELAY_MS = 3_000L
        private const val CAMERA_FAILURE_MAX_DELAY_MS = 2L * 60L * 1_000L
        private const val ITEM_RETRY_BASE_DELAY_MS = 5_000L
        private const val ITEM_RETRY_MAX_DELAY_MS = 60L * 60L * 1_000L
        private val notificationCleanupExecutor = Executors.newSingleThreadExecutor()

        fun dismissNotification(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                notificationCleanupExecutor.execute {
                    runCatching {
                        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .cancel(NOTIFICATION_ID)
                    }
                }
            }
        }
    }
}
