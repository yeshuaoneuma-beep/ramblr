package com.trevornk.ramblr

import java.util.Locale

/** Display states for the Phase 3B.2/3B.3 floating Live Transcript Window. */
enum class LiveWindowDisplayMode {
    HIDDEN,
    EXPANDED,
    COLLAPSED,
    COUNTDOWN_PEEK,
}

/** Active dictation/reconciliation phase shown inside the Live Transcript Window. */
enum class LiveWindowPhase {
    INACTIVE,
    LIVE_CONNECTING,
    LIVE_STREAMING,
    LIVE_DISCONNECTED_RECORDING,
    LIVE_PAUSED_CONTINUE,
    RECONCILIATION_WAITING_BATCH,
    RECONCILIATION_BATCH_READY,
}

/** Where disconnect/fallback/stage-status alerts should render given the Live Window state. */
enum class LiveWindowAlertRoute {
    INLINE_LIVE_WINDOW,
    BUBBLE_STATUS_PILL,
}

/**
 * Pure JVM-testable state machine for the floating Live Transcript Window, its collapse/reopen
 * cycle, smart countdown-only peek (ZERO transcript words shown in [LiveWindowDisplayMode.COUNTDOWN_PEEK]),
 * contextual alert routing, `Continue Live` pause/resume state, and keyguard/AOD visibility gating.
 */
class LiveWindowStateMachine {

    var sessionActive: Boolean = false
        private set
    var phase: LiveWindowPhase = LiveWindowPhase.INACTIVE
        private set
    var rawMode: LiveWindowDisplayMode = LiveWindowDisplayMode.HIDDEN
        private set
    var transcript: String = ""
        private set
    var batchCandidateText: String? = null
        private set
    var inlineStatusText: String? = null
        private set
    var remainingCountdownMs: Long? = null
        private set
    var cutoffRemainingSec: Int? = null
        private set
    var queuedUseBatch: Boolean = false
        private set
    var recoveryMode: LiveRecoveryMode = LiveRecoveryMode.PARALLEL_BATCH_FALLBACK
        private set
    var postCleanupEnabled: Boolean = false
        private set

    fun effectiveMode(overlayAllowed: Boolean): LiveWindowDisplayMode =
        if (!overlayAllowed || !sessionActive) LiveWindowDisplayMode.HIDDEN else rawMode

    fun alertRoute(overlayAllowed: Boolean): LiveWindowAlertRoute =
        if (effectiveMode(overlayAllowed) == LiveWindowDisplayMode.EXPANDED) {
            LiveWindowAlertRoute.INLINE_LIVE_WINDOW
        } else {
            LiveWindowAlertRoute.BUBBLE_STATUS_PILL
        }

    /** Transcript body & footer are visible ONLY in [LiveWindowDisplayMode.EXPANDED]. */
    fun showsTranscriptBody(overlayAllowed: Boolean): Boolean =
        effectiveMode(overlayAllowed) == LiveWindowDisplayMode.EXPANDED

    /** Compact header-only countdown peek shows status + "Show live" + ✕, with ZERO transcript words. */
    fun showsCountdownPeekHeaderOnly(overlayAllowed: Boolean): Boolean =
        effectiveMode(overlayAllowed) == LiveWindowDisplayMode.COUNTDOWN_PEEK

    /** Reopen chip beside the active bubble shows only while collapsed during active or paused Live. */
    fun showsReopenChip(overlayAllowed: Boolean): Boolean =
        effectiveMode(overlayAllowed) == LiveWindowDisplayMode.COLLAPSED &&
            (phase == LiveWindowPhase.LIVE_CONNECTING ||
                phase == LiveWindowPhase.LIVE_STREAMING ||
                phase == LiveWindowPhase.LIVE_DISCONNECTED_RECORDING ||
                phase == LiveWindowPhase.LIVE_PAUSED_CONTINUE)

    fun isReconciliationActive(): Boolean =
        phase == LiveWindowPhase.RECONCILIATION_WAITING_BATCH || phase == LiveWindowPhase.RECONCILIATION_BATCH_READY

