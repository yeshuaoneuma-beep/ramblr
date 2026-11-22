package com.trevornk.ramblr

import android.os.Build
import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `startActivityAndCollapse` overload split and pure Quick Settings tile routing /
 * display-state decisions. Both overloads throw outside their own API window, and `minSdk 30`
 * straddles the boundary, so an off-by-one here is a hard crash on real devices rather than a
 * degraded affordance. See [shouldUsePendingIntentCollapse].
 */
class QsTileCollapseCompatTest {

    @Test fun `API 34 takes the PendingIntent overload -- the version that added it`() {
        assertTrue(shouldUsePendingIntentCollapse(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    @Test fun `API 33 takes the Intent overload -- PendingIntent does not exist yet`() {
        assertFalse(shouldUsePendingIntentCollapse(Build.VERSION_CODES.TIRAMISU))
    }

    /**
     * The regression #136 actually shipped: minSdk is 30, and the unguarded PendingIntent call
     * threw NoSuchMethodError on every device in the 30..33 band.
     */
    @Test fun `every API level from minSdk 30 up to 33 uses the Intent overload`() {
        for (sdk in 30..33) {
            assertFalse("API $sdk must not call the API-34-only PendingIntent overload", shouldUsePendingIntentCollapse(sdk))
        }
    }

    @Test fun `API 34 and above always use the PendingIntent overload`() {
        for (sdk in 34..36) {
            assertTrue("API $sdk must not call the Intent overload, which throws from 34", shouldUsePendingIntentCollapse(sdk))
        }
    }

    @Test fun `click routing maps each recording state cleanly`() {
        assertEquals(QsTileClickAction.OPEN_ACCESSIBILITY_SETTINGS, resolveQsTileClickAction(null))
        assertEquals(QsTileClickAction.TOGGLE_DICTATION_AND_COLLAPSE, resolveQsTileClickAction(RecordingStateMachine.State.IDLE))
        assertEquals(QsTileClickAction.TOGGLE_DICTATION_AND_COLLAPSE, resolveQsTileClickAction(RecordingStateMachine.State.RECORDING))
        assertEquals(QsTileClickAction.CANCEL_TRANSCRIPTION_AND_COLLAPSE, resolveQsTileClickAction(RecordingStateMachine.State.TRANSCRIBING))
    }

    @Test fun `display state maps every service and recording state`() {
        val disabled = resolveQsTileDisplayState(state = null, serviceTurnedOff = false)
        assertEquals(Tile.STATE_INACTIVE, disabled.tileState)
        assertEquals(R.string.tile_subtitle_service_disabled, disabled.subtitleRes)

        val turnedOff = resolveQsTileDisplayState(state = null, serviceTurnedOff = true)
        assertEquals(Tile.STATE_INACTIVE, turnedOff.tileState)
        assertEquals(R.string.tile_subtitle_service_turned_off, turnedOff.subtitleRes)

        val recording = resolveQsTileDisplayState(state = RecordingStateMachine.State.RECORDING, serviceTurnedOff = false)
        assertEquals(Tile.STATE_ACTIVE, recording.tileState)
        assertEquals(R.string.tile_subtitle_recording, recording.subtitleRes)

        val transcribing = resolveQsTileDisplayState(state = RecordingStateMachine.State.TRANSCRIBING, serviceTurnedOff = false)
        assertEquals(Tile.STATE_ACTIVE, transcribing.tileState)
        assertEquals(R.string.tile_subtitle_transcribing, transcribing.subtitleRes)

        val idle = resolveQsTileDisplayState(state = RecordingStateMachine.State.IDLE, serviceTurnedOff = false)
        assertEquals(Tile.STATE_INACTIVE, idle.tileState)
        assertNull(idle.subtitleRes)
    }
}
