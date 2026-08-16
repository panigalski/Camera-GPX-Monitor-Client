package com.labpano.gpxclient

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PilotDiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private lateinit var connectionStatus: TextView
    private lateinit var bluetoothDetails: TextView
    private lateinit var locationDetails: TextView
    private lateinit var gnssDetails: TextView

    private val updater = object : Runnable {
        override fun run() {
            if (!started) return
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Pilot One Bluetooth / GPS"
        setContentView(buildUi())
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
        handler.removeCallbacks(updater)
        handler.post(updater)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(updater)
        super.onStop()
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 246, 248))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(Color.rgb(69, 39, 160))
        }
        header.addView(TextView(this).apply {
            text = "Pilot One Bluetooth / GPS"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        connectionStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(5), 0, 0)
            setTextColor(Color.WHITE)
        }
        header.addView(connectionStatus)
        content.addView(header)

        bluetoothDetails = TextView(this)
        content.addView(sectionCard("Bluetooth", bluetoothDetails), cardParams(dp(10)))

        locationDetails = TextView(this)
        content.addView(sectionCard("Camera GPS Source", locationDetails), cardParams(dp(8)))

        gnssDetails = TextView(this)
        content.addView(sectionCard("GPS Signal", gnssDetails), cardParams(dp(8)))

        content.addView(TextView(this).apply {
            text = "Values update automatically from the Main App dashboard. Bluetooth RSSI is shown only when Android can observe it without starting discovery or opening another Bluetooth connection."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(16), dp(10), dp(16), dp(8))
        })

        content.addView(Button(this).apply {
            text = "BACK"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(12), dp(4), dp(12), dp(12))
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun sectionCard(title: String, details: TextView): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(Color.WHITE)
        addView(TextView(this@PilotDiagnosticsActivity).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(32, 33, 36))
        })
        details.apply {
            textSize = 14f
            setTextColor(Color.rgb(32, 33, 36))
            setPadding(0, dp(6), 0, 0)
        }
        addView(details)
    }

    private fun render() {
        val dashboard = ClientSessionState.lastDashboard
        val connectedAddress = ClientSessionState.connectedAddress
        if (dashboard == null || connectedAddress.isNullOrBlank()) {
            connectionStatus.text = "Not connected to Main App"
            connectionStatus.setTextColor(Color.rgb(255, 205, 210))
            bluetoothDetails.text = "Connected Device: --\nBluetooth Signal Strength: --"
            locationDetails.text = "Current GPS Source: --\nProvider: --\nMocked Location: --\nLast Fix: --\nAccuracy: --"
            gnssDetails.text = "GNSS Receiver: --\nSatellites: --\nC/N₀: --\nConstellations: --"
            return
        }

        connectionStatus.text = "Connected • Main app ${dashboard.appVersion}"
        connectionStatus.setTextColor(Color.WHITE)
        val diagnostics = dashboard.deviceDiagnostics
        if (diagnostics == null) {
            bluetoothDetails.text = "This Main App version does not provide Bluetooth diagnostics."
            locationDetails.text = "This Main App version does not provide GPS source diagnostics."
            gnssDetails.text = "This Main App version does not provide GNSS signal diagnostics."
            return
        }

        renderBluetooth(diagnostics.bluetooth)
        renderLocation(diagnostics.location)
        renderGnss(diagnostics.gnss)
    }

    private fun renderBluetooth(value: BluetoothDiagnostics) {
        bluetoothDetails.text = buildString {
            if (!value.available) {
                append("Bluetooth: Unavailable")
                if (value.error.isNotBlank()) append("\n").append(value.error)
                return@buildString
            }
            append("Bluetooth: ").append(if (value.enabled) "ON" else "OFF")
            if (!value.enabled) return@buildString
            append("\nConnected Devices: ").append(value.devices.size)
            if (value.devices.isEmpty()) {
                append("\nConnected Device: None detected")
                if (value.error.isNotBlank()) append("\nDiagnostic Note: ").append(value.error)
                return@buildString
            }
            value.devices.forEachIndexed { index, device ->
                append("\n\n")
                if (value.devices.size > 1) append("Device ").append(index + 1).append(": ")
                else append("Connected Device: ")
                append(device.name)
                if (device.likelyGps) append(" • likely GPS/GNSS")
                if (device.address.isNotBlank()) append("\nAddress: ").append(device.address)
                if (device.transport.isNotBlank()) append("\nConnection: ").append(device.transport)
                append("\nBluetooth Signal Strength: ")
                if (device.rssiDbm != null) {
                    append(device.rssiDbm).append(" dBm • ").append(rssiQuality(device.rssiDbm))
                    if (device.rssiObservedAt > 0L) append(" • ").append(ageText(device.rssiObservedAt))
                } else {
                    append("--")
                    if (device.rssiNote.isNotBlank()) append("\nRSSI Note: ").append(device.rssiNote)
                }
            }
            if (value.error.isNotBlank()) append("\n\nDiagnostic Note: ").append(value.error)
        }
    }

    private fun renderLocation(value: LocationSourceDiagnostics) {
        locationDetails.text = buildString {
            val source = when {
                !value.permissionGranted -> "Location permission required on Pilot One"
                !value.available -> value.sourceLabel.ifBlank { "No location fix observed" }
                !value.fresh -> "No current fix • last source: ${value.sourceLabel}"
                else -> value.sourceLabel
            }
            append("Current GPS Source: ").append(source)
            append("\nProvider: ").append(value.provider.ifBlank { "--" })
            append("\nMocked Location: ").append(if (value.available) if (value.mocked) "YES" else "NO" else "--")
            append("\nLast Fix: ").append(if (value.lastFixAt > 0L) ageText(value.lastFixAt) else "--")
            append("\nAccuracy: ").append(value.accuracyMeters?.let { String.format(Locale.US, "±%.1f m", it) } ?: "--")
            if (value.inferredExternalBluetoothDevice.isNotBlank() && value.mocked) {
                append("\nExternal GPS Device (inferred): ").append(value.inferredExternalBluetoothDevice)
            }
        }
    }

    private fun renderGnss(value: GnssSignalDiagnostics) {
        gnssDetails.text = buildString {
            if (!value.supported) {
                append("GNSS diagnostics unavailable")
                return@buildString
            }
            if (!value.permissionGranted) {
                append("Location permission required on Pilot One")
                return@buildString
            }
            append("GNSS Receiver: ").append(if (value.running) "Running" else "Stopped")
            append("\nSignal Sample: ").append(if (value.fresh) "Current" else "Stale / unavailable")
            append("\nSatellites: ").append(value.satellitesVisible)
                .append(" visible • ").append(value.satellitesUsedInFix).append(" used in fix")
            append("\nAverage C/N₀: ").append(value.averageCn0DbHz?.let { String.format(Locale.US, "%.1f dB-Hz", it) } ?: "--")
            append("\nMaximum C/N₀: ").append(value.maxCn0DbHz?.let { String.format(Locale.US, "%.1f dB-Hz", it) } ?: "--")
            append("\nConstellations: ").append(formatConstellations(value.constellations))
            append("\nUsed in Fix: ").append(formatConstellations(value.usedConstellations))
            append("\nTime to First Fix: ").append(value.firstFixMs?.let { "${it} ms" } ?: "--")
            if (value.updatedAt > 0L) append("\nLast GNSS Update: ").append(ageText(value.updatedAt))
            if (!value.signalMatchesActiveLocationSource) {
                if (value.activeLocationMocked) {
                    append("\n\nNote: the current location is injected/mock. Satellite C/N₀ above is from the Pilot One system GNSS receiver and may not describe the external receiver supplying the active fix.")
                } else {
                    append("\n\nNote: the current location is not identified as the Pilot One GPS provider. Satellite C/N₀ above is system-GNSS information and may not describe the active location source.")
                }
            }
        }
    }

    private fun formatConstellations(values: Map<String, Int>): String {
        if (values.isEmpty()) return "--"
        return values.entries
            .filter { it.value > 0 }
            .sortedBy { it.key }
            .joinToString(" • ") { "${it.key} ${it.value}" }
            .ifBlank { "--" }
    }

    private fun rssiQuality(rssiDbm: Int): String = when {
        rssiDbm >= -50 -> "Excellent"
        rssiDbm >= -60 -> "Good"
        rssiDbm >= -70 -> "Fair"
        else -> "Weak"
    }

    private fun ageText(timestamp: Long): String {
        val age = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        return when {
            age < 2_000L -> "just now"
            age < 60_000L -> "${age / 1000L} s ago"
            age < 3_600_000L -> "${age / 60_000L} min ago"
            else -> SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date(timestamp))
        }
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(12), top, dp(12), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
