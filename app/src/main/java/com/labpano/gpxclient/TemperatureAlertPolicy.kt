package com.labpano.gpxclient

data class TemperatureAlertDecision(
    val shouldAlert: Boolean,
    val armed: Boolean,
    val lastAlertAt: Long
)

object TemperatureAlertPolicy {
    const val DEFAULT_THRESHOLD_C = 73.0
    const val MIN_THRESHOLD_C = 0.0
    const val MAX_THRESHOLD_C = 150.0
    const val REARM_DELTA_C = 3.0
    const val ALERT_COOLDOWN_MS = 10L * 60L * 1000L

    fun normalizeThreshold(value: Double): Double {
        require(value.isFinite()) { "Temperature threshold must be a finite number" }
        require(value in MIN_THRESHOLD_C..MAX_THRESHOLD_C) {
            "Temperature threshold must be between ${MIN_THRESHOLD_C.toInt()} and ${MAX_THRESHOLD_C.toInt()} °C"
        }
        return value
    }

    fun rearmTemperatureC(thresholdC: Double): Double =
        (normalizeThreshold(thresholdC) - REARM_DELTA_C).coerceAtLeast(MIN_THRESHOLD_C)

    fun evaluate(
        temperatureC: Double?,
        thresholdC: Double,
        armed: Boolean,
        lastAlertAt: Long,
        now: Long
    ): TemperatureAlertDecision {
        val threshold = normalizeThreshold(thresholdC)
        if (temperatureC == null || !temperatureC.isFinite()) {
            return TemperatureAlertDecision(false, armed, lastAlertAt)
        }

        var nextArmed = armed
        if (temperatureC <= rearmTemperatureC(threshold)) {
            nextArmed = true
        }

        val cooldownElapsed = lastAlertAt <= 0L || now < lastAlertAt || now - lastAlertAt >= ALERT_COOLDOWN_MS
        if (temperatureC > threshold && nextArmed && cooldownElapsed) {
            return TemperatureAlertDecision(true, false, now)
        }

        return TemperatureAlertDecision(false, nextArmed, lastAlertAt)
    }
}
