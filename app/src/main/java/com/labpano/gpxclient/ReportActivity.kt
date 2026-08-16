package com.labpano.gpxclient

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ReportActivity : Activity() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = DashboardClient()
    private var pollTask: ScheduledFuture<*>? = null
    private var refreshFailures = 0
    @Volatile private var destroyed = false
    @Volatile private var started = false

    private lateinit var serverAddress: String
    private lateinit var reportType: ReportType
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ReportAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS).orEmpty()
        reportType = runCatching {
            ReportType.valueOf(intent.getStringExtra(EXTRA_REPORT_TYPE).orEmpty())
        }.getOrDefault(ReportType.ERROR)

        if (serverAddress.isBlank()) {
            Toast.makeText(this, "Server address is unavailable", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        started = true
        scheduleRefresh(0L)
    }

    override fun onStop() {
        started = false
        pollTask?.cancel(false)
        pollTask = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        pollTask?.cancel(true)
        pollTask = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun postUi(action: () -> Unit) {
        mainHandler.post {
            if (!destroyed && !isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) action()
        }
    }

    private fun scheduleRefresh(delayMs: Long) {
        if (destroyed || !started) return
        pollTask?.cancel(false)
        pollTask = runCatching {
            worker.schedule({ refresh() }, delayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 246, 248))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(69, 39, 160))
        }
        header.addView(Button(this).apply {
            text = "‹ BACK"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(106), dp(48)))
        titleView = TextView(this).apply {
            text = reportType.displayName.uppercase(Locale.US)
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, 1)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)

        statusView = TextView(this).apply {
            text = "Loading entries…"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(statusView)

        adapter = ReportAdapter(this)
        listView = ListView(this).apply {
            dividerHeight = dp(1)
            adapter = this@ReportActivity.adapter
            setPadding(dp(8), 0, dp(8), dp(12))
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            isFastScrollEnabled = true
            transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnItemClickListener { _, _, position, _ ->
                confirmDelete(this@ReportActivity.adapter.getItem(position))
            }
        }
        emptyView = TextView(this).apply {
            text = "No ${reportType.displayName.lowercase(Locale.US)} entries."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        listView.emptyView = emptyView
        val content = FrameLayout(this).apply {
            addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            addView(listView, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun refresh() {
        if (destroyed) return
        try {
            val dashboard = client.fetch(serverAddress)
            val entries = entriesFor(dashboard)
            postUi {
                refreshFailures = 0
                render(entries, dashboard)
                scheduleRefresh(REFRESH_SUCCESS_DELAY_MS)
            }
        } catch (error: Throwable) {
            postUi {
                // ReportActivity is another live Main App consumer. If it detects the loss first,
                // terminate the shared camera session too and clear the report values on screen.
                CameraConnectionService.disconnect(this)
                refreshFailures = 0
                adapter.replace(emptyList())
                titleView.text = "${reportType.displayName.uppercase(Locale.US)} (0)"
                statusView.text = "Connection lost • return to the main Client screen and press Connect"
                emptyView.text = "Camera connection lost."
                pollTask?.cancel(false)
                pollTask = null
            }
        }
    }

    private fun entriesFor(dashboard: Dashboard): List<ReportEntry> = when (reportType) {
        ReportType.ERROR -> dashboard.errors
        ReportType.FAILED -> dashboard.failed
        ReportType.GOOD -> dashboard.good
    }

    private fun render(entries: List<ReportEntry>, dashboard: Dashboard) {
        val first = listView.firstVisiblePosition
        val top = if (listView.childCount > 0) listView.getChildAt(0).top else 0
        adapter.replace(entries)
        if (first >= 0 && adapter.count > 0) {
            listView.setSelectionFromTop(first.coerceAtMost(adapter.count - 1), top)
        }
        val word = if (entries.size == 1) "entry" else "entries"
        titleView.text = "${reportType.displayName.uppercase(Locale.US)} (${entries.size})"
        statusView.text = when {
            dashboard.monitoring.available && !dashboard.monitoring.serviceRunning ->
                "${dashboard.appVersion} • Monitoring is OFF on Pilot One • no new report entries will be created"
            dashboard.reportHealth.supported && (!dashboard.reportHealth.available || !dashboard.reportHealth.writable || !dashboard.reportHealth.ioHealthy) ->
                "${dashboard.appVersion} • Report storage problem: ${dashboard.reportHealth.lastError.ifBlank { dashboard.reportHealth.destination }}"
            else -> "${dashboard.appVersion} • ${entries.size} $word • tap an entry to delete its TXT record"
        }
        emptyView.text = when {
            dashboard.monitoring.available && !dashboard.monitoring.serviceRunning -> "Monitoring is OFF on Pilot One."
            dashboard.reportHealth.supported && (!dashboard.reportHealth.available || !dashboard.reportHealth.writable || !dashboard.reportHealth.ioHealthy) ->
                "Report files are missing or unavailable in OUTPUT."
            else -> "No ${reportType.displayName.lowercase(Locale.US)} entries."
        }
    }

    private fun confirmDelete(entry: ReportEntry) {
        val filename = File(entry.path).name.ifBlank { entry.path }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete entry?")
            .setMessage("Remove this entry from ${reportType.displayName}?\n\n$filename\n${entry.timestamp}\n\nThis deletes only the TXT report entry. It does not delete the MP4 or GPX file.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteEntry(entry) }
            .show()
    }

    private fun deleteEntry(entry: ReportEntry) {
        pollTask?.cancel(false)
        statusView.text = "Deleting entry…"
        runCatching {
            worker.execute {
                try {
                    client.deleteEntry(serverAddress, reportType, entry)
                    val dashboard = client.fetch(serverAddress)
                    postUi {
                        refreshFailures = 0
                        render(entriesFor(dashboard), dashboard)
                        Toast.makeText(this, "Entry deleted", Toast.LENGTH_SHORT).show()
                        scheduleRefresh(REFRESH_SUCCESS_DELAY_MS)
                    }
                } catch (error: Throwable) {
                    postUi {
                        statusView.text = "Delete failed: ${error.message ?: error.javaClass.simpleName}"
                        Toast.makeText(this, "Delete failed: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        scheduleRefresh(REFRESH_FAILURE_BASE_DELAY_MS)
                    }
                }
            }
        }.onFailure {
            statusView.text = "Delete could not be scheduled"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_REPORT_TYPE = "report_type"
        private const val REFRESH_SUCCESS_DELAY_MS = 2_000L
        private const val REFRESH_FAILURE_BASE_DELAY_MS = 2_000L
        private const val REFRESH_FAILURE_MAX_DELAY_MS = 60_000L
    }
}
