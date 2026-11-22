package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLongPressActionTest {

    @Test fun `transcribing always cancels, regardless of pending injection`() {
        assertEquals(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            overlayLongPressActionFor(RecordingStateMachine.State.TRANSCRIBING, hasPendingInjection = false)
        )
        assertEquals(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            overlayLongPressActionFor(RecordingStateMachine.State.TRANSCRIBING, hasPendingInjection = true)
        )
    }

    @Test fun `idle with a pending injection undoes it`() {
        assertEquals(
            OverlayLongPressAction.UNDO_INJECTION,
            overlayLongPressActionFor(RecordingStateMachine.State.IDLE, hasPendingInjection = true)
        )
    }

    @Test fun `idle with nothing pending opens the style menu`() {
        assertEquals(
            OverlayLongPressAction.SHOW_STYLE_MENU,
            overlayLongPressActionFor(RecordingStateMachine.State.IDLE, hasPendingInjection = false)
        )
    }

    @Test fun `recording gets cancel-recording when enabled, none when disabled`() {
        assertEquals(
            OverlayLongPressAction.CANCEL_RECORDING,
            overlayLongPressActionFor(RecordingStateMachine.State.RECORDING, hasPendingInjection = false)
        )
        assertEquals(
            OverlayLongPressAction.CANCEL_RECORDING,
            overlayLongPressActionFor(RecordingStateMachine.State.RECORDING, hasPendingInjection = true)
        )
        assertEquals(
            OverlayLongPressAction.NONE,
            overlayLongPressActionFor(
                RecordingStateMachine.State.RECORDING,
                hasPendingInjection = false,
                cancelAndUndoEnabled = false,
            )
        )
        assertEquals(
            OverlayLongPressAction.NONE,
            overlayLongPressActionFor(
                RecordingStateMachine.State.RECORDING,
                hasPendingInjection = true,
                cancelAndUndoEnabled = false,
            )
        )
    }

    // #361 + Phase 2C: the full state x pending-injection x setting matrix. With the setting on
    // (the default) RECORDING maps to CANCEL_RECORDING (3-second continuous hold); with it off
    // all destructive actions (RECORDING discard, TRANSCRIBING cancel, IDLE undo) disappear.
    @Test fun `full state by setting matrix`() {
        data class Case(
            val state: RecordingStateMachine.State,
            val pending: Boolean,
            val enabled: Boolean,
            val expected: OverlayLongPressAction,
        )
        val transcribing = RecordingStateMachine.State.TRANSCRIBING
        val idle = RecordingStateMachine.State.IDLE
        val recording = RecordingStateMachine.State.RECORDING
        val cases = listOf(
            Case(transcribing, pending = false, enabled = true, expected = OverlayLongPressAction.CANCEL_TRANSCRIPTION),
            Case(transcribing, pending = true, enabled = true, expected = OverlayLongPressAction.CANCEL_TRANSCRIPTION),
            Case(transcribing, pending = false, enabled = false, expected = OverlayLongPressAction.NONE),
            Case(transcribing, pending = true, enabled = false, expected = OverlayLongPressAction.NONE),
            Case(idle, pending = true, enabled = true, expected = OverlayLongPressAction.UNDO_INJECTION),
            Case(idle, pending = false, enabled = true, expected = OverlayLongPressAction.SHOW_STYLE_MENU),
            Case(idle, pending = true, enabled = false, expected = OverlayLongPressAction.SHOW_STYLE_MENU),
            Case(idle, pending = false, enabled = false, expected = OverlayLongPressAction.SHOW_STYLE_MENU),
            Case(recording, pending = false, enabled = true, expected = OverlayLongPressAction.CANCEL_RECORDING),
            Case(recording, pending = true, enabled = true, expected = OverlayLongPressAction.CANCEL_RECORDING),
            Case(recording, pending = false, enabled = false, expected = OverlayLongPressAction.NONE),
            Case(recording, pending = true, enabled = false, expected = OverlayLongPressAction.NONE),
        )
        for (c in cases) {
            assertEquals(
                "state=${c.state} pending=${c.pending} enabled=${c.enabled}",
                c.expected,
                overlayLongPressActionFor(c.state, hasPendingInjection = c.pending, cancelAndUndoEnabled = c.enabled)
            )
        }
    }

    @Test fun `setting defaults to enabled when omitted`() {
        assertEquals(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            overlayLongPressActionFor(RecordingStateMachine.State.TRANSCRIBING, hasPendingInjection = false)
        )
        assertEquals(
            OverlayLongPressAction.CANCEL_RECORDING,
            overlayLongPressActionFor(RecordingStateMachine.State.RECORDING, hasPendingInjection = false)
        )
    }

    @Test fun `cancel-recording uses 3-second floor while other actions use normal long-press timeout`() {
        assertEquals(3_000L, overlayLongPressTimeoutMs(OverlayLongPressAction.CANCEL_RECORDING, 400))
        assertEquals(3_000L, overlayLongPressTimeoutMs(OverlayLongPressAction.CANCEL_RECORDING, 1_500))
        assertEquals(4_000L, overlayLongPressTimeoutMs(OverlayLongPressAction.CANCEL_RECORDING, 4_000))

        for (action in listOf(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            OverlayLongPressAction.UNDO_INJECTION,
            OverlayLongPressAction.SHOW_STYLE_MENU,
            OverlayLongPressAction.NONE,
        )) {
            assertEquals("$action @ 400ms", 500L, overlayLongPressTimeoutMs(action, 400))
            assertEquals("$action @ 1500ms", 1_500L, overlayLongPressTimeoutMs(action, 1_500))
        }
    }

    @Test fun `only cancel-recording arms staged hold feedback`() {
        assertEquals(500L, overlayHoldFeedbackDelayMs(OverlayLongPressAction.CANCEL_RECORDING, 400))
        assertEquals(1_500L, overlayHoldFeedbackDelayMs(OverlayLongPressAction.CANCEL_RECORDING, 1_500))

        for (action in listOf(
            OverlayLongPressAction.CANCEL_TRANSCRIPTION,
            OverlayLongPressAction.UNDO_INJECTION,
            OverlayLongPressAction.SHOW_STYLE_MENU,
            OverlayLongPressAction.NONE,
        )) {
            assertEquals("$action must not arm staged feedback", null, overlayHoldFeedbackDelayMs(action, 400))
        }
    }
}
