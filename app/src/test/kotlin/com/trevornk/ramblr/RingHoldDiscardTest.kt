package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Phase 2C tightening tests for 3-second hold-to-discard on [WhisperAccessibilityService]:
 * - Strict 16 dp drag slop cancels active discard hold feedback rather than widening to 32 dp.
 * - Releasing finger before 3s aborts discard cleanly and preserves recording.
 * - Full 3s hold discards recording without writing any entry to [DictationHistoryStore].
 * - Pending [holdFeedbackAction] and [longPressAction] runnables are cancelled on
 *   `onEnterTranscribingUi`, `onIdleUi`, and `onDestroy`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingHoldDiscardTest {

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

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        DictationHistoryStore.forContext(app).clear()
        LongPressCancelUndoToggle.setEnabled(app, true)
        WhisperAccessibilityService.setMainActivityForeground(false)
        ActiveDictationHostRegistry.resetForTest()

        service = Robolectric.buildService(TestService::class.java, null).create().get()
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
    }

    @After fun tearDown() {
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        ActiveDictationHostRegistry.resetForTest()
        DictationHistoryStore.forContext(app).clear()
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

    private fun styleMenuView(): View? =
        WhisperAccessibilityService::class.java.getDeclaredField("styleMenuView")
            .apply { isAccessible = true }
            .get(service) as View?

    @Test fun `moving past strict 16dp drag slop cancels hold-to-discard feedback and becomes a drag`() {
        service.runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())

        val density = app.resources.displayMetrics.density
        val scaledTouchSlop = ViewConfiguration.get(app).scaledTouchSlop
        val strictSlop = RingGestureTracker.dragSlopPx(density, scaledTouchSlop)
        val postLongPressSlop = RingGestureTracker.postLongPressSlopPx(density, scaledTouchSlop)
        assertTrue(postLongPressSlop > strictSlop)

        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L, x = 100f, y = 100f)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(isDiscardHoldFeedbackActive())

        // Move past strict 16dp slop while staying inside postLongPressSlopPx.
        val moveDelta = strictSlop + 2f
        assertTrue(moveDelta < postLongPressSlop)
        dispatchTouch(MotionEvent.ACTION_MOVE, downTime = 1_000L, eventTime = 1_650L, x = 100f + moveDelta, y = 100f)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("crossing 16dp drag slop must cancel discard hold feedback", isDiscardHoldFeedbackActive())

        // Even after waiting past 3s, drag won and recording stays alive.
        shadowOf(Looper.getMainLooper()).idleFor(3_000, TimeUnit.MILLISECONDS)
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())
    }

    @Test fun `finger up before 3 seconds aborts discard and keeps recording active`() {
        service.runtime.onTap()
        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        assertTrue(isDiscardHoldFeedbackActive())

        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 2_000L)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(isDiscardHoldFeedbackActive())
        assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())
    }

    @Test fun `3-second hold discards recording without writing history`() {
        service.runtime.onTap()
        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(3_050, TimeUnit.MILLISECONDS)

        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
        assertEquals("Recording discarded", ShadowToast.getTextOfLatestToast())
        assertTrue("discarded recording must not write dictation history", DictationHistoryStore.forContext(app).all().isEmpty())

        dispatchTouch(MotionEvent.ACTION_UP, downTime = 1_000L, eventTime = 4_100L)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
    }

    @Test fun `onEnterTranscribingUi cancels pending holdFeedbackAction and longPressAction`() {
        // Case A: transition before 500ms feedback runnable fires.
        service.runtime.onTap()
        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        service.runtime.onTap() // stop -> triggers onEnterTranscribingUi synchronously while finger is still down
        shadowOf(Looper.getMainLooper()).idleFor(3_500, TimeUnit.MILLISECONDS)

        assertEquals(RecordingStateMachine.State.TRANSCRIBING, service.runtime.currentState())
        assertFalse(isDiscardHoldFeedbackActive())

        // Reset to IDLE for Case B.
        service.runtime.cancelTranscription()
        shadowOf(Looper.getMainLooper()).idle()

        // Case B: transition after 500ms feedback started while 3s longPressAction is still pending.
        service.runtime.onTap()
        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 5_000L, eventTime = 5_000L)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        assertTrue(isDiscardHoldFeedbackActive())

        service.runtime.onTap() // stop -> triggers onEnterTranscribingUi while 3s discard timer is pending
        shadowOf(Looper.getMainLooper()).idleFor(3_000, TimeUnit.MILLISECONDS)
        assertEquals(
            "cancelled longPressAction must not cancel transcription",
            RecordingStateMachine.State.TRANSCRIBING,
            service.runtime.currentState(),
        )
        assertFalse(isDiscardHoldFeedbackActive())
    }

    @Test fun `onIdleUi cancels pending holdFeedbackAction and longPressAction`() {
        service.runtime.onTap()
        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        val lease = DictationRuntime::class.java.getDeclaredField("sessionLease")
            .apply { isAccessible = true }
            .get(service.runtime) as DictationSessionLease
        service.runtime.resetToIdle(lease)

        shadowOf(Looper.getMainLooper()).idleFor(3_500, TimeUnit.MILLISECONDS)
        assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
        assertFalse(isDiscardHoldFeedbackActive())
        assertNull("cancelled longPressAction must not open style menu after resetToIdle", styleMenuView())
    }

    @Test fun `onDestroy cancels pending holdFeedbackAction and longPressAction`() {
        service.runtime.onTap()
        dispatchTouch(MotionEvent.ACTION_DOWN, downTime = 1_000L, eventTime = 1_000L)
        service.onDestroy()

        shadowOf(Looper.getMainLooper()).idleFor(3_500, TimeUnit.MILLISECONDS)
        assertFalse(isDiscardHoldFeedbackActive())
        assertNull(styleMenuView())
    }
}
