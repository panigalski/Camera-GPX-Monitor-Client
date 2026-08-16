package com.labpano.gpxclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal class HttpStatusException(val statusCode: Int, message: String) : IllegalStateException(message)

class DashboardClient {
    fun fetch(baseAddress: String): Dashboard {
        val base = normalizeAddress(baseAddress)
        return parse(JSONObject(requestText("GET", "$base/api/v1/dashboard")))
    }

    /**
     * Initial manual-connection snapshot. Main App 0.5.38+ treats syncCameraSettings=1 as a
     * synchronous Camera-settings handshake and re-reads /efs/video.properties before replying.
     * Older Main Apps safely ignore the query parameter, so this remains backwards compatible.
     */
    fun fetchForConnection(baseAddress: String): Dashboard {
        val base = normalizeAddress(baseAddress)
        return parse(JSONObject(requestText("GET", "$base/api/v1/dashboard?syncCameraSettings=1")))
    }

    /**
     * Returns null only when the Main App predates the lightweight live-status endpoint. Other
     * failures are real connection failures and are propagated to the caller.
     */
    fun fetchLiveStatus(baseAddress: String): LiveStatus? {
        val base = normalizeAddress(baseAddress)
        val text = try {
            requestText("GET", "$base/api/v1/live-status")
        } catch (error: HttpStatusException) {
            if (error.statusCode == 404) return null
            throw error
        }
        val root = JSONObject(text)
        validateApiVersion(root, "Live status")
        return LiveStatus(
            generatedAt = root.optLong("generatedAt").coerceAtLeast(0L),
            outputFolder = root.optString("outputFolder"),
            cameraRecording = cameraRecording(root.optJSONObject("cameraRecording")),
            fragmentStorage = fragmentStorage(root.optJSONObject("fragmentStorage")),
            monitoring = monitoring(root.optJSONObject("monitoring")),
            transfers = transfers(root.optJSONArray("transfers")),
            generatedElapsedRealtime = root.optLong("generatedElapsedRealtime").coerceAtLeast(0L),
            processStartedElapsedRealtime = root.optLong("processStartedElapsedRealtime").coerceAtLeast(0L),
            processInstanceId = root.optString("processInstanceId")
        )
    }

    fun fetchPendingGpx(baseAddress: String): List<PendingGpxItem> {
        val base = normalizeAddress(baseAddress)
        val result = mutableListOf<PendingGpxItem>()
        val seenIds = linkedSetOf<String>()
        var offset = 0
        var pageCount = 0

        while (pageCount < MAX_PENDING_GPX_PAGES) {
            val root = JSONObject(
                requestText(
                    "GET",
                    "$base/api/v1/pending-gpx?limit=$PENDING_GPX_PAGE_SIZE&offset=$offset"
                )
            )
            validateApiVersion(root, "Pending GPX")
            val page = pendingGpxItems(root.optJSONArray("items") ?: JSONArray())
            page.forEach { item ->
                val dedupeKey = item.id.ifBlank {
                    "${item.completedAt}|${item.videoName}|${item.gpxName}|${item.gpxSizeBytes}"
                }
                if (seenIds.add(dedupeKey)) result += item
            }

            val nextOffset = if (root.has("nextOffset") && !root.isNull("nextOffset")) {
                root.optInt("nextOffset", -1)
            } else {
                -1
            }
            if (nextOffset <= offset) return result

            offset = nextOffset
            pageCount++
        }

        throw IllegalStateException("Pending GPX queue exceeded the client pagination safety limit")
    }

    internal fun pendingGpxItems(array: JSONArray): List<PendingGpxItem> =
        (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            PendingGpxItem(
                id = value.optString("id"),
                status = value.optString("status"),
                completedAt = value.optString("completedAt"),
                videoName = value.optString("videoName"),
                videoPath = value.optString("videoPath"),
                gpxName = value.optString("gpxName"),
                gpxPath = value.optString("gpxPath"),
                gpxSizeBytes = value.optLong("gpxSizeBytes"),
                downloadUrl = value.optString("downloadUrl")
            )
        }

