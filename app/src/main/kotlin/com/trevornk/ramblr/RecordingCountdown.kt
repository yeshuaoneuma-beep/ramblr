package com.trevornk.ramblr

import java.util.Locale

/** Where the 7:00 recording countdown cue is surfaced on the accessibility host. */
internal enum class RecordingCountdownSurface {
    /** Unlocked and screen-on: non-touchable overlay pill (keeps ring hidden if hidden). */
    OVERLAY_PILL,

    /** Locked or screen-off: never draw over keyguard/AOD; use ongoing low-importance notification. */
    LOCKSCREEN_NOTIFICATION,
}

/** Pure formatting and surface-routing helpers for the 7:00 recording countdown. */
internal object RecordingCountdown {
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    fun accessibilitySurface(
        lockedByKeyguard: Boolean,
        screenOff: Boolean,
    ): RecordingCountdownSurface = if (feedbackBubbleAllowed(lockedByKeyguard, screenOff)) {
        RecordingCountdownSurface.OVERLAY_PILL
    } else {
        RecordingCountdownSurface.LOCKSCREEN_NOTIFICATION
    }
}
