package com.labpano.gpxclient

import android.app.Activity
import android.media.MediaPlayer
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val gpxTransferWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = DashboardClient()
    @Volatile private var currentServerAddress: String? = null
    @Volatile private var destroyed = false
    @Volatile private var started = false
    @Volatile private var connectionGeneration = 0L
    private var cameraSessionReceiverRegistered = false
    private val cameraSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CameraConnectionService.ACTION_SESSION_UPDATED) {
                // Render live recording/transfer changes immediately instead of waiting for the
                // generic one-second UI housekeeping timer.
                syncConnectionFromBackgroundService()
            }
        }
    }
    private val gpsUiUpdater = object : Runnable {
        override fun run() {
            if (destroyed || !started) return
            if (::gpsReceiverStatus.isInitialized) renderGpsReceiverStatus()
            syncConnectionFromBackgroundService()
            syncBackupUiFromPreferences()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var address: AutoCompleteTextView
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private var isConnected = false
    private var connectionState = ConnectionState.DISCONNECTED
    private var backupValidationInProgress = false
    private lateinit var transfers: LinearLayout
    private lateinit var cameraRecordingDetails: TextView
    private lateinit var monitoringDetails: TextView
    private lateinit var recordingTypeDetails: TextView
    private lateinit var fragmentStorageDetails: TextView
    private lateinit var outputFolderDetails: TextView
    private lateinit var currentEventDetails: TextView
    private lateinit var storageDetails: TextView
    private lateinit var storageProgress: ProgressBar
    private lateinit var batteryDetails: TextView
    private lateinit var batteryProgress: ProgressBar
    private lateinit var temperatureDetails: TextView
    private lateinit var externalStorageCard: View
    private lateinit var externalStorageDetails: TextView
    private lateinit var externalStorageProgress: ProgressBar
    private lateinit var errorButton: Button
    private lateinit var failedButton: Button
    private lateinit var goodButton: Button
    private lateinit var backupFolder: TextView
    private lateinit var backupStatus: TextView
    private lateinit var dailyPhoneGpxStatus: TextView
    private lateinit var gpsReceiverStatus: GpsStatusView
    private lateinit var backupToggle: Button
    private lateinit var sendGpxButton: Button
    private lateinit var sendGpxStatus: TextView
    private var backupInactiveBackgroundTint: ColorStateList? = null
    @Volatile private var sendingGpxFiles = false
    private var backupDiscoveryStarted = false
    private var gpxSendStatusOverride: String? = null
    private var lastGpxSendPendingCount = -1
    private lateinit var screenStatus: TextView
    private lateinit var screenToggle: Button
    private lateinit var soundToggle: Button

    private val soundQueue = ArrayDeque<Int>()
    private var activePlayer: MediaPlayer? = null
    private var reportBaselineInitialized = false
    private var previousErrorEntries: Set<String> = emptySet()
    private var previousFailedEntries: Set<String> = emptySet()
    private var previousGoodEntries: Set<String> = emptySet()
    private var previousErrorFingerprint: String? = null
    private var previousFailedFingerprint: String? = null
    private var previousGoodFingerprint: String? = null
    private var lastRenderedTransfers: List<TransferEntry>? = null
    private var lastBackgroundDashboardRenderedRevision = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initializeRuntimeState()
            setContentView(buildUi())
            updateKeepScreenOn()
            restoreConnectionSession()
        } catch (error: Throwable) {
            writeStartupCrash(error)
            showStartupFailure(error)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerCameraSessionReceiver()
        mainHandler.removeCallbacks(gpsUiUpdater)
        mainHandler.post(gpsUiUpdater)
        if (!backupDiscoveryStarted) {
            backupDiscoveryStarted = true
            runCatching {
                gpxTransferWorker.execute {
                    BackupGpxSendQueue.discoverExisting(this)
                    postUi { updateSendGpxButton() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderGpsReceiverStatus()
        refreshTemperatureCard()
        syncConnectionFromBackgroundService()
        updateBackupButton()
        updateKeepScreenOn()
        updateAppSoundsUi()
    }

    override fun onStop() {
        unregisterCameraSessionReceiver()
        started = false
        mainHandler.removeCallbacks(gpsUiUpdater)
        super.onStop()
    }

    private fun updateKeepScreenOn() {
        val enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, false)

        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (::screenStatus.isInitialized) {
            screenStatus.text = if (enabled) {
                "The screen will stay on while the client app is visible."
            } else {
                "The screen may turn off according to the device display timeout."
            }
        }
        if (::screenToggle.isInitialized) {
            screenToggle.text = if (enabled) "SCREEN ALWAYS ON: ON" else "SCREEN ALWAYS ON: OFF"
            screenToggle.setTextColor(if (enabled) Color.WHITE else Color.rgb(32, 33, 36))
            screenToggle.backgroundTintList = ColorStateList.valueOf(
                if (enabled) Color.rgb(76, 175, 80) else Color.rgb(224, 224, 224)
            )
        }
    }

    private fun toggleKeepScreenOn() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val enabled = !prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        updateKeepScreenOn()
    }

    private fun updateAppSoundsUi() {
        val muted = AppSoundSettings.isMuted(this)
        if (::soundToggle.isInitialized) {
            soundToggle.text = if (muted) "APP SOUNDS: MUTED" else "APP SOUNDS: ON"
            soundToggle.setTextColor(Color.WHITE)
            soundToggle.backgroundTintList = ColorStateList.valueOf(
                if (muted) Color.rgb(117, 117, 117) else Color.rgb(76, 175, 80)
            )
        }
    }

    private fun toggleAppSounds() {
        val muted = !AppSoundSettings.isMuted(this)
        AppSoundSettings.setMuted(this, muted)
        if (muted) {
            soundQueue.clear()
            activePlayer?.let { player ->
                runCatching { if (player.isPlaying) player.stop() }
                runCatching { player.release() }
            }
            activePlayer = null
        }
        CameraConnectionService.onAppSoundSettingChanged(this, muted)
        updateAppSoundsUi()
    }

    override fun onDestroy() {
        destroyed = true
        connectionGeneration++
        mainHandler.removeCallbacks(gpsUiUpdater)
        worker.shutdownNow()
        gpxTransferWorker.shutdownNow()
        soundQueue.clear()
        activePlayer?.release()
        activePlayer = null
        super.onDestroy()
    }

    private fun postUi(action: () -> Unit) {
        mainHandler.post {
            if (!destroyed && !isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) {
                action()
            }
        }
    }

    private fun restoreConnectionSession() {
        val background = CameraConnectionService.snapshot(this)
        val serviceRunning = CameraConnectionService.isRunningInProcess()
        val server = background.address.takeIf {
            background.requested && serviceRunning && it.isNotBlank()
        } ?: ClientSessionState.connectedAddress?.takeIf { serviceRunning }
            ?: return

        // Never start/restart the foreground service from Activity restoration. A camera link is
        // created only by the user's Connect action (or Android restarting an already-running
        // foreground service). Persisted preferences alone are not proof of a live connection.
        currentServerAddress = server
        isConnected = true
        connectButton.text = "Disconnect"
        connectButton.isEnabled = true
        address.setText(server, false)

        if (background.state != CameraConnectionService.STATE_CONNECTED &&
            background.state != CameraConnectionService.STATE_CONNECTING
        ) {
            disconnect(userInitiated = false, message = "Connection lost")
            return
        }
        connectionState = if (background.state == CameraConnectionService.STATE_CONNECTED) {
            ConnectionState.CONNECTED
        } else {
            ConnectionState.CONNECTING
        }
        if (connectionState == ConnectionState.CONNECTED) updateBackupCameraAddressForCurrentConnection()
        ClientSessionState.lastDashboard?.let {
            lastBackgroundDashboardRenderedRevision = ClientSessionState.lastDashboardRevision
            render(it)
        }
        if (background.state == CameraConnectionService.STATE_CONNECTING) {
            setConnectionStatus(background.message.ifBlank { "Connecting…" }, Color.rgb(255, 193, 7))
        }
    }

    private fun syncConnectionFromBackgroundService() {
        if (!::connectButton.isInitialized) return
        val background = CameraConnectionService.snapshot(this)
        val sessionWasActive = isConnected ||
            connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.RECONNECTING

        // A background connection failure is terminal by design. The service writes DISCONNECTED
        // before stopping; observe that state even after the service process object has disappeared.
        if (!background.requested ||
            background.address.isBlank() ||
            background.state == CameraConnectionService.STATE_DISCONNECTED
        ) {
            if (sessionWasActive) {
                disconnect(userInitiated = false, message = "Connection lost")
            }
            return
        }
        if (!CameraConnectionService.isRunningInProcess()) return

        currentServerAddress = background.address
        isConnected = true
        connectButton.text = "Disconnect"
        connectButton.isEnabled = true

        when (background.state) {
            CameraConnectionService.STATE_CONNECTED -> {
                connectionState = ConnectionState.CONNECTED
                updateBackupCameraAddressForCurrentConnection()
                val revision = ClientSessionState.lastDashboardRevision
                val dashboard = ClientSessionState.lastDashboard
                if (dashboard != null && revision > lastBackgroundDashboardRenderedRevision) {
                    lastBackgroundDashboardRenderedRevision = revision
                    render(dashboard)
                } else if (dashboard == null) {
                    setConnectionStatus("Connected in background", Color.rgb(76, 175, 80))
                }
            }
            CameraConnectionService.STATE_CONNECTING -> {
                connectionState = ConnectionState.CONNECTING
                setConnectionStatus(background.message.ifBlank { "Connecting…" }, Color.rgb(255, 193, 7))
            }
            CameraConnectionService.STATE_RECONNECTING -> {
                // Compatibility with stale state left by older Client versions. New versions no
                // longer reconnect automatically after a failed Main App poll.
                disconnect(userInitiated = false, message = "Connection lost")
            }
        }
    }

    private fun registerCameraSessionReceiver() {
        if (cameraSessionReceiverRegistered) return
        val filter = IntentFilter(CameraConnectionService.ACTION_SESSION_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cameraSessionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(cameraSessionReceiver, filter)
        }
        cameraSessionReceiverRegistered = true
    }

    private fun unregisterCameraSessionReceiver() {
        if (!cameraSessionReceiverRegistered) return
        runCatching { unregisterReceiver(cameraSessionReceiver) }
        cameraSessionReceiverRegistered = false
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 246, 248))
            // Keep startup focus on the page rather than the IP input. The address field
            // receives focus normally when the user taps it, which is when Android should
            // show the software keyboard.
            isFocusableInTouchMode = true
            requestFocus()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.rgb(69, 39, 160))
        }
        header.addView(TextView(this).apply {
            text = "Labpano GPX Client"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, 1)
        })

        val connectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        address = AutoCompleteTextView(this).apply {
            hint = "http://192.168.1.25:1100"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            threshold = 0
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ADDRESS, ""), false)
            setOnClickListener { showDropDown() }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showDropDown() }
        }
        refreshAddressDropdown()
        connectionRow.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                if (isConnected) disconnect(userInitiated = true) else connect()
            }
        }
        connectionRow.addView(connectButton, LinearLayout.LayoutParams(dp(112), dp(48)))
        header.addView(connectionRow)

        status = TextView(this).apply {
            text = "Not Connected"
            textSize = 13f
            setTextColor(Color.rgb(244, 67, 54))
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(status)
        content.addView(header)

        val cameraRecordingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            cameraRecordingDetails = TextView(this@MainActivity).apply {
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(cameraRecordingDetails)
            renderCameraRecordingStatus(null, emptyList())
        }
        content.addView(cameraRecordingCard, cardParams(dp(10)))

        val mainMonitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = "Main Camera App Monitor"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(32, 33, 36))
            })
            monitoringDetails = TextView(this@MainActivity).apply {
                textSize = 15f
                setPadding(0, dp(6), 0, 0)
            }
            addView(monitoringDetails)
            recordingTypeDetails = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(0, dp(5), 0, 0)
            }
            addView(recordingTypeDetails)
            fragmentStorageDetails = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(0, dp(3), 0, 0)
            }
            addView(fragmentStorageDetails)
            outputFolderDetails = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(0, dp(5), 0, dp(4))
                // Folder paths can be much wider than the monitor card. Keep the full path
                // visible by allowing normal multi-line wrapping instead of clipping/ellipsizing.
                isSingleLine = false
                maxLines = Int.MAX_VALUE
                ellipsize = null
                setHorizontallyScrolling(false)
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                setTextIsSelectable(true)
            }
            addView(outputFolderDetails)
            transfers = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(transfers)
            currentEventDetails = TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, 0)
                visibility = View.GONE
            }
            addView(currentEventDetails)
            renderMonitoringStatus(null)
            renderFragmentStorage(null)
            renderOutputFolder(null)
            renderCurrentEvent(null)
        }
        content.addView(mainMonitorCard, cardParams(dp(10)))

        content.addView(Button(this).apply {
            text = "PILOT ONE BLUETOOTH / GPS DETAILS"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PilotDiagnosticsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(12), dp(8), dp(12), 0)
        })

        val screenCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = "Screen Always On"
                textSize = 18f
                setTypeface(typeface, 1)
                setTextColor(Color.rgb(32, 33, 36))
            })
            screenStatus = TextView(this@MainActivity).apply {
                text = "The screen may turn off according to the device display timeout."
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, dp(8))
            }
            addView(screenStatus)
            screenToggle = Button(this@MainActivity).apply {
                text = "SCREEN ALWAYS ON: OFF"
                setTextColor(Color.rgb(32, 33, 36))
                setOnClickListener { toggleKeepScreenOn() }
            }
            addView(screenToggle)
        }
        content.addView(screenCard, cardParams(dp(10)))

        val soundCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = "App Sounds"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(32, 33, 36))
            })
            soundToggle = Button(this@MainActivity).apply {
                setOnClickListener { toggleAppSounds() }
            }
            addView(soundToggle)
            updateAppSoundsUi()
        }
        content.addView(soundCard, cardParams(dp(10)))

        content.addView(infoCard(
            title = "Pilot One Internal Storage",
            detailsView = TextView(this).also {
                storageDetails = it
                it.text = "Connect to view used, free and total space."
                it.textSize = 14f
                it.setTextColor(Color.DKGRAY)
                it.setPadding(0, dp(4), 0, dp(6))
            },
            progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
                storageProgress = it
                it.max = 100
            }
        ), cardParams(dp(10)))

        externalStorageCard = infoCard(
            title = "Pilot One External Storage",
            detailsView = TextView(this).also {
                externalStorageDetails = it
                it.text = "External storage is not connected."
                it.textSize = 14f
                it.setTextColor(Color.DKGRAY)
                it.setPadding(0, dp(4), 0, dp(6))
            },
            progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
                externalStorageProgress = it
                it.max = 100
            }
        ).apply { visibility = View.GONE }
        content.addView(externalStorageCard, cardParams(dp(8)))


        content.addView(infoCard(
            title = "Pilot One Battery",
            detailsView = TextView(this).also {
                batteryDetails = it
                it.text = "Connect to view the camera battery status."
                it.textSize = 14f
                it.setTextColor(Color.DKGRAY)
                it.setPadding(0, dp(4), 0, dp(6))
            },
            progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
                batteryProgress = it
                it.max = 100
            }
        ), cardParams(dp(8)))

        val temperatureCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = "Pilot One Device Temperature"
                textSize = 18f
                setTypeface(typeface, 1)
                setTextColor(Color.rgb(32, 33, 36))
            })
            temperatureDetails = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(0, dp(4), 0, dp(8))
            }
            addView(temperatureDetails)
            renderTemperatureStatus(null)
            addView(Button(this@MainActivity).apply {
                text = "TEMPERATURE ALERT SETTINGS"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, TemperatureSettingsActivity::class.java))
                }
            })
        }
        content.addView(temperatureCard, cardParams(dp(8)))

        val backupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = "Automatic Smartphone GPS Backup"
                textSize = 18f
                setTypeface(typeface, 1)
                setTextColor(Color.rgb(32, 33, 36))
            })
            backupFolder = TextView(this@MainActivity).apply {
                text = backupFolderLabel()
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(5), 0, dp(5))
            }
            addView(backupFolder)
            addView(Button(this@MainActivity).apply {
                text = "SELECT BACKUP FOLDER"
                setOnClickListener { selectBackupFolder() }
            })
            backupStatus = TextView(this@MainActivity).apply {
                text = "Automatic backup is off"
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(6), 0, dp(4))
            }
            addView(backupStatus)
            dailyPhoneGpxStatus = TextView(this@MainActivity).apply {
                text = "Global phone GPX: waiting for GPS backup to start"
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, dp(4))
            }
            addView(dailyPhoneGpxStatus)
            gpsReceiverStatus = GpsStatusView(this@MainActivity).apply {
                setPadding(0, dp(8), 0, dp(8))
            }
            addView(gpsReceiverStatus, LinearLayout.LayoutParams(-1, dp(154)))
            backupToggle = Button(this@MainActivity).apply {
                setTextColor(Color.rgb(32, 33, 36))
                setOnClickListener { toggleBackup() }
            }
            backupInactiveBackgroundTint = backupToggle.backgroundTintList
                ?: ColorStateList.valueOf(Color.rgb(224, 224, 224))
            updateBackupButton()
            addView(backupToggle)
            sendGpxStatus = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(sendGpxStatus)
            sendGpxButton = Button(this@MainActivity).apply {
                text = "Send GPX Files"
                setOnClickListener { sendPendingGpxFiles() }
            }
            addView(sendGpxButton)
            updateSendGpxButton()
        }
        content.addView(backupCard, cardParams(dp(8)))

        content.addView(TextView(this).apply {
            text = "Reports"
            textSize = 18f
            setTypeface(typeface, 1)
            setTextColor(Color.rgb(32, 33, 36))
            setPadding(dp(16), dp(10), dp(16), dp(4))
        })

        val reportButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        errorButton = reportButton("ERRORS", ReportType.ERROR)
        failedButton = reportButton("FAILED", ReportType.FAILED)
        goodButton = reportButton("GOOD", ReportType.GOOD)
        reportButtons.addView(errorButton, LinearLayout.LayoutParams(-1, dp(52)))
        reportButtons.addView(failedButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        reportButtons.addView(goodButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        content.addView(reportButtons)

        content.addView(TextView(this).apply {
            text = "Client version ${BuildConfig.VERSION_NAME}"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(2), 0, dp(6))
        })
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(content, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun infoCard(title: String, detailsView: TextView, progressView: ProgressBar): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTypeface(typeface, 1)
                setTextColor(Color.rgb(32, 33, 36))
            })
            addView(detailsView)
            addView(progressView)
        }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(12), top, dp(12), 0)
    }

    private fun reportButton(label: String, type: ReportType): Button = Button(this).apply {
        text = "$label (0)"
        isEnabled = false
        setOnClickListener { openReport(type) }
    }

    private fun openReport(type: ReportType) {
        val server = currentServerAddress ?: address.text.toString().trim()
        if (server.isBlank()) {
            Toast.makeText(this, "Connect to the main app first", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, ReportActivity::class.java).apply {
            putExtra(ReportActivity.EXTRA_SERVER_ADDRESS, server)
            putExtra(ReportActivity.EXTRA_REPORT_TYPE, type.name)
        })
    }

    private fun connect() {
        val value = address.text.toString().trim()
        if (value.isBlank()) {
            Toast.makeText(this, "Enter the address shown by the main app", Toast.LENGTH_LONG).show()
            return
        }

        connectionGeneration++
        val generation = connectionGeneration
        currentServerAddress = null
        isConnected = false
        connectionState = ConnectionState.CONNECTING
        ClientSessionState.clear()
        setConnectionStatus("Connecting…", Color.rgb(255, 193, 7))
        connectButton.isEnabled = false

        runCatching {
            worker.execute {
                try {
                    val dashboard = client.fetchForConnection(value)
                    val successfulAddress = client.normalizeAddress(value)
                    postUi {
                        if (generation != connectionGeneration) return@postUi
                        val serviceStarted = runCatching {
                            CameraConnectionService.connect(this, successfulAddress)
                        }.isSuccess
                        if (!serviceStarted) {
                            CameraConnectionService.disconnect(this)
                            currentServerAddress = null
                            isConnected = false
                            connectionState = ConnectionState.DISCONNECTED
                            ClientSessionState.clear()
                            connectButton.text = "Connect"
                            connectButton.isEnabled = true
                            setConnectionStatus("Not Connected", Color.rgb(244, 67, 54))
                            clearCameraData()
                            Toast.makeText(
                                this,
                                "The background camera connection could not be started.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@postUi
                        }
                        rememberSuccessfulAddress(successfulAddress)
                        currentServerAddress = successfulAddress
                        isConnected = true
                        connectionState = ConnectionState.CONNECTED
                        updateBackupCameraAddressForCurrentConnection()
                        connectButton.text = "Disconnect"
                        connectButton.isEnabled = true
                        ClientSessionState.beginConnection(successfulAddress, dashboard)
                        lastBackgroundDashboardRenderedRevision = ClientSessionState.lastDashboardRevision
                        render(dashboard)
                        requestNotificationPermissionIfUseful()
                    }
                } catch (error: Throwable) {
                    postUi {
                        if (generation != connectionGeneration) return@postUi
                        currentServerAddress = null
                        isConnected = false
                        connectionState = ConnectionState.DISCONNECTED
                        ClientSessionState.clear()
                        connectButton.text = "Connect"
                        connectButton.isEnabled = true
                        setConnectionStatus("Not Connected", Color.rgb(244, 67, 54))
                        clearCameraData()
                        Toast.makeText(
                            this,
                            "Unable to connect. Check the camera IP address and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.onFailure {
            connectionState = ConnectionState.DISCONNECTED
            connectButton.isEnabled = true
            setConnectionStatus("Not Connected", Color.rgb(244, 67, 54))
        }
    }

    private fun disconnect(userInitiated: Boolean, message: String = "Disconnected") {
        connectionGeneration++
        currentServerAddress = null
        isConnected = false
        connectionState = ConnectionState.DISCONNECTED
        CameraConnectionService.disconnect(this)
        ClientSessionState.clear()
        lastBackgroundDashboardRenderedRevision = ClientSessionState.lastDashboardRevision
        resetReportSoundBaseline()
        // Do not allow a queued report/temperature-derived sound from the now-dead camera session
        // to continue after every camera-side value has been cleared.
        soundQueue.clear()
        activePlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        activePlayer = null
        if (::connectButton.isInitialized) {
            connectButton.text = "Connect"
            connectButton.isEnabled = true
        }
        setConnectionStatus(message, Color.rgb(244, 67, 54))
        // Camera connection state is independent from smartphone GPS backup. Disconnecting or
        // losing the Main App must never turn off the contingency phone GPS timeline.
        clearCameraData()
        if (userInitiated) Toast.makeText(this, "Disconnected from Pilot One", Toast.LENGTH_SHORT).show()
    }

    private fun setConnectionStatus(text: String, color: Int) {
        if (::status.isInitialized) {
            status.text = text
            status.setTextColor(color)
        }
    }

    private fun clearCameraData() {
        if (::cameraRecordingDetails.isInitialized) renderCameraRecordingStatus(null, emptyList())
        if (::monitoringDetails.isInitialized) renderMonitoringStatus(null)
        if (::fragmentStorageDetails.isInitialized || ::recordingTypeDetails.isInitialized) renderFragmentStorage(null)
        if (::outputFolderDetails.isInitialized) renderOutputFolder(null)
        if (::currentEventDetails.isInitialized) renderCurrentEvent(null)
        if (::storageDetails.isInitialized) {
            storageDetails.text = "Connect to view used, free and total space."
            storageDetails.tag = null
            storageDetails.setTextColor(Color.DKGRAY)
        }
        if (::storageProgress.isInitialized) storageProgress.progress = 0
        if (::batteryDetails.isInitialized) {
            batteryDetails.text = "Connect to view the camera battery status."
            batteryDetails.tag = null
            batteryDetails.setTextColor(Color.DKGRAY)
        }
        if (::batteryProgress.isInitialized) batteryProgress.progress = 0
        if (::temperatureDetails.isInitialized) renderTemperatureStatus(null)
        if (::externalStorageCard.isInitialized) externalStorageCard.visibility = View.GONE
        if (::externalStorageDetails.isInitialized) {
            externalStorageDetails.tag = null
            externalStorageDetails.setTextColor(Color.DKGRAY)
        }
        if (::transfers.isInitialized) transfers.removeAllViews()
        lastRenderedTransfers = null
        if (::errorButton.isInitialized) resetReportButton(errorButton, "ERRORS")
        if (::failedButton.isInitialized) resetReportButton(failedButton, "FAILED")
        if (::goodButton.isInitialized) resetReportButton(goodButton, "GOOD")
    }

    private fun resetReportButton(button: Button, label: String) {
        button.text = "$label (0)"
        button.isEnabled = false
        button.setBackgroundColor(Color.rgb(224, 224, 224))
        button.setTextColor(Color.rgb(60, 60, 60))
    }

    private fun rememberSuccessfulAddress(value: String) {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val history = buildList {
            add(value)
            loadAddressHistory().filterTo(this) { it != value }
        }.take(MAX_ADDRESS_HISTORY)
        preferences.edit()
            .putString(KEY_ADDRESS, value)
            .putString(KEY_ADDRESS_HISTORY_JSON, JSONArray(history).toString())
            .remove(KEY_ADDRESS_HISTORY_LEGACY)
            .apply()
        if (address.text.toString() != value) {
            address.setText(value, false)
            address.setSelection(value.length)
        }
        refreshAddressDropdown()
        currentServerAddress = value
    }

    private fun loadAddressHistory(): List<String> {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = preferences.getString(KEY_ADDRESS, "").orEmpty()
        val fromJson = runCatching {
            val array = JSONArray(preferences.getString(KEY_ADDRESS_HISTORY_JSON, "[]") ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        val legacy = runCatching {
            preferences.getStringSet(KEY_ADDRESS_HISTORY_LEGACY, emptySet()).orEmpty().toList()
        }.getOrDefault(emptyList())
        return (listOf(current) + fromJson + legacy)
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_ADDRESS_HISTORY)
    }

    private fun refreshAddressDropdown() {
        if (!::address.isInitialized) return
        address.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, loadAddressHistory())
        )
    }

    private fun render(dashboard: Dashboard) {
        connectionState = ConnectionState.CONNECTED
        processDashboardAlerts(dashboard)
        setConnectionStatus(
            "Connected • Main app ${dashboard.appVersion}",
            Color.rgb(76, 175, 80)
        )
        renderCameraRecordingStatus(dashboard.cameraRecording, dashboard.transfers)
        renderMonitoringStatus(dashboard.monitoring)
        renderFragmentStorage(dashboard.fragmentStorage)
        renderOutputFolder(dashboard)
        renderCurrentEvent(dashboard)
        val storage = dashboard.internalStorage
        storageProgress.progress = storage.usedPercent.coerceIn(0, 100)
        storageDetails.text = if (storage.totalBytes > 0L) {
            String.format(Locale.US, "%d%% used • %s used / %s total • %s free", storage.usedPercent, formatBytes(storage.usedBytes), formatBytes(storage.totalBytes), formatBytes(storage.freeBytes))
        } else storage.error.ifBlank { "Storage information unavailable" }
        storageDetails.tag = storageDetails.text.toString()
        storageDetails.setTextColor(Color.DKGRAY)

        val external = dashboard.externalStorage
        if (external != null && external.totalBytes > 0L && external.path.isNotBlank()) {
            externalStorageCard.visibility = View.VISIBLE
            externalStorageProgress.progress = external.usedPercent.coerceIn(0, 100)
            externalStorageDetails.text = String.format(
                Locale.US,
                "%d%% used • %s used / %s total • %s free • %s",
                external.usedPercent,
                formatBytes(external.usedBytes),
                formatBytes(external.totalBytes),
                formatBytes(external.freeBytes),
                external.path
            )
            externalStorageDetails.tag = externalStorageDetails.text.toString()
            externalStorageDetails.setTextColor(Color.DKGRAY)
        } else {
            externalStorageCard.visibility = View.GONE
            externalStorageProgress.progress = 0
        }
        val battery = dashboard.battery
        batteryProgress.progress = battery.percent.coerceIn(0, 100)
        batteryDetails.text = if (battery.available && battery.percent >= 0) {
            buildString {
                append(battery.percent).append("% • ").append(battery.status)
                if (battery.charging) append(" via ").append(battery.powerSource)
                if (battery.health.isNotBlank() && battery.health != "Unknown") append(" • Health: ").append(battery.health)
            }
        } else battery.error.ifBlank { "Battery information unavailable. Update the Pilot One main app." }
        batteryDetails.tag = batteryDetails.text.toString()
        batteryDetails.setTextColor(Color.DKGRAY)
        renderTemperatureStatus(battery)

        updateReportButton(errorButton, "ERRORS", dashboard.errors.size, Color.rgb(198, 40, 40))
        updateReportButton(failedButton, "FAILED", dashboard.failed.size, Color.rgb(121, 85, 72))
        updateReportButton(goodButton, "GOOD", dashboard.good.size, Color.rgb(46, 125, 50))

        val backupPrefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        backupStatus.text = BackupStatusDisplayPolicy.display(
            enabled = backupPrefs.getBoolean(BackupGpsService.KEY_ENABLED, false),
            persistedStatus = backupPrefs.getString(BackupGpsService.KEY_STATUS, null)
        )
        renderGpsReceiverStatus()
        updateBackupButton()

        // Temperature/storage values can change every few seconds even when transfer state is
        // identical. Rebuilding an entire hierarchy of transfer views on every temperature poll
        // causes avoidable UI churn on older phones, so update this section only when it changes.
        if (dashboard.transfers != lastRenderedTransfers) {
            transfers.removeAllViews()
            dashboard.transfers.forEach { transfers.addView(transferView(it)) }
            lastRenderedTransfers = dashboard.transfers
        }
    }


    private fun renderCurrentEvent(dashboard: Dashboard?) {
        if (!::currentEventDetails.isInitialized) return
        // Active transfers render their own single activity + filename line directly below
        // the progress bar. Keep this standalone line only for processing stages that do
        // not yet have a transfer/progress entry (for example GPX generation).
        val message = if (dashboard?.transfers?.isNotEmpty() == true) {
            ""
        } else {
            dashboard?.let(TransferEventFormatter::format).orEmpty()
        }
        currentEventDetails.text = message
        currentEventDetails.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderCameraRecordingStatus(
        status: CameraRecordingStatus?,
        activeTransfers: List<TransferEntry>
    ) {
        if (!::cameraRecordingDetails.isInitialized) return
        val showRecording = status?.let {
            RecordingDisplayPolicy.shouldShowRecording(it, activeTransfers)
        } ?: false
        val value = when {
            status == null || !status.available -> "Unknown"
            showRecording -> "Recording"
            else -> "Ready"
        }
        val valueColor = when {
            status == null || !status.available -> Color.DKGRAY
            showRecording -> Color.rgb(198, 40, 40)
            else -> Color.rgb(25, 118, 210)
        }
        cameraRecordingDetails.text = labelValueLine(
            label = "Pilot One Recording Status:",
            value = value,
            valueColor = valueColor,
            boldValue = true
        )
    }

    private fun renderMonitoringStatus(status: MonitoringStatus?) {
        if (!::monitoringDetails.isInitialized) return
        val value = when {
            status == null || !status.available -> "--"
            status.serviceRunning -> "ON"
            else -> "OFF"
        }
        val color = when {
            status == null || !status.available -> Color.DKGRAY
            status.serviceRunning -> Color.rgb(46, 125, 50)
            else -> Color.rgb(198, 40, 40)
        }
        monitoringDetails.text = labelValueLine("Monitoring", value, color, boldValue = true)
    }

    private fun renderFragmentStorage(status: FragmentStorageStatus?) {
        val display = FragmentStorageDisplayPolicy.describe(status)

        if (::recordingTypeDetails.isInitialized) {
            val typeColor = when {
                status == null || display.recordingType == "Unknown" -> Color.DKGRAY
                else -> Color.rgb(25, 118, 210)
            }
            recordingTypeDetails.text = labelValueLine(
                "Recording Type:",
                display.recordingType,
                typeColor,
                boldValue = status != null && display.recordingType != "Unknown"
            )
        }

        if (!::fragmentStorageDetails.isInitialized) return
        val valueColor = when {
            status == null || !status.available -> Color.DKGRAY
            display.recordingType == "Unknown" -> Color.rgb(25, 118, 210)
            status.enabled -> Color.rgb(25, 118, 210)
            else -> Color.DKGRAY
        }
        fragmentStorageDetails.text = labelValueLine(
            "Fragment Storage:",
            display.fragmentStorage,
            valueColor,
            boldValue = status?.available == true
        )
    }

    private fun renderOutputFolder(dashboard: Dashboard?) {
        if (!::outputFolderDetails.isInitialized) return
        val output = dashboard?.outputFolder.orEmpty()
            .ifBlank { dashboard?.reportHealth?.destination.orEmpty() }
            .ifBlank { "--" }
        outputFolderDetails.text = outputFolderText(output)
        // Preserve the exact raw value for accessibility/copy semantics; visual wrapping does not
        // alter the path itself.
        outputFolderDetails.contentDescription = "Output Folder: $output"
    }

    private fun outputFolderText(value: String): SpannableStringBuilder {
        val label = "Output Folder:"
        return SpannableStringBuilder(label).apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append('\n')
            append(value)
        }
    }

    private fun labelValueLine(label: String, value: String, valueColor: Int, boldValue: Boolean): SpannableStringBuilder {
        val builder = SpannableStringBuilder(label).append(' ').append(value)
        builder.setSpan(
            ForegroundColorSpan(Color.BLACK),
            0,
            label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val start = label.length + 1
        builder.setSpan(
            ForegroundColorSpan(valueColor),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (boldValue) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    private fun processDashboardAlerts(dashboard: Dashboard) {
        // Do not turn a transient report-read failure into false "new report" alarms when the
        // next successful poll restores the same historical entries.
        if (dashboard.reportHealth.supported &&
            (!dashboard.reportHealth.available || !dashboard.reportHealth.ioHealthy)
        ) return

        val errorFingerprint = reportFingerprint(dashboard.errors)
        val failedFingerprint = reportFingerprint(dashboard.failed)
        val goodFingerprint = reportFingerprint(dashboard.good)

        if (!reportBaselineInitialized) {
            previousErrorEntries = dashboard.errors.map(::reportKey).toSet()
            previousFailedEntries = dashboard.failed.map(::reportKey).toSet()
            previousGoodEntries = dashboard.good.map(::reportKey).toSet()
            previousErrorFingerprint = errorFingerprint
            previousFailedFingerprint = failedFingerprint
            previousGoodFingerprint = goodFingerprint
            reportBaselineInitialized = true
            return
        }

        if (errorFingerprint != previousErrorFingerprint) {
            val current = dashboard.errors.map(::reportKey).toSet()
            if ((current - previousErrorEntries).isNotEmpty()) enqueueSound(R.raw.error_combined)
            previousErrorEntries = current
            previousErrorFingerprint = errorFingerprint
        }
        if (failedFingerprint != previousFailedFingerprint) {
            val current = dashboard.failed.map(::reportKey).toSet()
            if ((current - previousFailedEntries).isNotEmpty()) enqueueSound(R.raw.failed_combined)
            previousFailedEntries = current
            previousFailedFingerprint = failedFingerprint
        }
        if (goodFingerprint != previousGoodFingerprint) {
            val current = dashboard.good.map(::reportKey).toSet()
            if ((current - previousGoodEntries).isNotEmpty()) enqueueSound(R.raw.good_combined)
            previousGoodEntries = current
            previousGoodFingerprint = goodFingerprint
        }
    }

    private fun reportFingerprint(entries: List<ReportEntry>): String {
        if (entries.isEmpty()) return "0"
        // DashboardClient orders reports newest first. Size + newest entry detects additions and
        // deletions without rebuilding 500-entry sets every time only the temperature changes.
        return entries.size.toString() + "\u0000" + reportKey(entries.first())
    }

    private fun renderTemperatureStatus(battery: BatteryStatus?) {
        if (!::temperatureDetails.isInitialized) return
        val threshold = TemperatureAlertSettings.thresholdC(this)
        val returnTemperature = TemperatureAlertPolicy.rearmTemperatureC(threshold)
        val temperature = battery?.temperatureC
        val currentValue = temperature?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--"
        val warningValue = String.format(Locale.US, "%.1f °C", threshold)
        val returnValue = String.format(Locale.US, "%.1f °C", returnTemperature)

        val builder = SpannableStringBuilder()
        fun appendTemperatureLine(label: String, value: String, valueColor: Int) {
            val lineStart = builder.length
            builder.append(label).append(' ').append(value)
            builder.setSpan(
                ForegroundColorSpan(Color.BLACK),
                lineStart,
                lineStart + label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val valueStart = lineStart + label.length + 1
            builder.setSpan(
                ForegroundColorSpan(valueColor),
                valueStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        appendTemperatureLine("Current Temperature:", currentValue, Color.rgb(46, 125, 50))
        builder.append('\n')
        appendTemperatureLine("Warning Temperature:", warningValue, Color.rgb(198, 40, 40))
        builder.append('\n')
        appendTemperatureLine("Return Temperature:", returnValue, Color.rgb(25, 118, 210))

        temperatureDetails.text = builder
        temperatureDetails.tag = builder.toString()
    }

    private fun refreshTemperatureCard() {
        if (!::temperatureDetails.isInitialized) return
        renderTemperatureStatus(ClientSessionState.lastDashboard?.battery)
    }

    private fun reportKey(entry: ReportEntry): String =
        entry.timestamp + "\u0000" + entry.path + "\u0000" + entry.message

    private fun resetReportSoundBaseline() {
        reportBaselineInitialized = false
        previousErrorEntries = emptySet()
        previousFailedEntries = emptySet()
        previousGoodEntries = emptySet()
        previousErrorFingerprint = null
        previousFailedFingerprint = null
        previousGoodFingerprint = null
    }

    private fun enqueueSound(resourceId: Int) {
        if (AppSoundSettings.isMuted(this)) return
        soundQueue.addLast(resourceId)
        playNextSound()
    }

    private fun playNextSound() {
        if (AppSoundSettings.isMuted(this)) {
            soundQueue.clear()
            return
        }
        if (activePlayer != null || soundQueue.isEmpty()) return
        val resourceId = soundQueue.removeFirst()
        val player = MediaPlayer.create(this, resourceId) ?: run {
            playNextSound()
            return
        }
        activePlayer = player
        player.setOnCompletionListener { completed ->
            completed.release()
            activePlayer = null
            playNextSound()
        }
        player.setOnErrorListener { failed, _, _ ->
            failed.release()
            activePlayer = null
            playNextSound()
            true
        }
        player.start()
    }

    private fun transferView(item: TransferEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(4))
        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = item.percent.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Color.rgb(123, 31, 162))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(224, 224, 224))
        })
        addView(TextView(this@MainActivity).apply {
            text = TransferEventFormatter.formatTransfer(item)
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, 0)
        })
    }



    private fun renderGpsReceiverStatus() {
        if (!::gpsReceiverStatus.isInitialized) return
        val prefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        val backupEnabled = prefs.getBoolean(BackupGpsService.KEY_ENABLED, false)
        if (!backupEnabled) {
            gpsReceiverStatus.update(
                0,
                0,
                Color.rgb(117, 117, 117),
                "GPS collection is off"
            )
            return
        }

        val state = prefs.getString(BackupGpsService.KEY_GPS_STATE, "Waiting for GPS receiver data").orEmpty()
        val connectedSatellites = prefs.getInt(BackupGpsService.KEY_GPS_CONNECTED_SATELLITE_COUNT, 0)
        val availableSatellites = prefs.getInt(BackupGpsService.KEY_GPS_AVAILABLE_SATELLITE_COUNT, 0)
        val lastFixAt = prefs.getLong(BackupGpsService.KEY_GPS_LAST_FIX_AT, 0L)
        val accuracy = prefs.getFloat(BackupGpsService.KEY_ACCURACY, -1f)
        val provider = prefs.getString(BackupGpsService.KEY_GPS_PROVIDER, "").orEmpty()
        val providerEnabled = prefs.getBoolean(BackupGpsService.KEY_GPS_PROVIDER_ENABLED, false)
        val ageMs = if (lastFixAt > 0L) (System.currentTimeMillis() - lastFixAt).coerceAtLeast(0L) else Long.MAX_VALUE
        val ageText = when {
            lastFixAt <= 0L -> "No GPS fix yet"
            ageMs < 2_000L -> "Last fix: just now"
            ageMs < 60_000L -> "Last fix: ${ageMs / 1000L} seconds ago"
            else -> "Last fix: ${ageMs / 60_000L} minute(s) ago"
        }
        val fillColor = when {
            !providerEnabled -> Color.rgb(198, 40, 40)
            availableSatellites <= 0 -> Color.rgb(245, 124, 0)
            lastFixAt <= 0L -> Color.rgb(245, 124, 0)
            ageMs > 15_000L -> Color.rgb(121, 85, 72)
            else -> Color.rgb(46, 125, 50)
        }
        val details = buildString {
            append(state)
            append(" • ").append(ageText)
            if (provider.isNotBlank()) append(" • ").append(provider)
            if (accuracy >= 0f) append(String.format(Locale.US, " • ±%.1f m", accuracy))
        }
        gpsReceiverStatus.update(connectedSatellites, availableSatellites, fillColor, details)
    }

    private fun updateReportButton(button: Button, label: String, count: Int, activeColor: Int) {
        button.text = "$label ($count)"
        button.isEnabled = true
        if (count > 0) {
            button.setBackgroundColor(activeColor)
            button.setTextColor(Color.WHITE)
        } else {
            button.setBackgroundColor(Color.rgb(224, 224, 224))
            button.setTextColor(Color.rgb(60, 60, 60))
        }
    }

    private fun selectBackupFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE).edit()
                .putString(BackupGpsService.KEY_FOLDER, uri.toString()).apply()
            backupFolder.text = backupFolderLabel()
            runCatching {
                gpxTransferWorker.execute {
                    BackupGpxSendQueue.discoverExisting(this)
                    postUi { updateSendGpxButton() }
                }
            }
        }
    }

    private fun toggleBackup() {
        val prefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(BackupGpsService.KEY_ENABLED, false)
        if (enabled) {
            stopAutomaticBackup("Automatic backup stopped")
            return
        }
        val folder = prefs.getString(BackupGpsService.KEY_FOLDER, null)
        if (folder.isNullOrBlank()) {
            Toast.makeText(this, "Select a smartphone backup folder first", Toast.LENGTH_LONG).show()
            return
        }
        val folderUri = runCatching { Uri.parse(folder) }.getOrNull()
        val hasWriteGrant = folderUri != null && contentResolver.persistedUriPermissions.any {
            it.uri == folderUri && it.isWritePermission
        }
        if (!hasWriteGrant) {
            Toast.makeText(this, "Backup folder permission was lost. Select the folder again.", Toast.LENGTH_LONG).show()
            return
        }
        // Smartphone GPS is a contingency source and must not depend on the camera link.
        // If a live camera connection exists, attach its address so the service can also build
        // per-video backup GPX files. Otherwise start phone GPS collection with no camera address.
        updateBackupCameraAddressForCurrentConnection(force = true)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
        } else {
            validateBackupFolderAndStart()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val locationGranted = permissions.indices.any { index ->
                permissions[index] == Manifest.permission.ACCESS_FINE_LOCATION &&
                    grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            }
            if (locationGranted) {
                validateBackupFolderAndStart()
            } else {
                Toast.makeText(this, "Location permission is required for GPX backup", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validateBackupFolderAndStart() {
        if (backupValidationInProgress) return
        backupValidationInProgress = true
        backupStatus.text = "Checking backup folder access…"
        updateBackupButton()
        runCatching {
            worker.execute {
                try {
                    verifyBackupFolderWritable()
                    postUi {
                        backupValidationInProgress = false
                        startBackupService()
                        requestNotificationPermissionIfUseful()
                    }
                } catch (error: Throwable) {
                    postUi {
                        backupValidationInProgress = false
                        getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE).edit()
                            .putBoolean(BackupGpsService.KEY_ENABLED, false)
                            .putString(BackupGpsService.KEY_STATUS, "Backup folder check failed")
                            .apply()
                        backupStatus.text = "Backup folder check failed"
                        updateBackupButton()
                        Toast.makeText(
                            this,
                            "Backup folder is not fully accessible: ${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.onFailure {
            backupValidationInProgress = false
            backupStatus.text = "Backup folder check could not start"
            updateBackupButton()
        }
    }

    private fun verifyBackupFolderWritable() {
        val prefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        val treeText = prefs.getString(BackupGpsService.KEY_FOLDER, null)
            ?: error("Select a smartphone backup folder")
        val tree = Uri.parse(treeText)
        val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        require(grant != null && grant.isReadPermission && grant.isWritePermission) {
            "Read/write permission was lost; select the folder again"
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val name = "_gpx_client_access_test_${UUID.randomUUID().toString().take(8)}.tmp"
        val document = DocumentsContract.createDocument(contentResolver, parent, "text/plain", name)
            ?: error("Cannot create a test file")
        try {
            val expected = "Labpano GPX Client folder test".toByteArray(Charsets.UTF_8)
            val output = contentResolver.openOutputStream(document, "wt")
                ?: contentResolver.openOutputStream(document, "w")
                ?: error("Cannot write a test file")
            output.use { it.write(expected); it.flush() }
            val actual = contentResolver.openInputStream(document)?.use { it.readBytes() }
                ?: error("Cannot read the test file")
            require(actual.contentEquals(expected)) { "Test-file verification failed" }
        } finally {
            val deleted = runCatching {
                DocumentsContract.deleteDocument(contentResolver, document)
            }.getOrDefault(false)
            require(deleted) { "The test file could not be deleted" }
        }
    }

    private fun requestNotificationPermissionIfUseful() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun startBackupService() {
        val prefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        updateBackupCameraAddressForCurrentConnection(force = true)
        val intent = Intent(this, BackupGpsService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            prefs.edit()
                .putBoolean(BackupGpsService.KEY_ENABLED, true)
                .putString(BackupGpsService.KEY_STATUS, "Automatic backup starting…")
                .apply()
            backupStatus.text = "Automatic backup starting…"
        } catch (error: Throwable) {
            prefs.edit()
                .putBoolean(BackupGpsService.KEY_ENABLED, false)
                .putString(BackupGpsService.KEY_STATUS, "Automatic backup failed to start: ${error.message ?: error.javaClass.simpleName}")
                .apply()
            backupStatus.text = "Automatic backup failed to start"
            Toast.makeText(this, "Could not start automatic backup: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
        updateBackupButton()
        updateKeepScreenOn()
    }

    private fun syncBackupUiFromPreferences() {
        if (!::backupStatus.isInitialized) return
        if (backupValidationInProgress) {
            backupStatus.text = "Checking backup folder access…"
            updateBackupButton()
            return
        }
        val prefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(BackupGpsService.KEY_ENABLED, false)
        backupStatus.text = BackupStatusDisplayPolicy.display(
            enabled = enabled,
            persistedStatus = prefs.getString(BackupGpsService.KEY_STATUS, null)
        )
        if (::dailyPhoneGpxStatus.isInitialized) {
            val dailyError = prefs.getString(BackupGpsService.KEY_DAILY_GPX_ERROR, "").orEmpty()
            val dailyPath = prefs.getString(BackupGpsService.KEY_DAILY_GPX_LAST_PATH, "").orEmpty()
            dailyPhoneGpxStatus.text = when {
                dailyError.isNotBlank() -> "Global phone GPX: $dailyError"
                dailyPath.isNotBlank() -> "Global phone GPX: $dailyPath"
                enabled -> "Global phone GPX: collecting fixes; first save follows shortly"
                else -> "Global phone GPX: starts with automatic backup"
            }
            dailyPhoneGpxStatus.setTextColor(if (dailyError.isNotBlank()) Color.rgb(198, 40, 40) else Color.DKGRAY)
        }
        updateBackupButton()
        updateSendGpxButton()
    }

    private fun updateSendGpxButton() {
        if (!::sendGpxButton.isInitialized || !::sendGpxStatus.isInitialized) return
        val pending = BackupGpxSendQueue.pendingCount(this)
        if (lastGpxSendPendingCount >= 0 && pending != lastGpxSendPendingCount) {
            gpxSendStatusOverride = null
        }
        lastGpxSendPendingCount = pending
        val connected = isConnected && connectionState == ConnectionState.CONNECTED && !currentServerAddress.isNullOrBlank()
        sendGpxButton.text = "Send GPX Files"
        sendGpxButton.isEnabled = !sendingGpxFiles && pending > 0 && connected
        sendGpxButton.backgroundTintList = ColorStateList.valueOf(
            when {
                sendingGpxFiles -> Color.rgb(158, 158, 158)
                pending > 0 && connected -> Color.rgb(76, 175, 80)
                else -> Color.rgb(224, 224, 224)
            }
        )
        sendGpxButton.setTextColor(if (sendGpxButton.isEnabled) Color.WHITE else Color.rgb(96, 96, 96))
        if (!sendingGpxFiles) {
            sendGpxStatus.text = gpxSendStatusOverride ?: when {
                pending == 0 -> "GPX files to send: none"
                !connected -> "GPX files waiting to send: $pending • connect to the Main App"
                else -> "GPX files waiting to send: $pending"
            }
        }
    }

    private fun sendPendingGpxFiles() {
        if (sendingGpxFiles) return
        val server = currentServerAddress?.takeIf { isConnected && connectionState == ConnectionState.CONNECTED }
        if (server.isNullOrBlank()) {
            Toast.makeText(this, "Connect to the Main App first", Toast.LENGTH_LONG).show()
            updateSendGpxButton()
            return
        }
        val pending = BackupGpxSendQueue.pending(this)
        if (pending.isEmpty()) {
            updateSendGpxButton()
            return
        }
        sendingGpxFiles = true
        gpxSendStatusOverride = null
        sendGpxStatus.text = "Sending 0/${pending.size} GPX files…"
        updateSendGpxButton()

        runCatching {
            gpxTransferWorker.execute {
                var sent = 0
                try {
                    // Legacy 1.10.28/1.10.29 queue entries did not persist GOOD/FAILED status.
                    // Resolve them against Main App's durable queue once per manual send batch so
                    // older phone backups are still placed beside the correct camera recording.
                    val cameraQueue = runCatching { client.fetchPendingGpx(client.normalizeAddress(server)) }
                        .getOrDefault(emptyList())
                    pending.forEach { entry ->
                        val status = entry.status.ifBlank {
                            resolveBackupCameraStatus(entry.fileName, cameraQueue)
                                ?: error("Cannot determine GOOD/FAILED/ERROR destination for ${entry.fileName}")
                        }
                        val bytes = readBackupGpxBytes(Uri.parse(entry.documentUri))
                        val sha256 = sha256Hex(bytes)
                        require(sha256.equals(entry.sha256, ignoreCase = true)) {
                            "${entry.fileName} changed after it was queued; restart the app to rediscover it"
                        }
                        client.uploadBackupGpx(
                            baseAddress = server,
                            status = status,
                            dateFolder = entry.dateFolder,
                            fileName = entry.fileName,
                            bytes = bytes,
                            sha256 = sha256
                        )
                        if (entry.status.isBlank()) BackupGpxSendQueue.markStatus(this, entry.id, status)
                        BackupGpxSendQueue.markSent(this, entry.id)
                        sent++
                        postUi {
                            sendGpxStatus.text = "Sending $sent/${pending.size} GPX files…"
                        }
                    }
                    postUi {
                        sendingGpxFiles = false
                        val remaining = BackupGpxSendQueue.pendingCount(this)
                        updateSendGpxButton()
                        gpxSendStatusOverride = if (remaining == 0) {
                            "All pending GPX backup files were copied to the camera Output Folder."
                        } else {
                            "$sent GPX files sent • $remaining new file(s) waiting"
                        }
                        lastGpxSendPendingCount = remaining
                        sendGpxStatus.text = gpxSendStatusOverride
                    }
                } catch (error: Throwable) {
                    postUi {
                        sendingGpxFiles = false
                        val remaining = BackupGpxSendQueue.pendingCount(this)
                        updateSendGpxButton()
                        gpxSendStatusOverride = "GPX send stopped after $sent file(s): ${error.message ?: error.javaClass.simpleName} • $remaining pending"
                        lastGpxSendPendingCount = remaining
                        sendGpxStatus.text = gpxSendStatusOverride
                        Toast.makeText(this, "Could not send all GPX files: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.onFailure { error ->
            sendingGpxFiles = false
            sendGpxStatus.text = "GPX send could not start: ${error.message ?: error.javaClass.simpleName}"
            updateSendGpxButton()
        }
    }

    private fun resolveBackupCameraStatus(
        backupFileName: String,
        cameraQueue: List<PendingGpxItem>
    ): String? {
        val videoBase = backupFileName
            .replace(Regex("(?i)_backup(?: \\(\\d+\\))?\\.gpx$"), "")
        return cameraQueue.asReversed().firstOrNull { item ->
            File(item.videoName).nameWithoutExtension.equals(videoBase, ignoreCase = true)
        }?.status?.trim()?.uppercase(Locale.US)?.takeIf { it == "GOOD" || it == "FAILED" || it == "ERROR" }
    }

    private fun readBackupGpxBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: error("Backup GPX is no longer readable; keep the selected Backup Folder permission")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_GPX_SEND_BYTES) { "Backup GPX exceeds the send safety limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray().also { bytes ->
                require(bytes.isNotEmpty()) { "Backup GPX is empty" }
                require(bytes.copyOfRange(0, minOf(bytes.size, 4096)).toString(Charsets.UTF_8).contains("<gpx", true)) {
                    "Backup file is not GPX"
                }
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun updateBackupButton() {
        if (!::backupToggle.isInitialized) return
        val enabled = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
            .getBoolean(BackupGpsService.KEY_ENABLED, false)
        backupToggle.text = when {
            backupValidationInProgress -> "CHECKING BACKUP FOLDER…"
            enabled -> "STOP AUTOMATIC BACKUP"
            else -> "START AUTOMATIC BACKUP"
        }
        backupToggle.isEnabled = !backupValidationInProgress
        if (enabled && !backupValidationInProgress) {
            backupToggle.backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 125, 50))
            backupToggle.setTextColor(Color.WHITE)
        } else {
            backupToggle.backgroundTintList = backupInactiveBackgroundTint
            backupToggle.setTextColor(Color.rgb(32, 33, 36))
        }
    }

    private fun backupFolderLabel(): String {
        val value = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
            .getString(BackupGpsService.KEY_FOLDER, null)
        if (value.isNullOrBlank()) return "Backup folder: not selected"
        val uri = Uri.parse(value)
        val displayPath = runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val parts = documentId.split(":", limit = 2)
            val volume = parts.firstOrNull().orEmpty()
            val relative = parts.getOrNull(1).orEmpty().replace('/', '\\')
            val root = if (volume.equals("primary", ignoreCase = true)) "Internal Storage" else "Storage ($volume)"
            if (relative.isBlank()) root else "$root\\$relative"
        }.getOrElse { uri.lastPathSegment ?: value }
        return "Backup folder: $displayPath"
    }

    private fun initializeRuntimeState() {
        if (runtimeInitializedForProcess) return
        runtimeInitializedForProcess = true

        val appContext = applicationContext
        val cameraSnapshot = CameraConnectionService.snapshot(appContext)
        val cameraServiceRunning = CameraConnectionService.isRunningInProcess()

        // Do not resurrect a camera connection from stale SharedPreferences on a cold launch.
        // The manual-connect contract is preserved: a new service is started only when the user
        // presses Connect. If Android already has the foreground service alive in this process,
        // keep that genuine background session intact.
        if (!cameraServiceRunning) {
            if (cameraSnapshot.requested) {
                CameraConnectionService.clearStalePersistedConnection(appContext)
            } else {
                ClientSessionState.clear()
            }
        }

        // Reset backup UI state immediately, but do not call stopService from the Activity's main
        // thread. Service shutdown may involve LocationManager/notification Binder work on some
        // devices and must never delay first-frame rendering.
        getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(BackupGpsService.KEY_ENABLED, false)
            .putString(BackupGpsService.KEY_STATUS, "Automatic backup is off")
            .remove(BackupGpsService.KEY_ACTIVE_CAMERA_ADDRESS)
            .putLong(BackupGpsService.KEY_GPS_FIX_COUNT, 0L)
            .putLong(BackupGpsService.KEY_GPS_LAST_FIX_AT, 0L)
            .putInt(BackupGpsService.KEY_GPS_CONNECTED_SATELLITE_COUNT, 0)
            .putInt(BackupGpsService.KEY_GPS_AVAILABLE_SATELLITE_COUNT, 0)
            .putBoolean(BackupGpsService.KEY_GPS_PROVIDER_ENABLED, false)
            .remove(BackupGpsService.KEY_GPS_PROVIDER)
            .remove(BackupGpsService.KEY_GPS_COORDINATES)
            .remove(BackupGpsService.KEY_ACCURACY)
            .apply()

        // NotificationManager IPC is isolated on notification-only executors. It never occupies
        // the Activity worker used by Connect, backup-folder validation or other app operations.
        CameraConnectionService.dismissStorageAlertNotification(appContext)
        BackupGpsService.dismissNotification(appContext)
        if (!cameraServiceRunning) {
            CameraConnectionService.dismissConnectionNotification(appContext)
        }
    }

    private fun updateBackupCameraAddressForCurrentConnection(force: Boolean = false) {
        val prefs = getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE)
        if (!force && !prefs.getBoolean(BackupGpsService.KEY_ENABLED, false)) return
        val liveAddress = currentServerAddress?.takeIf {
            isConnected && connectionState == ConnectionState.CONNECTED && it.isNotBlank()
        }
        prefs.edit().apply {
            if (liveAddress == null) remove(BackupGpsService.KEY_ACTIVE_CAMERA_ADDRESS)
            else putString(BackupGpsService.KEY_ACTIVE_CAMERA_ADDRESS, liveAddress)
        }.apply()
    }

    private fun stopAutomaticBackup(statusText: String) {
        stopService(Intent(this, BackupGpsService::class.java))
        getSharedPreferences(BackupGpsService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(BackupGpsService.KEY_ENABLED, false)
            .putString(BackupGpsService.KEY_STATUS, statusText)
            .remove(BackupGpsService.KEY_ACTIVE_CAMERA_ADDRESS)
            .apply()
        if (::backupStatus.isInitialized) backupStatus.text = statusText
        updateBackupButton()
        updateKeepScreenOn()
    }

    private fun showStartupFailure(error: Throwable) {
        val message = TextView(this).apply {
            text = "The client could not start.\n\n${error.javaClass.simpleName}: ${error.message ?: "No message"}\n\nA diagnostic file was saved as client-crash.txt."
            textSize = 16f
            setTextColor(Color.RED)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(message) })
    }

    private fun writeStartupCrash(error: Throwable) {
        runCatching {
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            File(filesDir, "client-crash.txt").writeText("Client version ${BuildConfig.VERSION_NAME}\n${error.javaClass.name}: ${error.message}\n\n$stack")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_GPX_SEND_BYTES = 16L * 1024L * 1024L
        private const val PREFS = "client_settings"
        private const val KEY_ADDRESS = "server_address"
        private const val KEY_ADDRESS_HISTORY_JSON = "successful_server_addresses_json_v2"
        private const val KEY_ADDRESS_HISTORY_LEGACY = "successful_server_addresses"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val MAX_ADDRESS_HISTORY = 15
        private const val REQUEST_FOLDER = 501
        private const val REQUEST_LOCATION = 502
        private const val REQUEST_NOTIFICATIONS = 503
        @Volatile private var runtimeInitializedForProcess = false
    }

}
