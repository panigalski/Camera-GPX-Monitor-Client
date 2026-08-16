package com.labpano.gpxclient

import android.content.Context

/** Shared preference controlling sounds generated directly by the client application. */
object AppSoundSettings {
    private const val PREFS = "app_sound_settings"
    private const val KEY_MUTED = "muted"

    fun isMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MUTED, muted)
            .apply()
    }
}