    fun onLiveConnecting(
        windowEnabled: Boolean,
        overlayAllowed: Boolean,
        recoveryMode: LiveRecoveryMode = LiveRecoveryMode.PARALLEL_BATCH_FALLBACK,
        postCleanupEnabled: Boolean = false,
        preserveTranscript: Boolean = false,
    ): LiveWindowDisplayMode {
        if (!preserveTranscript) {
            transcript = ""
        }
        batchCandidateText = null
        inlineStatusText = null
        remainingCountdownMs = null
        cutoffRemainingSec = null
        queuedUseBatch = false
        this.recoveryMode = recoveryMode
        this.postCleanupEnabled = postCleanupEnabled
        if (!windowEnabled) {
            sessionActive = false
            phase = LiveWindowPhase.INACTIVE
            rawMode = LiveWindowDisplayMode.HIDDEN
            return LiveWindowDisplayMode.HIDDEN
        }
        sessionActive = true
        phase = LiveWindowPhase.LIVE_CONNECTING
        if (!preserveTranscript || rawMode == LiveWindowDisplayMode.HIDDEN) {
            rawMode = LiveWindowDisplayMode.EXPANDED
        }
        return effectiveMode(overlayAllowed)
    }

    fun onLiveStarted(
        windowEnabled: Boolean,
        overlayAllowed: Boolean,
        recoveryMode: LiveRecoveryMode = this.recoveryMode,
        postCleanupEnabled: Boolean = this.postCleanupEnabled,
    ): LiveWindowDisplayMode {
        this.recoveryMode = recoveryMode
        this.postCleanupEnabled = postCleanupEnabled
        val transitioningFromConnecting = sessionActive && phase == LiveWindowPhase.LIVE_CONNECTING
        if (!transitioningFromConnecting) {
            transcript = ""
            batchCandidateText = null
            inlineStatusText = null
            remainingCountdownMs = null
            cutoffRemainingSec = null
            queuedUseBatch = false
        } else {
            inlineStatusText = null
        }
        if (!windowEnabled) {
            sessionActive = false
            phase = LiveWindowPhase.INACTIVE
            rawMode = LiveWindowDisplayMode.HIDDEN
            return LiveWindowDisplayMode.HIDDEN
        }
        sessionActive = true
        phase = LiveWindowPhase.LIVE_STREAMING
        if (!transitioningFromConnecting || rawMode == LiveWindowDisplayMode.HIDDEN) {
            rawMode = LiveWindowDisplayMode.EXPANDED
        }
        return effectiveMode(overlayAllowed)
    }

    fun onLiveContinued(
        preservedPrefix: String,
        windowEnabled: Boolean,
        overlayAllowed: Boolean,
        postCleanupEnabled: Boolean = this.postCleanupEnabled,
    ): LiveWindowDisplayMode {
        transcript = preservedPrefix
        batchCandidateText = null
        inlineStatusText = null
        remainingCountdownMs = null
        cutoffRemainingSec = null
        queuedUseBatch = false
        this.recoveryMode = LiveRecoveryMode.CONTINUE_LIVE_SESSION
        this.postCleanupEnabled = postCleanupEnabled
        if (!windowEnabled) {
            sessionActive = false
            phase = LiveWindowPhase.INACTIVE
            rawMode = LiveWindowDisplayMode.HIDDEN
            return LiveWindowDisplayMode.HIDDEN
        }
        sessionActive = true
        phase = LiveWindowPhase.LIVE_CONNECTING
        if (rawMode == LiveWindowDisplayMode.HIDDEN || rawMode == LiveWindowDisplayMode.COUNTDOWN_PEEK) {
            rawMode = LiveWindowDisplayMode.EXPANDED
        }
        return effectiveMode(overlayAllowed)
    }

    fun onLivePausedForContinue(
        accumulatedLiveText: String,
        inlineBanner: String,
        postCleanupEnabled: Boolean,
        windowEnabled: Boolean,
        overlayAllowed: Boolean,
    ): LiveWindowDisplayMode {
        transcript = accumulatedLiveText
        batchCandidateText = null
        remainingCountdownMs = null
        cutoffRemainingSec = null
        queuedUseBatch = false
        this.recoveryMode = LiveRecoveryMode.CONTINUE_LIVE_SESSION
        this.postCleanupEnabled = postCleanupEnabled
        inlineStatusText = inlineBanner
        if (!windowEnabled) {
            sessionActive = false
            phase = LiveWindowPhase.INACTIVE
            rawMode = LiveWindowDisplayMode.HIDDEN
            return LiveWindowDisplayMode.HIDDEN
        }
        sessionActive = true
        phase = LiveWindowPhase.LIVE_PAUSED_CONTINUE
        rawMode = when (rawMode) {
            LiveWindowDisplayMode.COLLAPSED,
            LiveWindowDisplayMode.COUNTDOWN_PEEK -> LiveWindowDisplayMode.COLLAPSED
            LiveWindowDisplayMode.EXPANDED,
            LiveWindowDisplayMode.HIDDEN -> LiveWindowDisplayMode.EXPANDED
        }
        return effectiveMode(overlayAllowed)
    }

