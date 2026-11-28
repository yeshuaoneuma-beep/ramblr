package com.trevornk.ramblr

import android.content.Context
import android.media.AudioAttributes
import android.app.NotificationManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View

/** What the device's vibrator can do right now (#385). */
data class HapticCapabilities(val hasAmplitudeControl: Boolean, val predefinedSupported: Boolean)

enum class PredefinedHaptic { TICK, CLICK, HEAVY_CLICK }

/** Framework-free description of one vibration; [AndroidHapticOutput] turns it into a [VibrationEffect]. */
sealed class HapticEffectSpec {
    data class OneShot(val durationMs: Long, val amplitude: Int) : HapticEffectSpec()
    data class Predefined(val kind: PredefinedHaptic) : HapticEffectSpec()
    data class Duration(val durationMs: Long) : HapticEffectSpec()
    data class Waveform(val timings: List<Long>, val amplitudes: List<Int>?) : HapticEffectSpec()
}

/** Pure settings -> effect mapping. */
object HapticEffectPlanner {
    private fun amplitude(s: HapticStrength) = when (s) {
        HapticStrength.LIGHT -> 64
        HapticStrength.MEDIUM -> 128
        HapticStrength.STRONG -> 255
    }

    /**
     * Amplitude control -> one-shot with the user's length and a strength-mapped amplitude.
     * Otherwise a predefined tick/click/heavy-click (fixed length, strength picks the effect),
     * and only if even those are unsupported a plain fixed-amplitude duration.
     */
    fun plan(settings: HapticSettings, caps: HapticCapabilities): HapticEffectSpec {
        val length = HapticPrefs.clampLengthMs(settings.lengthMs).toLong()
        return when {
            caps.hasAmplitudeControl -> HapticEffectSpec.OneShot(length, amplitude(settings.strength))
            caps.predefinedSupported -> HapticEffectSpec.Predefined(
                when (settings.strength) {
                    HapticStrength.LIGHT -> PredefinedHaptic.TICK
                    HapticStrength.MEDIUM -> PredefinedHaptic.CLICK
                    HapticStrength.STRONG -> PredefinedHaptic.HEAVY_CLICK
                }
            )
            else -> HapticEffectSpec.Duration(length)
        }
    }

    fun planUiTap(strength: HapticStrength, caps: HapticCapabilities): HapticEffectSpec {
        val (durationMs, amplitude) = when (strength) {
            HapticStrength.LIGHT -> 10L to 70
            HapticStrength.MEDIUM -> 18L to 155
            HapticStrength.STRONG -> 28L to 255
        }
        return if (caps.hasAmplitudeControl) {
            HapticEffectSpec.OneShot(durationMs, amplitude)
        } else {
            HapticEffectSpec.Duration(durationMs)
        }
    }

    fun planLiveDisconnect(strength: HapticStrength, caps: HapticCapabilities): HapticEffectSpec {
        val (timings, amplitudes) = when (strength) {
            HapticStrength.LIGHT -> listOf(0L, 35L, 65L, 35L) to listOf(0, 90, 0, 90)
            HapticStrength.MEDIUM -> listOf(0L, 55L, 70L, 55L) to listOf(0, 175, 0, 175)
            HapticStrength.STRONG -> listOf(0L, 80L, 75L, 80L) to listOf(0, 255, 0, 255)
        }
        return if (caps.hasAmplitudeControl) {
            HapticEffectSpec.Waveform(timings, amplitudes)
        } else {
            HapticEffectSpec.Waveform(timings, null)
        }
    }
}

/** The platform seam. Implementations must not block. */
interface HapticOutput {
    /** Null when vibrating is not currently appropriate (no vibrator, or DND total silence/alarms only).
     *  Ringer mode is deliberately NOT checked: on Pixels an active DND/Bedtime rule reports
     *  RINGER_MODE_SILENT even though touch-usage vibration is allowed, and the USAGE_TOUCH tag
     *  already makes the system apply the user's touch-feedback setting. */
    fun capabilities(): HapticCapabilities?
    fun play(spec: HapticEffectSpec)
}

