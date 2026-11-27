package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Composable cloud cleanup prompt modifiers stored in the shared `"ramblr"` SharedPreferences.
 *
 * Applied dynamically at prompt assembly time via [PostProcessor.withCleanupModifiers] so saved
 * user prompts are never mutated.
 */
object CleanupModifiersPrefs {
    private const val PREFS_NAME = "ramblr"
    const val KEY_SMART_SELF_CORRECTION = "cleanup_smart_self_correction"
    const val KEY_SPOKEN_EMOJI_COMMANDS = "cleanup_spoken_emoji_commands"
    const val DEFAULT_SMART_SELF_CORRECTION = true
    const val DEFAULT_SPOKEN_EMOJI_COMMANDS = false

    fun isSmartSelfCorrectionEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SMART_SELF_CORRECTION, DEFAULT_SMART_SELF_CORRECTION)

    fun isSmartSelfCorrectionEnabled(context: Context): Boolean =
        isSmartSelfCorrectionEnabled(prefs(context))

    fun setSmartSelfCorrectionEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_SELF_CORRECTION, enabled).apply()
    }

    fun setSmartSelfCorrectionEnabled(context: Context, enabled: Boolean) {
        setSmartSelfCorrectionEnabled(prefs(context), enabled)
    }

    fun isSpokenEmojiCommandsEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SPOKEN_EMOJI_COMMANDS, DEFAULT_SPOKEN_EMOJI_COMMANDS)

    fun isSpokenEmojiCommandsEnabled(context: Context): Boolean =
        isSpokenEmojiCommandsEnabled(prefs(context))

    fun setSpokenEmojiCommandsEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPOKEN_EMOJI_COMMANDS, enabled).apply()
    }

    fun setSpokenEmojiCommandsEnabled(context: Context, enabled: Boolean) {
        setSpokenEmojiCommandsEnabled(prefs(context), enabled)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