    /**
     * Always updates the underlying transcript buffer, even while collapsed or peeking.
     * Guards against a tiny transient fragment (< 35% of current length) wiping an established
     * transcript (>= 8 words) when it does not begin a revision of the visible text.
     */
    fun onInterim(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = transcript.trim()
        if (current.isNotEmpty()) {
            val currentWords = current.split(Regex("\\s+")).size
            if (currentWords >= 8 && trimmed.length < (current.length * 0.35).toInt()) {
                val headLen = minOf(trimmed.length, current.length, 12)
                val beginsRevision = current.lowercase().startsWith(trimmed.lowercase().take(headLen)) ||
                    trimmed.lowercase().startsWith(current.lowercase().take(headLen))
                if (!beginsRevision) return
            }
        }
        transcript = trimmed
    }

    /** Collapses without stopping recording (`EXPANDED` / `COUNTDOWN_PEEK` -> `COLLAPSED`). */
    fun onCollapseTapped(overlayAllowed: Boolean): LiveWindowDisplayMode {
        if (!sessionActive) return LiveWindowDisplayMode.HIDDEN
        if (rawMode == LiveWindowDisplayMode.EXPANDED || rawMode == LiveWindowDisplayMode.COUNTDOWN_PEEK) {
            rawMode = LiveWindowDisplayMode.COLLAPSED
        }
        return effectiveMode(overlayAllowed)
    }

    /** Reopens from `COLLAPSED` or `COUNTDOWN_PEEK` back to `EXPANDED`. */
    fun onReopenTapped(overlayAllowed: Boolean): LiveWindowDisplayMode {
        if (!sessionActive) return LiveWindowDisplayMode.HIDDEN
        if (rawMode == LiveWindowDisplayMode.COLLAPSED || rawMode == LiveWindowDisplayMode.COUNTDOWN_PEEK) {
            rawMode = LiveWindowDisplayMode.EXPANDED
        }
        return effectiveMode(overlayAllowed)
    }

    /** 7:00->10:00 countdown tick: promotes `COLLAPSED` -> `COUNTDOWN_PEEK` (header-only, zero words). */
    fun onRecordingCountdown(remainingMs: Long, overlayAllowed: Boolean): LiveWindowDisplayMode {
        remainingCountdownMs = remainingMs
        if (sessionActive && rawMode == LiveWindowDisplayMode.COLLAPSED) {
            rawMode = LiveWindowDisplayMode.COUNTDOWN_PEEK
        }
        return effectiveMode(overlayAllowed)
    }

    /** 8:00->9:00 cutoff approach (final 60s) or 9:00 cutoff trigger: promotes `COLLAPSED` -> `COUNTDOWN_PEEK`. */
    fun onCutoffApproachOrTrigger(remainingCutoffSec: Int?, overlayAllowed: Boolean): LiveWindowDisplayMode {
        cutoffRemainingSec = remainingCutoffSec
        if (sessionActive && rawMode == LiveWindowDisplayMode.COLLAPSED) {
            rawMode = LiveWindowDisplayMode.COUNTDOWN_PEEK
        }
        return effectiveMode(overlayAllowed)
    }

    fun onLiveDisconnected(
        isSessionLimit: Boolean,
        inlineBanner: String = "Live paused \u00b7 recording continues",
        overlayAllowed: Boolean,
    ): LiveWindowDisplayMode {
        if (!sessionActive) return LiveWindowDisplayMode.HIDDEN
        phase = LiveWindowPhase.LIVE_DISCONNECTED_RECORDING
        cutoffRemainingSec = null
        inlineStatusText = inlineBanner
        if (isSessionLimit && rawMode == LiveWindowDisplayMode.COLLAPSED) {
            rawMode = LiveWindowDisplayMode.COUNTDOWN_PEEK
        }
        return effectiveMode(overlayAllowed)
    }

    fun onInlineStageStatus(statusText: String) {
        if (!sessionActive) return
        inlineStatusText = statusText
    }

    fun onReconciliationReady(
        liveText: String,
        batchPending: Boolean,
        windowEnabled: Boolean,
        overlayAllowed: Boolean,
    ): LiveWindowDisplayMode {
        transcript = liveText
        batchCandidateText = null
        queuedUseBatch = false
        remainingCountdownMs = null
        cutoffRemainingSec = null
        inlineStatusText = if (batchPending) {
            "Live stopped \u00b7 preparing Batch"
        } else {
            "Choose transcript to insert"
        }
        if (!windowEnabled) {
            sessionActive = false
            phase = LiveWindowPhase.INACTIVE
            rawMode = LiveWindowDisplayMode.HIDDEN
            return LiveWindowDisplayMode.HIDDEN
        }
        sessionActive = true
        phase = if (batchPending) {
            LiveWindowPhase.RECONCILIATION_WAITING_BATCH
        } else {
            LiveWindowPhase.RECONCILIATION_BATCH_READY
        }
        rawMode = LiveWindowDisplayMode.EXPANDED
        return effectiveMode(overlayAllowed)
    }

