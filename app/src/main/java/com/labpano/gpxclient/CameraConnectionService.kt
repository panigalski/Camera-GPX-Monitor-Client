package com.labpano.gpxclient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keeps the Pilot One dashboard connection alive independently of MainActivity.
 *
 * The service runs only after a successful manual Connect action. It keeps a foreground
 * notification visible and holds narrowly-scoped Wi-Fi/CPU locks. A dashboard connection
 * failure ends the camera session immediately: the client does not keep stale values or silently
 * retry after the Main App becomes unreachable. A new connection requires the user to press Connect.
 */
class CameraConnectionService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    // Notification delivery is intentionally isolated from the camera polling executor.
    // Some Android/OEM notification services can block Binder calls under load; that must
    // never stall the only thread responsible for keeping the Pilot One link alive.
    private val alertExecutor = Executors.newSingleThreadExecutor()
    private val client = DashboardClient()
    private var pollFuture: ScheduledFuture<*>? = null
    private var address: String = ""
    private var shuttingDown = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var temperaturePlayer: MediaPlayer? = null
    private var liveStatusSupported: Boolean? = null
    private var nextFullPollElapsed: Long = 0L
    @Volatile private var forceFullPoll: Boolean = false

    override fun onCreate() {
        super.onCreate()
        activeService = this
        runningInProcess = true
        createNotificationChannel()
        // A notification from a previous client process may still be visible in SystemUI.
        // Clear it on the notification worker so service startup never waits on NotificationManager.
        enqueueNotificationTask { cancelStorageAlertNotificationNow(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val requestedAddress = intent?.getStringExtra(EXTRA_ADDRESS)
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_ADDRESS, "").orEmpty()

        val wasRequested = prefs.getBoolean(KEY_REQUESTED, false)
        val explicitConnect = intent?.action == ACTION_CONNECT
        val requested = explicitConnect || wasRequested
        if (!requested || requestedAddress.isBlank()) {
            clearConnectionState("Disconnected")
            stopSelf()
            return START_NOT_STICKY
        }

        address = runCatching { client.normalizeAddress(requestedAddress) }.getOrElse {
            clearConnectionState("Invalid camera address")
            stopSelf()
            return START_NOT_STICKY
        }

        if (explicitConnect) {
            liveStatusSupported = null
            nextFullPollElapsed = SystemClock.elapsedRealtime() + SUCCESS_POLL_MS
            forceFullPoll = false
            if (!wasRequested) TemperatureAlertSettings.resetAlertState(this)
            // A manual Connect starts a new storage-alert session. Current camera history is
            // baselined on the first poll; only faults at/after this connection request may alert.
            val keySuffix = address.lowercase(Locale.US)
            prefs.edit()
                .remove("$KEY_STORAGE_ALERT_BASELINE_INITIALIZED:$keySuffix")
                .remove("$KEY_SEEN_STORAGE_ALERT_IDS:$keySuffix")
                .putLong(KEY_CONNECTION_STARTED_AT, System.currentTimeMillis())
                .apply()
            enqueueNotificationTask { cancelStorageAlertNotificationNow(this) }
        }

        if (!explicitConnect) {
            liveStatusSupported = null
            nextFullPollElapsed = 0L
            forceFullPoll = true
        }

        val initialState = if (explicitConnect) STATE_CONNECTED else STATE_CONNECTING
        val initialMessage = if (explicitConnect) "Connected in background" else "Connecting in background…"
        prefs.edit()
            .putBoolean(KEY_REQUESTED, true)
            .putString(KEY_ADDRESS, address)
            .putString(KEY_STATE, initialState)
            .putString(KEY_MESSAGE, initialMessage)
            .apply()

        // MainActivity starts this service only after a successful dashboard request. Do not
        // immediately perform the same large dashboard request a second time; the duplicate fetch
        // used to coincide with first-frame rendering and amplified connection-time lag.
        val foregroundText = if (explicitConnect) {
            "Connected to Pilot One • background link active"
        } else {
            "Connecting to Pilot One…"
        }
        startForeground(NOTIFICATION_ID, notification(foregroundText))
        acquireLocks()
        schedulePoll(if (explicitConnect) LIVE_POLL_MS else 0L)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeService === this) activeService = null
        runningInProcess = false
        shuttingDown = true
        pollFuture?.cancel(true)
        pollFuture = null
        executor.shutdownNow()
        alertExecutor.shutdownNow()
        releaseLocks()
        temperaturePlayer?.release()
        temperaturePlayer = null

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REQUESTED, false)) {
            // This service is deliberately non-sticky. If Android actually destroys it, do not
            // leave a ghost requested/reconnecting state that can be mistaken for a live link.
            prefs.edit()
                .putBoolean(KEY_REQUESTED, false)
                .putString(KEY_STATE, STATE_DISCONNECTED)
                .putString(KEY_MESSAGE, "Background connection stopped • press Connect to reconnect")
                .putString(KEY_LAST_ERROR, "")
                .putLong(KEY_LAST_SUCCESS_AT, 0L)
                .remove(KEY_CONNECTION_STARTED_AT)
                .apply()
            ClientSessionState.clear()
            publishSessionUpdated()
        }
        super.onDestroy()
    }

    private fun schedulePoll(delayMs: Long) {
        if (shuttingDown || address.isBlank()) return
        pollFuture?.cancel(false)
        pollFuture = runCatching {
            executor.schedule({ pollOnce() }, delayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun pollOnce() {
        if (shuttingDown) return
        refreshWakeLock()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_REQUESTED, false)) {
            stopSelf()
            return
        }

        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            val shouldFetchFull = forceFullPoll || liveStatusSupported == false ||
                nextFullPollElapsed <= 0L || nowElapsed >= nextFullPollElapsed

            if (shouldFetchFull) {
                val dashboard = client.fetch(address)
                monitorTemperature(dashboard.battery.temperatureC)
                val newStorageAlerts = monitorStorageWriteAlerts(
                    dashboard.storageWriteAlerts,
                    dashboard.storageWriteAlertsSupported
                )
                ClientSessionState.update(address, dashboard)
                ClientSessionState.addSessionStorageWriteAlerts(newStorageAlerts)
                forceFullPoll = false
                nextFullPollElapsed = SystemClock.elapsedRealtime() + SUCCESS_POLL_MS
                markConnected(prefs)
                val temperatureText = dashboard.battery.temperatureC?.let {
                    String.format(Locale.US, " • %.1f °C", it)
                }.orEmpty()
                updateNotification("Connected to Pilot One$temperatureText • background link active")
                publishSessionUpdated()
                schedulePoll(if (liveStatusSupported == false) SUCCESS_POLL_MS else LIVE_POLL_MS)
            } else {
                val live = client.fetchLiveStatus(address)
                if (live == null) {
                    // Main App <= 0.5.26: fall back cleanly to the legacy 3-second full dashboard.
                    liveStatusSupported = false
                    nextFullPollElapsed = 0L
                    schedulePoll(0L)
                    return
                }
                liveStatusSupported = true
                val changed = ClientSessionState.mergeLive(address, live)
                if (changed) publishSessionUpdated()
                val untilFull = (nextFullPollElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                schedulePoll(minOf(LIVE_POLL_MS, untilFull))
            }
        } catch (error: Throwable) {
            val message = friendlyError(error)
            handleConnectionLoss(message)
        }
    }

    private fun markConnected(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putString(KEY_STATE, STATE_CONNECTED)
            .putString(KEY_MESSAGE, "Connected in background")
            .putString(KEY_LAST_ERROR, "")
            .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
            .apply()
    }

    private fun publishSessionUpdated() {
        sendBroadcast(Intent(ACTION_SESSION_UPDATED).setPackage(packageName))
    }

    private fun handleConnectionLoss(reason: String) {
        if (shuttingDown) return
        shuttingDown = true
        pollFuture?.cancel(false)
        pollFuture = null

        val message = "Connection lost • press Connect to reconnect"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_REQUESTED, false)
            .putString(KEY_STATE, STATE_DISCONNECTED)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_LAST_ERROR, reason)
            .putLong(KEY_LAST_SUCCESS_AT, 0L)
            .remove(KEY_CONNECTION_STARTED_AT)
            .apply()

        // Remove every dashboard-derived value immediately and notify a visible Activity now.
        ClientSessionState.clear()
        publishSessionUpdated()
        detachBackupCamera(this)
        temperaturePlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
            if (temperaturePlayer === player) temperaturePlayer = null
        }
        dismissStorageAlertNotification(this)
        runCatching { stopForeground(true) }
        stopSelf()
    }

    private fun monitorTemperature(temperatureC: Double?) {
        // Do not consume/disarm the temperature alert while sounds are muted. If the user
        // unmutes while the temperature is still above the threshold, the next poll may warn.
        if (AppSoundSettings.isMuted(this)) return
        if (!TemperatureAlertSettings.shouldPlayWarning(this, temperatureC)) return
        playTemperatureWarning()
    }

    private fun playTemperatureWarning() {
        if (AppSoundSettings.isMuted(this)) return
        if (temperaturePlayer?.isPlaying == true) return
        temperaturePlayer?.release()
        val player = MediaPlayer.create(this, R.raw.battery_temp_combined) ?: return
        temperaturePlayer = player
        player.setOnCompletionListener { completed ->
            completed.release()
            if (temperaturePlayer === completed) temperaturePlayer = null
        }
        player.setOnErrorListener { failed, _, _ ->
            failed.release()
            if (temperaturePlayer === failed) temperaturePlayer = null
            true
        }
        player.start()
    }

    private fun monitorStorageWriteAlerts(alerts: List<StorageWriteAlert>, supported: Boolean): List<StorageWriteAlert> {
        if (!supported) return emptyList()
        val validAlerts = alerts
            .asSequence()
            .filter { it.id.isNotBlank() }
            .sortedBy { it.occurredAt }
            .toList()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val keySuffix = address.lowercase(Locale.US)
        val baselineKey = "$KEY_STORAGE_ALERT_BASELINE_INITIALIZED:$keySuffix"
        val seenKey = "$KEY_SEEN_STORAGE_ALERT_IDS:$keySuffix"
        val initialized = prefs.getBoolean(baselineKey, false)
        val seen = prefs.getStringSet(seenKey, emptySet()).orEmpty().toSet()
        val now = System.currentTimeMillis()
        val connectionStartedAt = prefs.getLong(KEY_CONNECTION_STARTED_AT, now)

        val newAlerts = StorageAlertPolicy.newAlerts(
            alerts = validAlerts,
            initialized = initialized,
            seenIds = seen,
            connectionStartedAt = connectionStartedAt,
            now = now,
            clockSkewMs = STORAGE_ALERT_CLOCK_SKEW_MS
        )

        // Mark the dashboard history as seen BEFORE handing anything to Android's notification
        // service. If an OEM notification Binder call blocks, throws, or the process is killed,
        // the same alert will not be replayed indefinitely on the next poll/restart.
        val retainedIds = StorageAlertPolicy.retainedIds(validAlerts, MAX_SEEN_STORAGE_ALERT_IDS)
        prefs.edit()
            .putBoolean(baselineKey, true)
            .putStringSet(seenKey, retainedIds)
            .apply()

        if (newAlerts.isNotEmpty()) {
            // One dashboard response can contain several failures. Use one summary notification
            // instead of creating a notification per item, which avoids SystemUI/notification
            // flooding during a storage fault or repeated transfer recovery.
            val latest = newAlerts.maxByOrNull { it.occurredAt }
            if (latest != null) enqueueStorageWriteAlertNotification(latest, newAlerts.size)
        }
        return newAlerts
    }

    private fun enqueueStorageWriteAlertNotification(alert: StorageWriteAlert, newAlertCount: Int) {
        enqueueNotificationTask { showStorageWriteAlertNotification(alert, newAlertCount) }
    }

    private fun enqueueNotificationTask(action: () -> Unit) {
        if (shuttingDown) return
        runCatching {
            alertExecutor.execute {
                if (shuttingDown) return@execute
                runCatching(action)
            }
        }
    }

    private fun showStorageWriteAlertNotification(alert: StorageWriteAlert, newAlertCount: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val storageLabel = when (alert.storageType.uppercase(Locale.US)) {
            "INTERNAL" -> "Internal storage"
            "EXTERNAL" -> "External storage"
            else -> "Output storage"
        }
        val videoName = boundedNotificationText(alert.videoName.ifBlank { "MP4 file" }, MAX_NOTIFICATION_FIELD_CHARS)
        val message = boundedNotificationText(alert.message, MAX_NOTIFICATION_MESSAGE_CHARS)
        val destination = boundedNotificationText(alert.destination, MAX_NOTIFICATION_FIELD_CHARS)
        val detail = buildString {
            append(storageLabel).append(" MP4 write problem")
            if (newAlertCount > 1) append(" • ").append(newAlertCount).append(" new problems")
            append(" • ").append(videoName)
            if (message.isNotBlank()) append("\n").append(message)
            if (destination.isNotBlank()) append("\nDestination: ").append(destination)
        }.take(MAX_NOTIFICATION_DETAIL_CHARS)

        val openIntent = PendingIntent.getActivity(
            this,
            STORAGE_ALERT_PENDING_INTENT_REQUEST,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muted = AppSoundSettings.isMuted(this)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(
                this,
                if (muted) STORAGE_ALERT_SILENT_CHANNEL_ID else STORAGE_ALERT_CHANNEL_ID
            )
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).apply {
                if (muted) {
                    setSound(null)
                } else {
                    setDefaults(Notification.DEFAULT_SOUND)
                }
            }
        }
        val title = if (newAlertCount > 1) {
            "Pilot One MP4 write problems ($newAlertCount)"
        } else {
            "Pilot One MP4 write problem"
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_client)
            .setContentTitle(title)
            .setContentText("$storageLabel • $videoName")
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()

        // Always replace the previous storage-fault notification. This keeps SystemUI bounded
        // even if several independent MP4 failures happen in a short period.
        manager.notify(STORAGE_ALERT_NOTIFICATION_ID, notification)
    }

    private fun boundedNotificationText(value: String, maxChars: Int): String {
        val normalized = value.replace('\u0000', ' ').trim()
        return if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 1) + "…"
    }

    private fun friendlyError(error: Throwable): String {
        val names = generateSequence(error as Throwable?) { it.cause }
            .map { it.javaClass.simpleName }
            .toSet()
        return when {
            "SocketTimeoutException" in names -> "camera connection timed out"
            "ConnectException" in names || "NoRouteToHostException" in names -> "camera is not reachable"
            "UnknownHostException" in names -> "camera address could not be resolved"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "camera connection is temporarily unavailable"
        }
    }


    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock?.isHeld != true) {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = manager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:camera-link")
                ?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire() }
                }
        }
        if (wakeLock == null) {
            val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = manager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:camera-link")
                ?.apply { setReferenceCounted(false) }
        }
        refreshWakeLock()
    }

    private fun refreshWakeLock() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Pilot One background connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the client connected to the Pilot One camera while the screen is off"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    STORAGE_ALERT_CHANNEL_ID,
                    "Pilot One MP4 write problems",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when the Camera App cannot write or verify an MP4 on internal or external storage"
                    setShowBadge(true)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    STORAGE_ALERT_SILENT_CHANNEL_ID,
                    "Pilot One MP4 write problems (muted)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Silent MP4 write notifications used when App Sounds is muted"
                    setShowBadge(true)
                    setSound(null, null)
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_client)
            .setContentTitle("Labpano GPX Client")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        // Updating the foreground notification is not part of the networking critical path.
        // Keep every NotificationManager Binder call off the polling executor as well.
        enqueueNotificationTask {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification(text.take(MAX_FOREGROUND_NOTIFICATION_CHARS)))
        }
    }

    private fun clearConnectionState(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_REQUESTED, false)
            .putString(KEY_STATE, STATE_DISCONNECTED)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_LAST_ERROR, "")
            .putLong(KEY_LAST_SUCCESS_AT, 0L)
            .remove(KEY_CONNECTION_STARTED_AT)
            .apply()
        ClientSessionState.clear()
    }

    data class Snapshot(
        val requested: Boolean,
        val address: String,
        val state: String,
        val message: String,
        val lastError: String,
        val lastSuccessAt: Long
    )

    companion object {
        const val PREFS = "camera_connection_service"
        const val KEY_REQUESTED = "connection_requested"
        const val KEY_ADDRESS = "connection_address"
        const val KEY_STATE = "connection_state"
        const val KEY_MESSAGE = "connection_message"
        const val KEY_LAST_ERROR = "connection_last_error"
        const val KEY_LAST_SUCCESS_AT = "connection_last_success_at"
        private const val KEY_CONNECTION_STARTED_AT = "connection_started_at"
        private const val KEY_STORAGE_ALERT_BASELINE_INITIALIZED = "storage_alert_baseline_initialized_v1"
        private const val KEY_SEEN_STORAGE_ALERT_IDS = "seen_storage_alert_ids_v1"

        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_RECONNECTING = "RECONNECTING"
        const val ACTION_SESSION_UPDATED = "com.labpano.gpxclient.action.CAMERA_SESSION_UPDATED"

        private const val ACTION_CONNECT = "com.labpano.gpxclient.action.CONNECT_CAMERA"
        private const val EXTRA_ADDRESS = "camera_address"
        private const val CHANNEL_ID = "camera_background_connection"
        private const val STORAGE_ALERT_CHANNEL_ID = "camera_storage_write_alerts"
        private const val STORAGE_ALERT_SILENT_CHANNEL_ID = "camera_storage_write_alerts_silent"
        private const val NOTIFICATION_ID = 2202
        private const val STORAGE_ALERT_NOTIFICATION_ID = 2600
        private const val STORAGE_ALERT_PENDING_INTENT_REQUEST = 2600
        private const val LEGACY_STORAGE_ALERT_NOTIFICATION_BASE = 2600
        private const val LEGACY_STORAGE_ALERT_NOTIFICATION_MAX = 2600 + 0x0FFF
        private const val SUCCESS_POLL_MS = 3_000L
        private const val LIVE_POLL_MS = 250L
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 1_000L
        private const val STORAGE_ALERT_CLOCK_SKEW_MS = 5_000L
        private const val MAX_SEEN_STORAGE_ALERT_IDS = 100
        private const val MAX_NOTIFICATION_FIELD_CHARS = 240
        private const val MAX_NOTIFICATION_MESSAGE_CHARS = 480
        private const val MAX_NOTIFICATION_DETAIL_CHARS = 1_200
        private const val MAX_FOREGROUND_NOTIFICATION_CHARS = 180
        @Volatile private var runningInProcess = false
        @Volatile private var activeService: CameraConnectionService? = null
        private val notificationCleanupExecutor = Executors.newSingleThreadExecutor()

        fun isRunningInProcess(): Boolean = runningInProcess

        fun onAppSoundSettingChanged(context: Context, muted: Boolean) {
            val appContext = context.applicationContext
            if (!muted) {
                // Unmute is an explicit request to resume active warnings. Rearm the temperature
                // latch/cooldown so a device that is STILL above the warning threshold can warn
                // again instead of remaining suppressed by an alert that played before muting.
                TemperatureAlertSettings.resetAlertState(appContext)
            }

            val service = activeService ?: return
            runCatching {
                service.executor.execute {
                    if (service.shuttingDown) return@execute
                    if (muted) {
                        service.temperaturePlayer?.let { player ->
                            runCatching { if (player.isPlaying) player.stop() }
                            runCatching { player.release() }
                            if (service.temperaturePlayer === player) service.temperaturePlayer = null
                        }
                    } else if (!AppSoundSettings.isMuted(service)) {
                        // Use a fresh full-dashboard temperature rather than replaying cached data.
                        service.forceFullPoll = true
                        service.schedulePoll(0L)
                    }
                }
            }
        }


        fun connect(context: Context, address: String) {
            val intent = Intent(context, CameraConnectionService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ADDRESS, address)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun dismissStorageAlertNotification(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                notificationCleanupExecutor.execute {
                    cancelStorageAlertNotificationNow(appContext)
                }
            }
        }

        fun clearStalePersistedConnection(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REQUESTED, false)
                .putString(KEY_STATE, STATE_DISCONNECTED)
                .putString(KEY_MESSAGE, "Disconnected")
                .putString(KEY_LAST_ERROR, "")
                .putLong(KEY_LAST_SUCCESS_AT, 0L)
                .remove(KEY_CONNECTION_STARTED_AT)
                .apply()
            ClientSessionState.clear()
        }

        fun dismissConnectionNotification(context: Context) {
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

        private fun cancelStorageAlertNotificationNow(context: Context) {
            runCatching {
                val manager = context.applicationContext
                    .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // Client 1.9.3 generated a different notification ID for every storage fault:
                // 2600 + (alert.id.hashCode() and 0x0FFF). Cancelling only ID 2600 therefore
                // leaves old notifications visible forever after an upgrade. minSdk is 24, so
                // activeNotifications is available and lets us remove only this app's storage
                // fault notifications without touching the live foreground-connection notice.
                manager.activeNotifications.forEach { active ->
                    val storageChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        active.notification.channelId == STORAGE_ALERT_CHANNEL_ID ||
                            active.notification.channelId == STORAGE_ALERT_SILENT_CHANNEL_ID
                    } else {
                        false
                    }
                    val legacyStorageId = active.id in LEGACY_STORAGE_ALERT_NOTIFICATION_BASE..LEGACY_STORAGE_ALERT_NOTIFICATION_MAX
                    if (storageChannel || legacyStorageId || active.id == STORAGE_ALERT_NOTIFICATION_ID) {
                        manager.cancel(active.tag, active.id)
                    }
                }
                // Also cancel the current fixed ID in case it is not returned by an OEM's active
                // notification list during a transition.
                manager.cancel(STORAGE_ALERT_NOTIFICATION_ID)
            }
        }

        private fun detachBackupCamera(context: Context) {
            // The smartphone GPS backup is a contingency recorder and must survive camera loss.
            // Only remove the optional camera endpoint; BackupGpsService keeps collecting phone GPS.
            context.applicationContext
                .getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(BackupGpsService.KEY_ACTIVE_CAMERA_ADDRESS)
                .apply()
        }

        fun disconnect(context: Context) {
            dismissStorageAlertNotification(context)
            detachBackupCamera(context)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REQUESTED, false)
                .putString(KEY_STATE, STATE_DISCONNECTED)
                .putString(KEY_MESSAGE, "Disconnected")
                .putString(KEY_LAST_ERROR, "")
                .putLong(KEY_LAST_SUCCESS_AT, 0L)
                .remove(KEY_CONNECTION_STARTED_AT)
                .apply()
            ClientSessionState.clear()
            context.stopService(Intent(context, CameraConnectionService::class.java))
        }

        fun snapshot(context: Context): Snapshot {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Snapshot(
                requested = prefs.getBoolean(KEY_REQUESTED, false),
                address = prefs.getString(KEY_ADDRESS, "").orEmpty(),
                state = prefs.getString(KEY_STATE, STATE_DISCONNECTED).orEmpty(),
                message = prefs.getString(KEY_MESSAGE, "Disconnected").orEmpty(),
                lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
                lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L)
            )
        }
    }
}
