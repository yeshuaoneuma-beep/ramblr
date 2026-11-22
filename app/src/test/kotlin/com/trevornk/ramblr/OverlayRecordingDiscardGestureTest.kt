package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end touch tests for Phase 2C's 3-second hold-to-discard gesture on the real
 * [WhisperAccessibilityService] overlay view:
 * - Short tap while RECORDING stops and transcribes.
 * - Holding past 500ms enters discard feedback, and releasing before 3s keeps recording active.
 * - Continuous hold >= 3s discards cleanly and returns to IDLE without transcribing.
 * - Idle long-press behavior (style menu) remains unchanged (~500ms, not 3s).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayRecordingDiscardGestureTest {

    class TestService : WhisperAccessibilityService()

    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean =
            stateMachine.tryStartRecording()

        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
    }

    private lateinit var app: Application
    private lateinit var service: TestService
    private lateinit var overlay: View
    private lateinit var feedbackView: TextView

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // Enable reduced motion so startPulse()'s self-restarting ViewPropertyAnimator loop
        // does not spin Robolectric's Choreographer indefinitely during Looper.idle()/idleFor(...).
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        LongPressCancelUndoToggle.setEnabled(app, true)
        WhisperAccessibilityService.setMainActivityForeground(false)

        service = Robolectric.buildService(TestService::class.java, null).create().get()
        // Wire a real DictationRuntime with a FakeRecordingEngine so onTap()/cancelRecording()
        // exercise the full production state machine without touching AudioRecord.
        val runtimeListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }
            .get(service) as RuntimeListener
        val runtime = DictationRuntime(
            app,
            runtimeListener,
            InMemoryDictationSessionLeaseRegistry(),
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }
            .set(service, runtime)

        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }
            .invoke(service)
        overlay = WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }
            .get(service) as View
        feedbackView = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
            .apply { isAccessible = true }
            .get(service) as TextView
    }

    @After fun tearDown() {
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun dispatchTouch(action: Int, downTime: Long, eventTime: Long, x: Float = 100f, y: Float = 100f) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            overlay.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun isDiscardHoldFeedbackActive(): Boolean =
        WhisperAccessibilityService::class.java.getDeclaredField("discardHoldFeedbackActive")
            .apply { isAccessible = true }
            .getBoolean(service)

    private fun styleMenuOverlay(): View? =
        WhisperAccessibilityService::class.java.getDeclaredField("styleMenuView")
            .apply { isAccessible = true }
            .get(service) as View?

    @Test fun `release before 3 seconds during recording aborts discard and keeps recording active`() {
        service.runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())

        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        // Enter the staged discard feedback window (500ms+).
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue("discard hold feedback should be active after 500ms", isDiscardHoldFeedbackActive())
        assertTrue(
            "countdown feedback should be visible: '${feedbackView.text}'",
            feedbackView.text.toString().contains("Hold to discard"),
        )

        // Advance to 2.0s total (still < 3s) and release.
        shadowOf(Looper.getMainLooper()).idleFor(1_400, TimeUnit.MILLISECONDS)
        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 3_000L)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "releasing before 3s must keep recording active",
            RecordingStateMachine.State.RECORDING,
            service.runtime.currentState(),
        )
        assertFalse("discard hold feedback must clear on release", isDiscardHoldFeedbackActive())
    }

    @Test fun `continuous hold of 3 seconds while recording discards cleanly without starting a new recording on lift`() {
        service.runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())

        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(3_050, TimeUnit.MILLISECONDS)

        assertEquals(
            "3-second hold must discard recording and return to IDLE",
            RecordingStateMachine.State.IDLE,
            service.runtime.currentState(),
        )
        assertEquals("Recording discarded", ShadowToast.getTextOfLatestToast())
        assertFalse(isDiscardHoldFeedbackActive())

        // Lifting the finger after discard must NOT count as a tap that restarts recording.
        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 4_100L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
    }

    @Test fun `quick tap while recording still stops and enters transcribing`() {
        service.runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())

        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS)
        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 1_120L)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RecordingStateMachine.State.TRANSCRIBING, service.runtime.currentState())
    }

    @Test fun `disabling cancel-and-undo setting turns 3s recording hold back into normal stop tap`() {
        LongPressCancelUndoToggle.setEnabled(app, false)
        service.runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())

        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(3_100, TimeUnit.MILLISECONDS)
        assertEquals(
            "must still be recording when cancel-and-undo toggle is off",
            RecordingStateMachine.State.RECORDING,
            service.runtime.currentState(),
        )
        assertFalse(isDiscardHoldFeedbackActive())

        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 4_100L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, service.runtime.currentState())
    }

    @Test fun `idle long-press still opens the style menu at standard timeout rather than waiting 3s`() {
        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())

        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)

        assertTrue("idle long-press must still open style menu at ~500ms", styleMenuOverlay() != null)
        assertFalse(isDiscardHoldFeedbackActive())

        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 1_650L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
    }
}