    fun deleteEntry(baseAddress: String, type: ReportType, entry: ReportEntry) {
        val base = normalizeAddress(baseAddress)
        val query = listOf(
            "type" to type.apiValue,
            "timestamp" to entry.timestamp,
            "path" to entry.path,
            "message" to entry.message
        ).joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
        }
        requestText("DELETE", "$base/api/v1/report-entry?$query")
    }

    fun normalizeAddress(value: String): String {
        val raw = value.trim()
        require(raw.isNotBlank()) { "Enter the main app address" }

        val candidate = if (raw.contains("://")) raw else "http://$raw"
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid camera address")
        }

        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Only HTTP or HTTPS addresses are supported"
        }
        require(uri.rawUserInfo.isNullOrBlank()) { "User information is not allowed in the camera address" }
        require(uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank()) {
            "Enter only the camera host and optional port"
        }
        require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
            "Enter only the camera host and optional port"
        }
        require(!uri.host.isNullOrBlank()) { "Invalid camera IP address" }
        require(uri.port == -1 || uri.port in 1..65_535) { "Invalid camera port" }

        val port = if (uri.port >= 0) uri.port else DEFAULT_PORT
        return URI(uri.scheme.lowercase(), null, uri.host, port, null, null, null).toASCIIString()
    }

    private fun requestText(method: String, url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
        }
        return try {
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = input?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_JSON_BYTES) { "Camera response is too large" }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrNull().orEmpty()
                throw HttpStatusException(code, message.ifBlank { "Camera returned HTTP $code" })
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(root: JSONObject): Dashboard {
        validateApiVersion(root, "Dashboard")
        return Dashboard(
            appVersion = root.optString("appVersion"),
            monitoringDirectory = root.optString("monitoringDirectory"),
            outputFolder = root.optString("outputFolder").ifBlank {
                root.optJSONObject("reportHealth")?.optString("destination").orEmpty()
            },
            internalStorage = storage(root.optJSONObject("internalStorage")),
            externalStorage = root.optJSONObject("externalStorage")?.let { storage(it) },
            battery = battery(root.optJSONObject("battery")),
            cameraRecording = cameraRecording(root.optJSONObject("cameraRecording")),
            fragmentStorage = fragmentStorage(root.optJSONObject("fragmentStorage")),
            monitoring = monitoring(root.optJSONObject("monitoring")),
            reportHealth = reportHealth(root.optJSONObject("reportHealth")),
            errors = reports(root.optJSONArray("error")),
            failed = reports(root.optJSONArray("failed")),
            good = reports(root.optJSONArray("good")),
            transfers = transfers(root.optJSONArray("transfers")),
            storageWriteAlerts = storageWriteAlerts(root.optJSONArray("storageWriteAlerts")),
            storageWriteAlertsSupported = root.has("storageWriteAlerts"),
            deviceDiagnostics = deviceDiagnostics(root.optJSONObject("deviceDiagnostics")),
            generatedAt = root.optLong("generatedAt").coerceAtLeast(0L),
            generatedElapsedRealtime = root.optLong("generatedElapsedRealtime").coerceAtLeast(0L),
            processStartedElapsedRealtime = root.optLong("processStartedElapsedRealtime").coerceAtLeast(0L),
            processInstanceId = root.optString("processInstanceId")
        )
    }

    private fun deviceDiagnostics(value: JSONObject?): DeviceDiagnostics? {
        if (value == null) return null
        val bluetoothValue = value.optJSONObject("bluetooth") ?: JSONObject()
        val devicesArray = bluetoothValue.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until devicesArray.length()).mapNotNull { index ->
            devicesArray.optJSONObject(index)?.let { device ->
                BluetoothDeviceDiagnostics(
                    name = device.optString("name"),
                    address = device.optString("address"),
                    transport = device.optString("transport"),
                    likelyGps = device.optBoolean("likelyGps", false),
                    rssiDbm = nullableInt(device, "rssiDbm"),
                    rssiObservedAt = device.optLong("rssiObservedAt").coerceAtLeast(0L),
                    rssiNote = device.optString("rssiNote")
                )
            }
        }
        val bluetooth = BluetoothDiagnostics(
            available = bluetoothValue.optBoolean("available", false),
            enabled = bluetoothValue.optBoolean("enabled", false),
            devices = devices,
            error = bluetoothValue.optString("error")
        )

        val locationValue = value.optJSONObject("location") ?: JSONObject()
        val location = LocationSourceDiagnostics(
            available = locationValue.optBoolean("available", false),
            permissionGranted = locationValue.optBoolean("permissionGranted", false),
            fresh = locationValue.optBoolean("fresh", false),
            sourceType = locationValue.optString("sourceType", "UNKNOWN"),
            sourceLabel = locationValue.optString("sourceLabel", "Unknown"),
            provider = locationValue.optString("provider"),
            mocked = locationValue.optBoolean("mocked", false),
            lastFixAt = locationValue.optLong("lastFixAt").coerceAtLeast(0L),
            accuracyMeters = nullableDouble(locationValue, "accuracyMeters"),
            inferredExternalBluetoothDevice = locationValue.optString("inferredExternalBluetoothDevice")
        )

        val gnssValue = value.optJSONObject("gnss") ?: JSONObject()
        val gnss = GnssSignalDiagnostics(
            supported = gnssValue.optBoolean("supported", false),
            permissionGranted = gnssValue.optBoolean("permissionGranted", false),
            running = gnssValue.optBoolean("running", false),
            fresh = gnssValue.optBoolean("fresh", false),
            satellitesVisible = gnssValue.optInt("satellitesVisible").coerceAtLeast(0),
            satellitesUsedInFix = gnssValue.optInt("satellitesUsedInFix").coerceAtLeast(0),
            averageCn0DbHz = nullableDouble(gnssValue, "averageCn0DbHz"),
            maxCn0DbHz = nullableDouble(gnssValue, "maxCn0DbHz"),
            firstFixMs = nullableInt(gnssValue, "firstFixMs"),
            updatedAt = gnssValue.optLong("updatedAt").coerceAtLeast(0L),
            activeLocationMocked = gnssValue.optBoolean("activeLocationMocked", false),
            signalMatchesActiveLocationSource = gnssValue.optBoolean("signalMatchesActiveLocationSource", true),
            constellations = intMap(gnssValue.optJSONObject("constellations")),
            usedConstellations = intMap(gnssValue.optJSONObject("usedConstellations"))
        )
        return DeviceDiagnostics(bluetooth, location, gnss)
    }

    private fun intMap(value: JSONObject?): Map<String, Int> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, Int>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = value.optInt(key).coerceAtLeast(0)
        }
        return result
    }

    private fun nullableDouble(value: JSONObject, key: String): Double? =
        if (!value.has(key) || value.isNull(key)) null else value.optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() }

    private fun nullableInt(value: JSONObject, key: String): Int? =
        if (!value.has(key) || value.isNull(key)) null else value.optInt(key)

    private fun validateApiVersion(root: JSONObject, endpointName: String) {
        val raw = root.optString("apiVersion").trim()
        if (raw.isBlank()) return
        val version = raw.substringBefore('.').toIntOrNull() ?: return
        require(version <= MAX_SUPPORTED_API_VERSION) {
            "$endpointName API $raw is newer than this client supports"
        }
    }

    private fun cameraRecording(value: JSONObject?): CameraRecordingStatus {
        if (value == null) {
            return CameraRecordingStatus(false, false, "", 0L, "Unavailable")
        }
        return CameraRecordingStatus(
            available = value.optBoolean("available", false),
            recording = value.optBoolean("recording", false),
            videoName = value.optString("videoName"),
            updatedAt = value.optLong("updatedAt").coerceAtLeast(0L),
            source = value.optString("source", "mp4-file-events"),
            finalizing = value.optBoolean("finalizing", false),
            generation = value.optLong("generation").coerceAtLeast(0L)
        )
    }

    private data class ParsedFragmentLimit(
        val type: String,
        val sizeGb: Int? = null,
        val durationMinutes: Int? = null,
        val display: String
    )

    /**
     * Parse the exact strings used by Pilot Camera 5.18.11. The structured fields sent by new Main
     * Apps take precedence, but this parser keeps compatibility with older Main Apps that only send
     * raw/display strings.
     */
    private fun parseFragmentLimit(rawValue: String, displayValue: String, enabled: Boolean): ParsedFragmentLimit {
        if (!enabled) return ParsedFragmentLimit("unlimited", display = "Off (Unlimited)")
        val raw = rawValue.trim()
        val compact = raw.lowercase().replace(" ", "")
        Regex("^(4|6|8|10)(?:g|gb)$").matchEntire(compact)?.let { match ->
            val gb = match.groupValues[1].toInt()
            return ParsedFragmentLimit("size", sizeGb = gb, display = "$gb GB")
        }
        Regex("^(10|30)(?:m|min|mins|minute|minutes)$").matchEntire(compact)?.let { match ->
            val minutes = match.groupValues[1].toInt()
            return ParsedFragmentLimit("time", durationMinutes = minutes, display = "$minutes min")
        }
        Regex("^(1|2)(?:h|hr|hrs|hour|hours)$").matchEntire(compact)?.let { match ->
            val hours = match.groupValues[1].toInt()
            return ParsedFragmentLimit(
                "time",
                durationMinutes = hours * 60,
                display = if (hours == 1) "1 Hour" else "$hours Hours"
            )
        }

        // Some legacy Main versions carried only the Camera-facing display label. Parse the same
        // supported values from it so 4 GB -> 10 GB remains correctly typed during rolling upgrades.
        val displayCompact = displayValue.trim().lowercase().replace(" ", "")
        Regex("^(4|6|8|10)gb(?:\\(observed\\))?$").matchEntire(displayCompact)?.let { match ->
            val gb = match.groupValues[1].toInt()
            return ParsedFragmentLimit("size", sizeGb = gb, display = "$gb GB")
        }
        Regex("^(10|30)min(?:\\(observed\\))?$").matchEntire(displayCompact)?.let { match ->
            val minutes = match.groupValues[1].toInt()
            return ParsedFragmentLimit("time", durationMinutes = minutes, display = "$minutes min")
        }
        Regex("^(1|2)hours?(?:\\(observed\\))?$").matchEntire(displayCompact)?.let { match ->
            val hours = match.groupValues[1].toInt()
            return ParsedFragmentLimit(
                "time",
                durationMinutes = hours * 60,
                display = if (hours == 1) "1 Hour" else "$hours Hours"
            )
        }
        return ParsedFragmentLimit("other", display = displayValue.ifBlank { "Enabled" })
    }

    private fun fragmentStorage(value: JSONObject?): FragmentStorageStatus {
        val unknownMode = FragmentStorageMode()
        if (value == null) {
            return FragmentStorageStatus(
                available = false,
                enabled = false,
                display = "Unavailable",
                updatedAt = 0L,
                source = "Unavailable",
                error = "Update the Main App to a Fragment Storage-aware version",
                stitched = unknownMode,
                streetView = unknownMode,
                unstitched = unknownMode,
                timeLapse = unknownMode
            )
        }

        fun mode(name: String): FragmentStorageMode {
            val node = value.optJSONObject(name) ?: return unknownMode
            val known = node.optBoolean("known", false)
            val enabled = node.optBoolean("enabled", false)
            val raw = node.optString("rawValue")
            val serverDisplay = node.optString("displayValue", "Unknown")
            val parsed = if (known) parseFragmentLimit(raw, serverDisplay, enabled) else ParsedFragmentLimit("unknown", display = "Unknown")
            val serverType = node.optString("limitType").takeIf { it.isNotBlank() && it != "unknown" }
            val serverSize = nullableInt(node, "sizeGb")?.takeIf { it > 0 }
            val serverDuration = nullableInt(node, "durationMinutes")?.takeIf { it > 0 }
            val finalType = serverType ?: parsed.type
            val finalSize = serverSize ?: parsed.sizeGb
            val finalDuration = serverDuration ?: parsed.durationMinutes
            val canonicalDisplay = when {
                !known -> "Unknown"
                !enabled -> "Off (Unlimited)"
                finalType == "size" && finalSize != null -> "$finalSize GB"
                finalType == "time" && finalDuration != null -> when {
                    finalDuration == 60 -> "1 Hour"
                    finalDuration % 60 == 0 -> "${finalDuration / 60} Hours"
                    else -> "$finalDuration min"
                }
                else -> parsed.display.ifBlank { serverDisplay }
            }
            return FragmentStorageMode(
                known = known,
                enabled = enabled,
                rawValue = raw,
                displayValue = canonicalDisplay,
                limitType = finalType,
                sizeGb = finalSize,
                durationMinutes = finalDuration
            )
        }

        val stitched = mode("stitched")
        val streetView = mode("streetView")
        val unstitched = mode("unstitched")
        val timeLapse = mode("timeLapse")
        val modeName = value.optString("mode")
        val selectedMode = when (modeName) {
            "stitched" -> stitched
            "streetView" -> streetView
            "unstitched" -> unstitched
            "timeLapse" -> timeLapse
            else -> null
        }
        val enabled = value.optBoolean("enabled", selectedMode?.enabled ?: false)
        val raw = value.optString("rawValue").ifBlank { selectedMode?.rawValue.orEmpty() }
        val serverDisplay = value.optString("display", selectedMode?.displayValue ?: "Unavailable")
        val parsed = parseFragmentLimit(raw, serverDisplay, enabled)
        val serverType = value.optString("limitType").takeIf { it.isNotBlank() && it != "unknown" }
        val sizeGb = nullableInt(value, "sizeGb")?.takeIf { it > 0 } ?: selectedMode?.sizeGb ?: parsed.sizeGb
        val durationMinutes = nullableInt(value, "durationMinutes")?.takeIf { it > 0 }
            ?: selectedMode?.durationMinutes ?: parsed.durationMinutes
        val limitType = serverType ?: selectedMode?.limitType?.takeIf { it != "unknown" } ?: parsed.type
        val display = when {
            !value.optBoolean("available", false) -> serverDisplay.ifBlank { "Unavailable" }
            !enabled -> "Off (Unlimited)"
            limitType == "size" && sizeGb != null -> "$sizeGb GB"
            limitType == "time" && durationMinutes != null -> when {
                durationMinutes == 60 -> "1 Hour"
                durationMinutes % 60 == 0 -> "${durationMinutes / 60} Hours"
                else -> "$durationMinutes min"
            }
            else -> parsed.display.ifBlank { serverDisplay }
        }

        return FragmentStorageStatus(
            available = value.optBoolean("available", false),
            enabled = enabled,
            display = display,
            updatedAt = value.optLong("updatedAt").coerceAtLeast(0L),
            revision = value.optLong("revision").coerceAtLeast(0L),
            source = value.optString("source", "pilot-control-protocol"),
            error = value.optString("error"),
            mode = modeName,
            modeSource = value.optString("modeSource", "unknown"),
            modeUpdatedAt = value.optLong("modeUpdatedAt").coerceAtLeast(0L),
            rawValue = raw,
            limitType = limitType,
            sizeGb = sizeGb,
            durationMinutes = durationMinutes,
            processStartedElapsedRealtime = value.optLong("processStartedElapsedRealtime").coerceAtLeast(0L),
            stitched = stitched,
            streetView = streetView,
            unstitched = unstitched,
            timeLapse = timeLapse
        )
    }

    private fun monitoring(value: JSONObject?): MonitoringStatus {
        if (value == null) return MonitoringStatus(false, false, false, "Unavailable")
        return MonitoringStatus(
            available = true,
            requested = value.optBoolean("requested", false),
            serviceRunning = value.optBoolean("serviceRunning", false),
            lastStatus = value.optString("lastStatus", "idle")
        )
    }

    private fun reportHealth(value: JSONObject?): ReportHealth {
        if (value == null) {
            return ReportHealth(false, "", "", false, false, false, emptyList(), 0L, 0L, "", "")
        }
        val filesArray = value.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArray.length()).mapNotNull { index ->
            filesArray.optJSONObject(index)?.let { file ->
                ReportFileHealth(
                    name = file.optString("name"),
                    exists = file.optBoolean("exists", false),
                    readable = file.optBoolean("readable", false),
                    writable = file.optBoolean("writable", false),
                    sizeBytes = file.optLong("sizeBytes").coerceAtLeast(0L)
                )
            }
        }
        return ReportHealth(
            supported = true,
            destination = value.optString("destination"),
            destinationType = value.optString("destinationType"),
            available = value.optBoolean("available", false),
            writable = value.optBoolean("writable", false),
            ioHealthy = value.optBoolean("ioHealthy", true),
            files = files,
            lastSuccessAt = value.optLong("lastSuccessAt").coerceAtLeast(0L),
            lastFailureAt = value.optLong("lastFailureAt").coerceAtLeast(0L),
            lastOperation = value.optString("lastOperation"),
            lastError = value.optString("lastError")
        )
    }

    private fun battery(value: JSONObject?): BatteryStatus {
        if (value == null) {
            return BatteryStatus(false, -1, false, false, "Unknown", "Battery", null, "Unavailable", -1, "Unknown", "Battery data unavailable")
        }
        return BatteryStatus(
            available = value.optBoolean("available", false),
            percent = value.optInt("percent", -1),
            charging = value.optBoolean("charging", false),
            full = value.optBoolean("full", false),
            status = value.optString("status", "Unknown"),
            powerSource = value.optString("powerSource", "Battery"),
            temperatureC = if (value.has("temperatureC") && !value.isNull("temperatureC")) value.optDouble("temperatureC") else null,
            temperatureSource = value.optString("temperatureSource", "Legacy/unknown"),
            voltageMillivolts = value.optInt("voltageMillivolts", -1),
            health = value.optString("health", "Unknown"),
            error = value.optString("error")
        )
    }

    private fun storage(value: JSONObject?): StorageUsage {
        if (value == null) return StorageUsage("", 0L, 0L, 0L, 0, "Storage data unavailable")
        return StorageUsage(
            path = value.optString("path"),
            totalBytes = value.optLong("totalBytes").coerceAtLeast(0L),
            freeBytes = value.optLong("freeBytes").coerceAtLeast(0L),
            usedBytes = value.optLong("usedBytes").coerceAtLeast(0L),
            usedPercent = value.optInt("usedPercent").coerceIn(0, 100),
            error = value.optString("error")
        )
    }

    private fun reports(array: JSONArray?): List<ReportEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                ReportEntry(it.optString("timestamp"), it.optString("path"), it.optString("message"))
            }
        }.reversed()
    }

    private fun storageWriteAlerts(array: JSONArray?): List<StorageWriteAlert> {
        if (array == null) return emptyList()
        val limit = minOf(array.length(), MAX_STORAGE_WRITE_ALERTS)
        return (0 until limit).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                StorageWriteAlert(
                    id = it.optString("id").take(MAX_ALERT_ID_CHARS),
                    occurredAt = it.optLong("occurredAt").coerceAtLeast(0L),
                    storageType = it.optString("storageType", "UNKNOWN").take(MAX_ALERT_SHORT_FIELD_CHARS),
                    videoName = it.optString("videoName").take(MAX_ALERT_FIELD_CHARS),
                    destination = it.optString("destination").take(MAX_ALERT_FIELD_CHARS),
                    operation = it.optString("operation").take(MAX_ALERT_SHORT_FIELD_CHARS),
                    message = it.optString("message").take(MAX_ALERT_MESSAGE_CHARS)
                )
            }
        }.sortedByDescending { it.occurredAt }
    }

    private fun transfers(array: JSONArray?): List<TransferEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                TransferEntry(
                    id = it.optString("id"),
                    sourceName = it.optString("sourceName"),
                    destinationName = it.optString("destinationName"),
                    copiedBytes = it.optLong("copiedBytes").coerceAtLeast(0L),
                    totalBytes = it.optLong("totalBytes").coerceAtLeast(0L),
                    percent = it.optInt("percent").coerceIn(0, 100),
                    phase = it.optString("phase")
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_PORT = 1100
        private const val PENDING_GPX_PAGE_SIZE = 500
        private const val MAX_PENDING_GPX_PAGES = 100
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_JSON_BYTES = 5L * 1024L * 1024L
        private const val MAX_SUPPORTED_API_VERSION = 3
        private const val MAX_STORAGE_WRITE_ALERTS = 50
        private const val MAX_ALERT_ID_CHARS = 160
        private const val MAX_ALERT_SHORT_FIELD_CHARS = 120
        private const val MAX_ALERT_FIELD_CHARS = 500
        private const val MAX_ALERT_MESSAGE_CHARS = 1_000
    }
}
