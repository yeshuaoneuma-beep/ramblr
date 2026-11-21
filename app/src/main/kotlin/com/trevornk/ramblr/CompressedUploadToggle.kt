package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * On-by-default toggle for compressed (AAC/M4A) cloud transcription uploads (#109). When
 * enabled, [DictationRuntime.startRecording] composes an [AacEncoderSession] into
 * the same `onChunk` tee [RecordingEngine] already feeds to streaming preview and #108's
 * [SilenceAutoStopSession], so a finished `.m4a` is ready the instant recording stops. Cloud
 * transcription ([TranscriberClient]/[GeminiTranscriberClient]) then uploads that compressed
 * file instead of the raw WAV, which is roughly an order of magnitude smaller -- keeping
 * long cloud dictations within inline-audio limits and reducing mobile upload latency.
 *
 * When off, zero [AacEncoderSession] instantiation and byte-for-byte identical upload behavior
 * to raw PCM/WAV -- the `onChunk` composition in `startRecording` simply omits
 * this consumer, the same way an unconfigured [SilenceAutoStopSession] is omitted today.
 */
object CompressedUploadToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "compressed_upload_enabled"
    const val DEFAULT = true

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
