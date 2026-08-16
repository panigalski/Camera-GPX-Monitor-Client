package com.labpano.gpxclient

/** Converts the Main App's machine-oriented live processing state into one short UI line. */
object TransferEventFormatter {
    fun formatTransfer(transfer: TransferEntry): String {
        val name = transfer.destinationName.ifBlank { transfer.sourceName }.ifBlank { "file" }
        return when (transfer.phase.trim().uppercase()) {
            "COPYING", "MOVING" -> "Moving: $name"
            "VERIFYING" -> "Verifying: $name"
            "FINALIZING" -> "Finalizing: $name"
            "PREPARING", "QUEUED" -> "Preparing: $name"
            else -> transfer.phase.trim().takeIf { it.isNotBlank() }
                ?.let { "${humanize(it)}: $name" }
                ?: "Processing: $name"
        }
    }

    fun format(dashboard: Dashboard): String {
        val transfer = dashboard.transfers.firstOrNull()
        if (transfer != null) return formatTransfer(transfer)

        val status = dashboard.monitoring.lastStatus.trim()
        if (dashboard.monitoring.serviceRunning && status.startsWith("processing:", ignoreCase = true)) {
            val name = status.substringAfter(':').trim()
            return if (name.isBlank()) {
                "Processing / generating GPX"
            } else {
                "Processing / generating GPX: $name"
            }
        }
        return ""
    }

    private fun humanize(value: String): String = value
        .lowercase()
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