    fun onReconciliationBatchReady(liveText: String, batchText: String) {
        transcript = liveText
        batchCandidateText = batchText
        phase = LiveWindowPhase.RECONCILIATION_BATCH_READY
        inlineStatusText = "Batch transcript ready"
    }

    fun markQueuedUseBatch() {
        if (phase == LiveWindowPhase.RECONCILIATION_WAITING_BATCH) {
            queuedUseBatch = true
            inlineStatusText = "Will insert Batch when ready\u2026"
        }
    }

    fun onReset() {
        sessionActive = false
        phase = LiveWindowPhase.INACTIVE
        rawMode = LiveWindowDisplayMode.HIDDEN
        transcript = ""
        batchCandidateText = null
        inlineStatusText = null
        remainingCountdownMs = null
        cutoffRemainingSec = null
        queuedUseBatch = false
        recoveryMode = LiveRecoveryMode.PARALLEL_BATCH_FALLBACK
        postCleanupEnabled = false
    }

    fun formatHeaderStatus(elapsedSec: Int): String {
        val safeSec = elapsedSec.coerceAtLeast(0)
        return when (phase) {
            LiveWindowPhase.LIVE_CONNECTING -> "LIVE \u00b7 Connecting\u2026"
            LiveWindowPhase.LIVE_STREAMING -> {
                val base = "LIVE \u00b7 ${formatMmSs(safeSec)}"
                val cutoffSec = cutoffRemainingSec
                val countdown = remainingCountdownMs
                when {
                    cutoffSec != null && cutoffSec in 1..60 -> "$base \u00b7 ${formatMmSs(cutoffSec)} to cutoff"
                    countdown != null -> "$base \u00b7 ${RecordingCountdown.formatRemaining(countdown)} left"
                    else -> base
                }
            }
            LiveWindowPhase.LIVE_DISCONNECTED_RECORDING -> {
                val base = "REC \u00b7 ${formatMmSs(safeSec)}"
                val countdown = remainingCountdownMs
                if (countdown != null) "$base \u00b7 ${RecordingCountdown.formatRemaining(countdown)} left" else base
            }
            LiveWindowPhase.LIVE_PAUSED_CONTINUE -> "LIVE \u00b7 Paused"
            LiveWindowPhase.RECONCILIATION_WAITING_BATCH -> "LIVE \u00b7 Preparing Batch"
            LiveWindowPhase.RECONCILIATION_BATCH_READY -> "LIVE \u00b7 Choose transcript"
            LiveWindowPhase.INACTIVE -> "LIVE \u00b7 0:00"
        }
    }

    companion object {
        fun formatMmSs(totalSeconds: Int): String {
            val safe = totalSeconds.coerceAtLeast(0)
            val m = safe / 60
            val s = safe % 60
            return String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}

/**
 * Pure anchor calculation for the floating Live Transcript Window: opens above the bubble when the
 * bubble is in the lower half of the screen, or below the bubble when in the upper half, avoiding
 * bubble overlap and clamping inside the usable screen rect.
 */
internal fun liveWindowPosition(
    bubbleX: Int,
    bubbleY: Int,
    bubbleWidth: Int,
    bubbleHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    screenLeft: Int,
    screenW: Int,
    screenH: Int,
    margin: Int,
    gap: Int,
): Pair<Int, Int> {
    val maxX = (screenW - windowWidth - margin).coerceAtLeast(screenLeft + margin)
    val bubbleCenterX = bubbleX + bubbleWidth / 2
    val idealX = if (bubbleCenterX > screenW / 2) {
        bubbleX + bubbleWidth - windowWidth
    } else {
        bubbleX
    }
    val x = idealX.coerceIn(screenLeft + margin, maxX)

    val bubbleCenterY = bubbleY + bubbleHeight / 2
    val openUpward = bubbleCenterY > screenH / 2
    val maxY = (screenH - windowHeight - margin).coerceAtLeast(margin)
    val y = if (openUpward) {
        (bubbleY - windowHeight - gap).coerceIn(margin, maxY)
    } else {
        (bubbleY + bubbleHeight + gap).coerceIn(margin, maxY)
    }
    return x to y
}
