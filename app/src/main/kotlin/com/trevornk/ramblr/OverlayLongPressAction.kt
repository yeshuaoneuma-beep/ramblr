package com.trevornk.ramblr

/**
 * What a long-press on the overlay ring should do (#53, Phase 2C):
 *   RECORDING                          -> CANCEL_RECORDING (3-second continuous hold to discard,
 *                                         unless [cancelAndUndoEnabled] is false)
 *   TRANSCRIBING                       -> CANCEL_TRANSCRIPTION (pre-existing, #20)
 *   IDLE, hasPendingInjection = true   -> UNDO_INJECTION (pre-existing, #27)
 *   IDLE, hasPendingInjection = false  -> SHOW_STYLE_MENU (pre-existing, #53)
 */
enum class OverlayLongPressAction { CANCEL_RECORDING, CANCEL_TRANSCRIPTION, UNDO_INJECTION, SHOW_STYLE_MENU, NONE }

/**
 * Pure decision, no Android dependencies: given the current recording state and whether an
 * undoable injection (#27) is pending, what should a long-press on the overlay do?
 *
 * With the "Long-press cancels and undoes" setting off ([cancelAndUndoEnabled] = false, #361) the
 * destructive actions are removed: RECORDING -> NONE, TRANSCRIBING -> NONE (so the long-press is
 * not even armed), and IDLE -> SHOW_STYLE_MENU regardless of [hasPendingInjection].
 */
fun overlayLongPressActionFor(
    state: RecordingStateMachine.State,
    hasPendingInjection: Boolean,
    cancelAndUndoEnabled: Boolean = true,
    idleQuickMenuEnabled: Boolean = true,
): OverlayLongPressAction = when (state) {
    RecordingStateMachine.State.RECORDING ->
        if (cancelAndUndoEnabled) OverlayLongPressAction.CANCEL_RECORDING else OverlayLongPressAction.NONE
    RecordingStateMachine.State.TRANSCRIBING ->
        if (cancelAndUndoEnabled) OverlayLongPressAction.CANCEL_TRANSCRIPTION else OverlayLongPressAction.NONE
    RecordingStateMachine.State.IDLE -> when {
        hasPendingInjection && cancelAndUndoEnabled -> OverlayLongPressAction.UNDO_INJECTION
        idleQuickMenuEnabled -> OverlayLongPressAction.SHOW_STYLE_MENU
        else -> OverlayLongPressAction.NONE
    }
}

/** Hold duration required before [action] executes. Both [OverlayLongPressAction.CANCEL_RECORDING]
 *  and [OverlayLongPressAction.CANCEL_TRANSCRIPTION] require a deliberate 3-second continuous hold;
 *  all other actions use the normal long-press timeout. */
fun overlayLongPressTimeoutMs(
    action: OverlayLongPressAction,
    systemLongPressMs: Int,
): Long = when (action) {
    OverlayLongPressAction.CANCEL_RECORDING,
    OverlayLongPressAction.CANCEL_TRANSCRIPTION -> RingGestureTracker.recordingCancelHoldMs(systemLongPressMs)
    else -> RingGestureTracker.longPressTimeoutMs(systemLongPressMs)
}

/** Delay before showing staged 3-second hold-to-cancel feedback, or null for standard single-stage holds. */
fun overlayHoldFeedbackDelayMs(
    action: OverlayLongPressAction,
    systemLongPressMs: Int,
): Long? = when (action) {
    OverlayLongPressAction.CANCEL_RECORDING,
    OverlayLongPressAction.CANCEL_TRANSCRIPTION -> RingGestureTracker.longPressTimeoutMs(systemLongPressMs)
    else -> null
}

// Whether a fired long-press may actually take effect given finger movement (#57) is now decided by
// RingGestureTracker (#322): drag is sticky and wins over a long-press that has not fired yet, and a
// long-press that already fired is only converted to a drag past a larger slop.
