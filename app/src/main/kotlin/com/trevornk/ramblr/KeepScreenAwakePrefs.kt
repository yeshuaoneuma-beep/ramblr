package com.trevornk.ramblr

import android.content.Context

/** How the floating overlay holds `FLAG_KEEP_SCREEN_ON` during dictation (Phase 3B.4). */
enum class KeepScreenAwakeMode { OFF, WHILE_RECORDING, AUTO_UNTIL_INSERTED, CUSTOM_TIMER }

/**
 * "Keep screen awake" setting (Phase 3B.4), stored in the shared `"ramblr"` SharedPreferences.
 *
 * The flag is applied to the overlay window's own `WindowManager.LayoutParams` by
 * [WhisperAccessibilityService], so it needs no permission (no WAKE_LOCK usage for the display).
 */
object KeepScreenAwakePrefs {
    private const val PREFS_NAME = "ramblr"
    const val KEY_MODE = "keep_screen_awake_mode"
    const val KEY_CUSTOM_MS = "keep_screen_awake_custom_ms"

    const val DEFAULT_CUSTOM_MS = 300_000L
    const val MIN_CUSTOM_MS = 60_000L
    const val MAX_CUSTOM_MS = 7_200_000L

    /** Quick presets offered by the Custom timer sheet (2, 5, 10, 15, 30 min). */
    val PRESET_MS: List<Long> = listOf(120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadMode(context: Context): KeepScreenAwakeMode {
        val raw = prefs(context).getString(KEY_MODE, null) ?: return KeepScreenAwakeMode.OFF
        return KeepScreenAwakeMode.entries.firstOrNull { it.name == raw } ?: KeepScreenAwakeMode.OFF
    }

    fun saveMode(context: Context, mode: KeepScreenAwakeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun clampCustomMs(ms: Long): Long = ms.coerceIn(MIN_CUSTOM_MS, MAX_CUSTOM_MS)

    fun customDurationMs(context: Context): Long =
        clampCustomMs(prefs(context).getLong(KEY_CUSTOM_MS, DEFAULT_CUSTOM_MS))

    fun saveCustomDurationMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_CUSTOM_MS, clampCustomMs(ms)).apply()
    }

    /** `"5 min"`, `"1 hr"`, `"1 hr 30 min"`. Sub-minute remainders are dropped. */
    fun formatDuration(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0L) / 60_000L)
        val hours = totalMin / 60L
        val minutes = totalMin % 60L
        return when {
            hours == 0L -> "$minutes min"
            minutes == 0L -> "$hours hr"
            else -> "$hours hr $minutes min"
        }
    }

    /** Hours/minutes picker values → clamped milliseconds. */
    fun pickerToMs(hours: Int, minutes: Int): Long =
        clampCustomMs(hours.toLong() * 3_600_000L + minutes.toLong() * 60_000L)
}
