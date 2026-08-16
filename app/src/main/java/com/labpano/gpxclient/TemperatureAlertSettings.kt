package com.labpano.gpxclient

import android.content.Context

object TemperatureAlertSettings {
    private const val PREFS = "temperature_alert_settings"
    private const val KEY_THRESHOLD_C = "threshold_c"
    private const val KEY_ARMED = "armed"
    private const val KEY_LAST_ALERT_AT = "last_alert_at"

    fun thresholdC(context: Context): Double {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_THRESHOLD_C, TemperatureAlertPolicy.DEFAULT_THRESHOLD_C.toFloat())
            .toDouble()
        return stored.takeIf {
            it.isFinite() && it in TemperatureAlertPolicy.MIN_THRESHOLD_C..TemperatureAlertPolicy.MAX_THRESHOLD_C
        } ?: TemperatureAlertPolicy.DEFAULT_THRESHOLD_C
    }

    fun saveThresholdC(context: Context, value: Double) {
        val normalized = TemperatureAlertPolicy.normalizeThreshold(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_THRESHOLD_C, normalized.toFloat())
            .putBoolean(KEY_ARMED, true)
            .putLong(KEY_LAST_ALERT_AT, 0L)
            .apply()
    }

    @Synchronized
    fun shouldPlayWarning(context: Context, temperatureC: Double?, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val threshold = thresholdC(context)
        val armed = prefs.getBoolean(KEY_ARMED, true)
        val lastAlertAt = prefs.getLong(KEY_LAST_ALERT_AT, 0L)
        val decision = TemperatureAlertPolicy.evaluate(temperatureC, threshold, armed, lastAlertAt, now)

        if (decision.armed != armed || decision.lastAlertAt != lastAlertAt) {
            prefs.edit()
                .putBoolean(KEY_ARMED, decision.armed)
                .putLong(KEY_LAST_ALERT_AT, decision.lastAlertAt)
                .apply()
        }
        return decision.shouldAlert
    }

    fun resetAlertState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ARMED, true)
            .putLong(KEY_LAST_ALERT_AT, 0L)
            .apply()
    }

    fun resetToDefault(context: Context) {
        saveThresholdC(context, TemperatureAlertPolicy.DEFAULT_THRESHOLD_C)
    }
}
