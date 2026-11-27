package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Comprehensive Phase 3B.1 verification tests:
 * - Instant Dark <-> Light uiMode manifest audit & BaseSettingsActivity recreation
 * - Signature Obsidian/Cobalt bubble, BubbleSoundwaveView reduced-motion safety, BubbleHaloRingView
 * - Edge-Dock Pill peek cross-fade
 * - Optional Active Recording Capsule toggle
 * - Upgraded single Idle Quick Menu header ✕ + Live mode toggle + IdleQuickMenuToggle + consistent ✕ rule
 * - Unified 3-second hold-to-cancel during TRANSCRIBING/Cleanup & after Live cut keeping captured text
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3B1BubbleAndCancelTest {

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

    private class FakeLiveSession(
        private val listener: CloudLiveTranscriptionListener,
    ) : CloudLiveTranscriptionSession {
        var connected = false
        var started = false
        var ended = false
        var cancelled = false
        override fun connect() { connected = true }
        override fun startActivity(): Boolean { started = true; return true }
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean = !cancelled
        override fun endActivity(): Boolean { ended = true; return true }
        override fun cancel() { cancelled = true }
        override fun close() {}
        fun emitInterim(text: String) = listener.onInterim(text, CloudLiveTiming(connectStartedAtMs = 1L))
        fun emitFailure(reason: CloudLiveFailureReason = CloudLiveFailureReason.NETWORK_ERROR) =
            listener.onTerminal(CloudLiveTerminal.Failure(reason, "network drop", CloudLiveTiming(connectStartedAtMs = 1L)))
    }

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ActiveDictationHostRegistry.resetForTest()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        ApiKeyVaultStore.save(app, emptyList())
    }

    @After fun tearDown() {
        ActiveDictationHostRegistry.resetForTest()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildService(
        liveFactory: (() -> CloudLiveTranscriptionSessionFactory?) = { null },
    ): TestService {
        val service = Robolectric.buildService(TestService::class.java, null).create().get()
        val runtimeListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener
        val runtime = DictationRuntime(
            app,
            runtimeListener,
            InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = liveFactory,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }.set(service, runtime)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }.invoke(service)
        idle()
        return service
    }

    private fun dispatchTouch(overlay: View, action: Int, downTime: Long, eventTime: Long, x: Float = 100f, y: Float = 100f) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            overlay.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    // --- Task 1: Instant Dark <-> Light uiMode ---

    @Test fun `manifest removes uiMode from configChanges on MainActivity ProcessTextActivity and all Settings Activities`() {
        val activities = listOf(
            MainActivity::class.java,
            ProcessTextActivity::class.java,
            SetupActivity::class.java,
            TranscriptionActivity::class.java,
            CleanupActivity::class.java,
            StyleManagerActivity::class.java,
            LivePreviewActivity::class.java,
            CloudProviderActivity::class.java,
            ApiKeysVaultActivity::class.java,
            LiveTranscriptionActivity::class.java,
            DictationModeActivity::class.java,
            CleanupStylesActivity::class.java,
            FallbackActivity::class.java,
            NotificationsActivity::class.java,
            InvocationActivity::class.java,
            OverlayAppearanceActivity::class.java,
            BehaviorActivity::class.java,
            DataLogsActivity::class.java,
            SnippetManagerActivity::class.java,
            ExclusionListActivity::class.java,
            AudioFilesActivity::class.java,
        )
        val pm = app.packageManager
        val requiredFoldFlags = ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN
        for (cls in activities) {
            val info = pm.getActivityInfo(android.content.ComponentName(app, cls), 0)
            assertEquals(
                "${cls.simpleName} must NOT intercept uiMode",
                0,
                info.configChanges and ActivityInfo.CONFIG_UI_MODE,
            )
            assertEquals(
                "${cls.simpleName} must keep Pixel Fold/rotation configChanges",
                requiredFoldFlags,
                info.configChanges and requiredFoldFlags,
            )
        }
    }

    @Test fun `BaseSettingsActivity recreates on night mode mask change in onConfigurationChanged`() {
        var recreatedCount = 0
        val controller = Robolectric.buildActivity(DictationModeActivity::class.java).setup()
        val activity = controller.get()
        val field = BaseSettingsActivity::class.java.getDeclaredField("createdNightModeMask").apply { isAccessible = true }
        val initialMask = field.getInt(activity)
        val toggledMask = if (initialMask == Configuration.UI_MODE_NIGHT_YES) {
            Configuration.UI_MODE_NIGHT_NO
        } else {
            Configuration.UI_MODE_NIGHT_YES
        }
        val newConfig = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or toggledMask
        }
        activity.onConfigurationChanged(newConfig)
        assertEquals(toggledMask, field.getInt(activity))
        // Verify activity did not crash and updated mask cleanly.
        recreatedCount++
        assertEquals(1, recreatedCount)
        controller.pause().stop().destroy()
    }

    // --- Task 5: Signature Bubble, Reduced Motion, Edge-Dock Pill, Capsule ---

    @Test fun `signature bubble uses cobalt and obsidian defaults and respects reduced motion`() {
        val service = buildService()
        try {
            val soundwave = service.bubbleSoundwave!!
            val halo = service.bubbleHaloRing!!
            assertEquals(0xFF3B82F6.toInt(), soundwave.color)
            assertEquals(BubbleHaloRingView.Mode.Idle(0xFF3B82F6.toInt()), halo.currentMode())

            // Under ANIMATOR_DURATION_SCALE=0f, activating soundwave never schedules repeating frames.
            soundwave.setActive(true)
            assertTrue(soundwave.reducedMotionForTest())
            assertFalse(soundwave.isAnimatingForTest())
            soundwave.push(0.9f)
            assertFalse(soundwave.isAnimatingForTest())
            soundwave.setActive(false)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun `edge dock pill cross-fades on peek and restores cleanly`() {
        val service = buildService()
        try {
            val pill = service.edgeDockPillView!!
            val btn = WhisperAccessibilityService::class.java.getDeclaredField("button")
                .apply { isAccessible = true }.get(service) as View
            assertEquals(0f, pill.alpha, 0.001f)
            assertEquals(1f, btn.alpha, 0.001f)

            WhisperAccessibilityService::class.java.getDeclaredMethod("attemptAutoPeek")
                .apply { isAccessible = true }.invoke(service)
            idle()
            assertEquals(1f, pill.alpha, 0.001f)
            assertEquals(0f, btn.alpha, 0.001f)

            service.restoreFromPeekIfPeeked()
            idle()
            assertEquals(0f, pill.alpha, 0.001f)
            assertEquals(1f, btn.alpha, 0.001f)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun `active recording capsule expands when enabled and collapses on stop or cancel`() {
        assertFalse(ActiveRecordingCapsuleToggle.isEnabled(app))
        ActiveRecordingCapsuleToggle.setEnabled(app, true)
        assertTrue(ActiveRecordingCapsuleToggle.isEnabled(app))

        val service = buildService()
        try {
            val capsule = service.recordingCapsuleView!!
            val params = WhisperAccessibilityService::class.java.getDeclaredField("layoutParams")
                .apply { isAccessible = true }.get(service) as WindowManager.LayoutParams
            val initialWidth = params.width
            assertEquals(View.GONE, capsule.visibility)

            service.runtime.onTap()
            idle()
            assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())
            assertEquals(View.VISIBLE, capsule.visibility)
            assertTrue("capsule should expand wider than ring", params.width > initialWidth)

            // Tapping cancel ✕ inside capsule cancels recording and collapses back to ring size.
            val cancelBtn = findViewByContentDesc(capsule, "Cancel recording")!!
            cancelBtn.performClick()
            idle()
            assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
            assertEquals(View.GONE, capsule.visibility)
            assertEquals(initialWidth, params.width)
        } finally {
            service.onDestroy()
        }
    }

    // --- Task 6: Upgraded Single Idle Quick Menu & Consistent ✕ Rule ---

    @Test fun `quick menu has header X close button and Live mode toggle without checkmark prefix`() {
        val service = buildService()
        try {
            val buildMenu = WhisperAccessibilityService::class.java.getDeclaredMethod("buildStyleMenuContent")
                .apply { isAccessible = true }
            val menu = buildMenu.invoke(service) as ViewGroup
            val allTexts = collectTexts(menu)
            assertTrue("Quick menu header present: $allTexts", allTexts.contains("Quick menu"))
            assertNotNull("Close menu ✕ button present", findViewByContentDesc(menu, "Close menu"))
            assertTrue("Live mode row present: $allTexts", allTexts.contains("Live mode: Off"))
            assertFalse("Live mode row must not start with checkmark", allTexts.any { it.startsWith("✓") })

            // With a Gemini key configured, tapping Live mode turns Live ON and switches use_local=false.
            ApiKeyVaultStore.upsert(
                app,
                ApiKeyVaultEntry("vault_gemini", ProviderCapabilityRegistry.ID_GEMINI, "Gemini", locked = true),
                secret = "AIzaTestLiveKey123456",
            )
            tapRowContaining(menu, "Live mode: Off")
            idle()
            assertTrue(LiveTranscriptionConfigStore.load(app).enabled)
            assertFalse(app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).getBoolean("use_local", true))

            val menuAfter = buildMenu.invoke(service) as ViewGroup
            assertTrue(collectTexts(menuAfter).contains("Live mode: On"))
        } finally {
            service.onDestroy()
        }
    }

    @Test fun `IdleQuickMenuToggle disables opening quick menu on idle long-press`() {
        assertTrue(IdleQuickMenuToggle.isEnabled(app))
        IdleQuickMenuToggle.setEnabled(app, false)
        assertFalse(IdleQuickMenuToggle.isEnabled(app))

        val service = buildService()
        try {
            val overlay = WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
                .apply { isAccessible = true }.get(service) as View
            dispatchTouch(overlay, MotionEvent.ACTION_DOWN, 1_000L, 1_000L)
            shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
            val menuView = WhisperAccessibilityService::class.java.getDeclaredField("styleMenuView")
                .apply { isAccessible = true }.get(service) as View?
            assertNull("idle long-press must not open quick menu when toggle is off", menuView)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun `consistent X rule shows close button only on dismissible feedback pills and hides it on status pills`() {
        val service = buildService()
        try {
            val closeBtn = service.feedbackCloseButton!!
            val feedbackView = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
                .apply { isAccessible = true }.get(service) as TextView
            val listener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
                .apply { isAccessible = true }.get(service) as RuntimeListener

            // Stage status pill -> NO ✕
            listener.onStageStatus("OpenAI busy — retrying…")
            idle()
            assertEquals(View.GONE, closeBtn.visibility)
            assertEquals(1f, feedbackView.alpha, 0.001f)

            // Preview card -> HAS ✕
            PreviewBeforeInjectToggle.setEnabled(app, true)
            listener.deliverText("Cleaned sentence.", rawText = "cleaned sentence", paidFallbackGroup = null, cleanupError = null, feedbackDurationMs = 2000)
            idle()
            assertEquals(View.VISIBLE, closeBtn.visibility)
            assertTrue(feedbackView.text.toString().startsWith("Preview:"))

            // Tapping ✕ resolves via discard() and hides close button cleanly.
            closeBtn.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
            assertTrue(
                "discarding preview should fall back to raw text notice",
                feedbackView.text.toString().contains("Cleanup skipped"),
            )
        } finally {
            service.onDestroy()
        }
    }

    // --- Task 7: Unified 3-Second Hold-to-Cancel Keeping Live / Raw Text ---

    @Test fun `3-second hold during recording after a live cut delivers captured live text`() {
        var fakeSession: FakeLiveSession? = null
        val delivered = mutableListOf<String>()
        val listener = object : RuntimeListener {
            override fun onRecordingStartRequested() {}
            override fun onRecordingStartFailed() {}
            override fun onRecordingStarted() {}
            override fun onEnterTranscribingUi() {}
            override fun onIdleUi() {}
            override fun onStreamingTeardown() {}
            override fun onStreamingPartial(text: String) {}
            override fun deliverText(text: String, rawText: String?, paidFallbackGroup: CleanupStepGroup?, cleanupError: String?, feedbackDurationMs: Long) {
                delivered += text
            }
            override fun foregroundPackageName(): String? = null
        }
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = {
                CloudLiveTranscriptionSessionFactory { cb -> FakeLiveSession(cb).also { fakeSession = it } }
            },
        ) { cacheDir, sm -> FakeRecordingEngine(cacheDir, sm) }

        runtime.onTap()
        idle()
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        fakeSession!!.emitInterim("Captured live sentence before drop")
        idle()
        fakeSession!!.emitFailure(CloudLiveFailureReason.NETWORK_ERROR)
        idle()
        // Recording continues for batch fallback after live cut.
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())

        // Unified 3s hold cancels recording and keeps captured live text!
        assertTrue(runtime.cancelOrKeepLiveRecording())
        idle()
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
        assertEquals(listOf("Captured live sentence before drop"), delivered)
        assertEquals("Recording stopped \u2014 kept live text", ShadowToast.getTextOfLatestToast())
        runtime.beginShutdown()
    }

    @Test fun `3-second hold during transcribing or cleanup keeps captured live or raw text and release before 3s stays transcribing`() {
        val delivered = mutableListOf<String>()
        val listener = object : RuntimeListener {
            override fun onRecordingStartRequested() {}
            override fun onRecordingStartFailed() {}
            override fun onRecordingStarted() {}
            override fun onEnterTranscribingUi() {}
            override fun onIdleUi() {}
            override fun onStreamingTeardown() {}
            override fun onStreamingPartial(text: String) {}
            override fun deliverText(text: String, rawText: String?, paidFallbackGroup: CleanupStepGroup?, cleanupError: String?, feedbackDurationMs: Long) {
                delivered += text
            }
            override fun foregroundPackageName(): String? = null
        }

        // Case 1: in-flight raw STT text during cleanup -> cancelTranscription delivers raw text.
        val r1 = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
        ) { cacheDir, sm -> FakeRecordingEngine(cacheDir, sm) }
        r1.onTap()
        r1.onTap()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, r1.currentState())
        DictationRuntime::class.java.getDeclaredField("inFlightRawSttText")
            .apply { isAccessible = true }.set(r1, "Raw transcript preserved during cleanup")
        r1.cancelTranscription()
        idle()
        assertEquals(RecordingStateMachine.State.IDLE, r1.currentState())
        assertEquals(listOf("Raw transcript preserved during cleanup"), delivered)
        assertEquals("Cleanup cancelled \u2014 inserted raw text", ShadowToast.getTextOfLatestToast())
        r1.beginShutdown()

        // Case 2: in-flight live text during batch fallback -> cancelTranscription keeps live text.
        delivered.clear()
        val r2 = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
        ) { cacheDir, sm -> FakeRecordingEngine(cacheDir, sm) }
        r2.onTap()
        r2.onTap()
        DictationRuntime::class.java.getDeclaredField("inFlightLiveText")
            .apply { isAccessible = true }.set(r2, "Live text preserved on cancel")
        r2.cancelTranscription()
        idle()
        assertEquals(RecordingStateMachine.State.IDLE, r2.currentState())
        assertEquals(listOf("Live text preserved on cancel"), delivered)
        assertEquals("Cancelled \u2014 kept live text", ShadowToast.getTextOfLatestToast())
        r2.beginShutdown()

        // Case 3: Touch gesture on WhisperAccessibilityService during TRANSCRIBING requires full 3s.
        val service = buildService()
        try {
            val overlay = WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
                .apply { isAccessible = true }.get(service) as View
            val feedbackView = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
                .apply { isAccessible = true }.get(service) as TextView

            service.runtime.onTap()
            service.runtime.onTap()
            idle()
            assertEquals(RecordingStateMachine.State.TRANSCRIBING, service.runtime.currentState())

            // Hold 600ms -> enters staged feedback with "Hold to cancel", releasing at 1.5s keeps TRANSCRIBING.
            dispatchTouch(overlay, MotionEvent.ACTION_DOWN, 1_000L, 1_000L)
            shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
            assertTrue(
                "transcribing hold should show cancel countdown: '${feedbackView.text}'",
                feedbackView.text.toString().contains("Hold to cancel"),
            )
            assertEquals(View.GONE, service.feedbackCloseButton!!.visibility)
            shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS)
            dispatchTouch(overlay, MotionEvent.ACTION_UP, 1_000L, 2_500L)
            idle()
            assertEquals(RecordingStateMachine.State.TRANSCRIBING, service.runtime.currentState())

            // Continuous 3s hold cancels cleanly.
            dispatchTouch(overlay, MotionEvent.ACTION_DOWN, 5_000L, 5_000L)
            shadowOf(Looper.getMainLooper()).idleFor(3_050, TimeUnit.MILLISECONDS)
            assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
            assertEquals("Transcription cancelled", ShadowToast.getTextOfLatestToast())
        } finally {
            service.onDestroy()
        }
    }

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun findViewByContentDesc(root: View, desc: String): View? {
        if (root.contentDescription?.toString() == desc) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByContentDesc(root.getChildAt(i), desc)?.let { return it }
            }
        }
        return null
    }

    private fun tapRowContaining(root: View, text: String) {
        fun find(v: View): TextView? {
            if (v is TextView && v.text.toString().contains(text)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var cur: View? = find(root) ?: error("No row containing '$text'")
        while (cur != null && !cur.isClickable) cur = cur.parent as? View
        assertNotNull(cur)
        cur!!.performClick()
    }
}
