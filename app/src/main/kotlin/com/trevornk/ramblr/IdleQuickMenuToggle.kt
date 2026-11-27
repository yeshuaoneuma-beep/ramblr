package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Toggle for the floating icon's idle long-press quick menu (Phase 3B.1).
 *
 * On by default so holding the idle icon opens the single quick menu (Live/Batch, Cleanup, Style,
 * Translation, Settings, optional Hide icon). Turning it off disables opening the quick menu on
 * idle long-press while still preserving undo when enabled.
 */
object IdleQuickMenuToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "idle_quick_menu_enabled"

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, true)
    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))
    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
