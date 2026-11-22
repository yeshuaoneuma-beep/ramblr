package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 2C tests for the 7:00 recording countdown:
 * - [RecordingCountdown.formatRemaining] formatting edges.
 * - [DictationRuntime] 7:00 (420_000 ms) trigger, per-second tick progression down to 10:00 (0 ms),
 *   and configurable clock/delay seams.
 * - Clean cancellation on stop, [DictationRuntime.cancelRecording], [DictationRuntime.resetToIdle],
 *   and [DictationRuntime.beginShutdown].
 * - [RamblrImeService] surfacing the countdown inside `statusView` while recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingCountdownTest {

    private class CountdownListener : RuntimeListener {
        val countdowns = mutableListOf<Long>()
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onRecordingCountdown(remainingMs: Long) { countdowns += remainingMs }
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {}
        override fun foregroundPackageName(): String? = null
    }

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
    private var imeService: RamblrImeService? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ActiveDictationHostRegistry.resetForTest()
    }

    @After fun tearDown() {
        imeService?.onDestroy()
        imeService = null
        ActiveDictationHostRegistry.resetForTest()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `formatRemaining renders m-ss cleanly across boundaries`() {
        assertEquals("3:00", RecordingCountdown.formatRemaining(180_000L))
        assertEquals("2:59", RecordingCountdown.formatRemaining(179_000L))
        assertEquals("2:59", RecordingCountdown.formatRemaining(178_001L))
        assertEquals("1:00", RecordingCountdown.formatRemaining(60_000L))
        assertEquals("0:59", RecordingCountdown.formatRemaining(59_000L))
        assertEquals("0:01", RecordingCountdown.formatRemaining(1_000L))
        assertEquals("0:01", RecordingCountdown.formatRemaining(1L))
        assertEquals("0:00", RecordingCountdown.formatRemaining(0L))
        assertEquals("0:00", RecordingCountdown.formatRemaining(-500L))
    }

    @Test fun `runtime triggers countdown at 7-00 and progresses down to 10-00 cap`() {
        val listener = CountdownListener()
        val leaseRegistry = InMemoryDictationSessionLeaseRegistry()
        val runtime = DictationRuntime(app, listener, leaseRegistry) { cacheDir, stateMachine ->
            FakeRecordingEngine(cacheDir, stateMachine)
        }

        assertEquals(420_000L, DictationRuntime.RECORDING_COUNTDOWN_START_MS)
        assertEquals(600_000L, DictationRuntime.RECORDING_CAP_MS)

        runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())

        // 1 second before 7:00 -> silent.
        shadowOf(Looper.getMainLooper()).idleFor(419_000, TimeUnit.MILLISECONDS)
        assertTrue("countdown must not fire before 7:00", listener.countdowns.isEmpty())

        // At 7:00 (420_000 ms) -> fires 3:00 remaining (180_000 ms).
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(listOf(180_000L), listener.countdowns)

        // One second later -> 179_000 ms.
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(listOf(180_000L, 179_000L), listener.countdowns)

        // Advance through the remaining 179 seconds to 10:00.
        shadowOf(Looper.getMainLooper()).idleFor(179_000, TimeUnit.MILLISECONDS)
        assertEquals(0L, listener.countdowns.last())
        assertEquals(181, listener.countdowns.size)

        // No further ticks once remaining reaches 0.
        shadowOf(Looper.getMainLooper()).idleFor(5_000, TimeUnit.MILLISECONDS)
        assertEquals(181, listener.countdowns.size)
        runtime.beginShutdown()
    }

    @Test fun `configurable clock and delay seams drive deterministic countdown progression`() {
        var fakeNow = 10_000L
        val listener = CountdownListener()
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            elapsedRealtimeMs = { fakeNow },
            recordingCountdownStartMs = 100L,
            recordingCapMs = 250L,
            recordingCountdownTickMs = 50L,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }

        runtime.onTap()
        assertTrue(listener.countdowns.isEmpty())

        fakeNow += 100L
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(listOf(150L), listener.countdowns)

        fakeNow += 50L
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        assertEquals(listOf(150L, 100L), listener.countdowns)

        fakeNow += 50L
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        assertEquals(listOf(150L, 100L, 50L), listener.countdowns)

        fakeNow += 50L
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        assertEquals(listOf(150L, 100L, 50L, 0L), listener.countdowns)

        fakeNow += 200L
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        assertEquals(4, listener.countdowns.size)
        runtime.beginShutdown()
    }

    @Test fun `countdown cancels cleanly on stop, cancelRecording, resetToIdle, and beginShutdown`() {
        val listener = CountdownListener()
        fun newRuntime() = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            recordingCountdownStartMs = 100L,
            recordingCapMs = 500L,
            recordingCountdownTickMs = 50L,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }

        // 1. Normal stop tap
        val r1 = newRuntime()
        r1.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, r1.currentState())
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(1, listener.countdowns.size)
        r1.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals("stop must cancel future countdown ticks", 1, listener.countdowns.size)
        r1.shutdown()

        // 2. cancelRecording()
        listener.countdowns.clear()
        val r2 = newRuntime()
        r2.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, r2.currentState())
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(1, listener.countdowns.size)
        assertTrue(r2.cancelRecording())
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals("cancelRecording must cancel future countdown ticks", 1, listener.countdowns.size)
        r2.shutdown()

        // 3. resetToIdle()
        listener.countdowns.clear()
        val r3 = newRuntime()
        r3.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, r3.currentState())
        val leaseField = DictationRuntime::class.java.getDeclaredField("sessionLease").apply { isAccessible = true }
        val lease = leaseField.get(r3) as DictationSessionLease
        r3.resetToIdle(lease)
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertTrue("resetToIdle before countdown start must prevent any ticks", listener.countdowns.isEmpty())
        r3.shutdown()

        // 4. beginShutdown()
        listener.countdowns.clear()
        val r4 = newRuntime()
        r4.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, r4.currentState())
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(1, listener.countdowns.size)
        r4.beginShutdown()
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals("beginShutdown must cancel future countdown ticks", 1, listener.countdowns.size)
    }

    @Test fun `RamblrImeService surfaces 7-00 countdown in statusView while recording and clears on exit`() {
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        imeService = service
        service.runtimeFactoryForTest = { ctx, listener ->
            DictationRuntime(
                ctx,
                listener,
                InMemoryDictationSessionLeaseRegistry(),
                recordingCountdownStartMs = 420_000L,
                recordingCapMs = 600_000L,
                recordingCountdownTickMs = 1_000L,
            ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        }
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "com.example.editor"
            fieldId = 1
        }
        service.onCreateInputView()
        service.onStartInput(info, false)
        service.onStartInputView(info, false)

        val controller = service.panelControllerForTest()!!
        val status = service.statusViewForTest()!!
        assertEquals(app.getString(R.string.ime_status_idle), status.text.toString())

        controller.onMicTap()
        assertEquals(app.getString(R.string.ime_status_recording), status.text.toString())

        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)
        assertEquals(
            app.getString(R.string.ime_status_recording_countdown, "3:00"),
            status.text.toString(),
        )

        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(
            app.getString(R.string.ime_status_recording_countdown, "2:59"),
            status.text.toString(),
        )

        controller.onMicTap()
        assertEquals(app.getString(R.string.ime_status_transcribing), status.text.toString())
        assertFalse(status.text.toString().contains("2:59"))
    }
}