/** What [DictationRuntime] calls; the seam tests substitute. */
interface HapticFeedback {
    fun onRecordingStarted()
    fun onRecordingStopped()

    /** Subtle attention vibration when Cloud Live drops mid-recording while local capture continues. */
    fun onLiveDisconnected() {}

    /** Subtle double-tick confirmation when dictated text is successfully inserted. */
    fun onTextInserted() {}

    /** Firm confirmation thud when a 3-second hold-to-cancel/discard completes. */
    fun onCancelDiscard() {}

    /** Forget any pending start tick; called when the runtime goes idle or shuts down. */
    fun reset()

    object None : HapticFeedback {
        override fun onRecordingStarted() {}
        override fun onRecordingStopped() {}
        override fun reset() {}
    }
}

/**
 * Start/stop haptic decision (#385). Fire-and-forget: [Vibrator.vibrate] is asynchronous, and any
 * failure is logged and swallowed so haptics can never affect dictation.
 *
 * Contract with [DictationRuntime]:
 *  - [onRecordingStarted] is called only after the engine has actually started recording.
 *  - [onRecordingStopped] is called for every real end of a *started* recording that the user (or
 *    silence auto-stop / max-duration cap) caused. There is no cancel-while-recording (a
 *    long-press while recording is just a stop tap), and shutdown / keyboard dismissal
 *    mid-recording deliberately gives no tick. A recording that ends in a recorder ERROR gets no tick
 *    (it is not a deliberate stop; the user sees an error message instead).
 *  - A stop with no preceding start tick is silent, and each start yields at most one stop tick,
 *    so the pair is always balanced. Settings are re-read at each tick, so toggling takes effect
 *    immediately. Settings are also re-checked at stop: turning haptics off mid-recording
 *    suppresses the stop tick.
 */
