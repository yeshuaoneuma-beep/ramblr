package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Optional compact recording capsule toggle (Phase 3B.1).
 *
 * Off by default so the circular signature bubble stays compact unless the user opts into the
 * expanded pill (`[ status dot + optional LIVE · 3 live bars · 0:00 timer · ✕ ]`) while recording.
 */
object ActiveRecordingCapsuleToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "active_recording_capsule_enabled"

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, false)
    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))
    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