class DictationHaptics(
    private val settings: () -> HapticSettings,
    private val output: () -> HapticOutput,
) : HapticFeedback {

    @Volatile private var startTicked = false

    override fun onRecordingStarted() {
        startTicked = tick(requireEnabled = true)
    }

    override fun onRecordingStopped() {
        if (!startTicked) return
        startTicked = false
        tick(requireEnabled = true)
    }

    override fun reset() {
        startTicked = false
    }

    override fun onLiveDisconnected() {
        try {
            val out = output()
            val caps = out.capabilities() ?: return
            out.play(HapticEffectPlanner.planLiveDisconnect(settings().liveDisconnectStrength, caps))
        } catch (e: Exception) {
            Log.w(TAG, "Live disconnect haptic failed; ignoring", e)
        }
    }

    override fun onTextInserted() {
        try {
            if (!settings().enabled) return
            val out = output()
            val caps = out.capabilities() ?: return
            val spec = if (caps.hasAmplitudeControl) {
                HapticEffectSpec.Waveform(listOf(0L, 12L, 45L, 12L), listOf(0, 72, 0, 96))
            } else {
                HapticEffectSpec.Waveform(listOf(0L, 12L, 45L, 12L), null)
            }
            out.play(spec)
        } catch (e: Exception) {
            Log.w(TAG, "Text inserted haptic failed; ignoring", e)
        }
    }

    override fun onCancelDiscard() {
        try {
            if (!settings().enabled) return
            val out = output()
            val caps = out.capabilities() ?: return
            val spec = when {
                caps.hasAmplitudeControl -> HapticEffectSpec.OneShot(45L, 220)
                caps.predefinedSupported -> HapticEffectSpec.Predefined(PredefinedHaptic.HEAVY_CLICK)
                else -> HapticEffectSpec.Duration(45L)
            }
            out.play(spec)
        } catch (e: Exception) {
            Log.w(TAG, "Cancel/discard haptic failed; ignoring", e)
        }
    }

    /** Settings "Test" button: plays with the current strength/length even when the master switch is off. */
    fun playTest() {
        tick(requireEnabled = false)
    }

    private fun tick(requireEnabled: Boolean): Boolean = try {
        val s = settings()
        if (requireEnabled && !s.enabled) {
            false
        } else {
            val out = output()
            val caps = out.capabilities()
            if (caps == null) {
                false
            } else {
                out.play(HapticEffectPlanner.plan(s, caps))
                true
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Haptic tick failed; ignoring", e)
        false
    }

    companion object {
        private const val TAG = "PhoneWhisper"

        fun forContext(context: Context): DictationHaptics {
            val app = context.applicationContext ?: context
            return DictationHaptics(
                settings = { HapticPrefs.load(app) },
                output = { AndroidHapticOutput(app) },
            )
        }
    }
}

/** Crisp, lightweight haptic tick for in-app taps, toggles, and selections. */
object UiTapHaptics {
    @Volatile internal var onTickForTest: ((View) -> Unit)? = null
    @Volatile internal var outputFactoryForTest: ((Context) -> HapticOutput)? = null

    fun tick(view: View) {
        runCatching {
            if (!HapticPrefs.isUiTapsEnabled(view.context)) return
            onTickForTest?.invoke(view)
            playStrength(view.context, HapticPrefs.uiTapStrength(view.context))
        }
    }

    fun playPreview(context: Context, strength: HapticStrength = HapticPrefs.uiTapStrength(context)) {
        runCatching {
            playStrength(context, strength)
        }
    }

    private fun playStrength(context: Context, strength: HapticStrength) {
        val app = context.applicationContext ?: context
        val out = outputFactoryForTest?.invoke(app) ?: AndroidHapticOutput(app)
        val caps = out.capabilities() ?: return
        out.play(HapticEffectPlanner.planUiTap(strength, caps))
    }
}

/**
 * Real vibrator. Skipped only when there is no vibrator or DND is total silence/alarms only.
 * Ringer mode is deliberately NOT checked: on Pixels an active DND/Bedtime rule reports
 * RINGER_MODE_SILENT even though touch-usage vibration is allowed, and the USAGE_TOUCH tag
 * already makes the system apply the user's touch-feedback setting. Tagged USAGE_TOUCH (API 33+ [VibrationAttributes]; older
 * [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION]) so the system "touch feedback" setting applies.
 * minSdk is 30, so VibrationEffect.createPredefined/createOneShot and hasAmplitudeControl need no guards.
 */
class AndroidHapticOutput(private val context: Context) : HapticOutput {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun capabilities(): HapticCapabilities? {
        val v = vibrator ?: return null
        if (!v.hasVibrator()) return null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val filter = nm?.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) return null
        val predefined = v.areAllEffectsSupported(
            VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK,
        ) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        return HapticCapabilities(v.hasAmplitudeControl(), predefined)
    }

    override fun play(spec: HapticEffectSpec) {
        val v = vibrator ?: return
        val effect = when (spec) {
            is HapticEffectSpec.OneShot -> VibrationEffect.createOneShot(spec.durationMs, spec.amplitude)
            is HapticEffectSpec.Predefined -> VibrationEffect.createPredefined(
                when (spec.kind) {
                    PredefinedHaptic.TICK -> VibrationEffect.EFFECT_TICK
                    PredefinedHaptic.CLICK -> VibrationEffect.EFFECT_CLICK
                    PredefinedHaptic.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
            )
            is HapticEffectSpec.Duration ->
                VibrationEffect.createOneShot(spec.durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            is HapticEffectSpec.Waveform -> if (spec.amplitudes != null) {
                VibrationEffect.createWaveform(spec.timings.toLongArray(), spec.amplitudes.toIntArray(), -1)
            } else {
                VibrationEffect.createWaveform(spec.timings.toLongArray(), -1)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }
}
