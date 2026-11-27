package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3B2LiveWindowAndReconciliationTest {

    class TestService : WhisperAccessibilityService() {
        var fakeRoot: AccessibilityNodeInfo? = null
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? = fakeRoot
    }

    private class CapturingListener(
        var interactiveReconciliation: Boolean = true,
    ) : RuntimeListener {
        data class Delivery(
            val text: String,
            val rawText: String?,
            val liveText: String?,
            val cleanupError: String?,
        )

        val deliveries = mutableListOf<Delivery>()
        val reconciliationReady = mutableListOf<Pair<String, Boolean>>()
        val reconciliationBatchReady = mutableListOf<Pair<String, String>>()
        val cutoffWarnings = mutableListOf<Long>()
        var idleCount = 0

        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() { idleCount++ }
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveCutoffWarning(remainingMs: Long) { cutoffWarnings += remainingMs }
        override fun supportsInteractiveLiveReconciliation(): Boolean = interactiveReconciliation
        override fun onLiveReconciliationReady(liveText: String, batchPending: Boolean) {
            reconciliationReady += liveText to batchPending
        }
        override fun onLiveReconciliationBatchReady(liveText: String, batchText: String) {
            reconciliationBatchReady += liveText to batchText
        }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            deliverText(text, rawText, paidFallbackGroup, cleanupError, feedbackDurationMs, liveText = null)
        }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
            liveText: String?,
        ) {
            deliveries += Delivery(text, rawText, liveText, cleanupError)
        }
        override fun foregroundPackageName(): String? = null
    }

    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            return true
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false

        fun finishAs(pcmFile: File, stopReason: StopReason = StopReason.USER) {
            stateMachine.tryStartTranscribing()
            onFinished!!(
                Result(
                    pcmFile = pcmFile,
                    bytesRecorded = pcmFile.length(),
                    discarded = false,
                    stopReason = stopReason,
                )
            )
        }
    }

    private class FakeLiveFactory : CloudLiveTranscriptionSessionFactory {
        val sessions = mutableListOf<FakeLiveSession>()
        override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession =
            FakeLiveSession(listener).also(sessions::add)
    }

    private class FakeLiveSession(
        private val listener: CloudLiveTranscriptionListener,
    ) : CloudLiveTranscriptionSession {
        var closes = 0
        var cancels = 0
        override fun connect() {}
        override fun startActivity(): Boolean = true
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean = true
        override fun endActivity(): Boolean = true
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
        fun interim(text: String) = listener.onInterim(text, CloudLiveTiming(1_000L, firstInterimAtMs = 1_100L))
        fun terminal(result: CloudLiveTerminal) = listener.onTerminal(result)
    }

    private lateinit var app: Application
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_openrouter", "vault_anthropic")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        ApiKeyVaultStore.save(app, emptyList())
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        idleMain()
        server.close()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_openrouter", "vault_anthropic")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        ApiKeyVaultStore.save(app, emptyList())
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun usablePcm(): File =
        File.createTempFile("p3b2_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(32_000)) }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) {
            idleMain()
            Thread.sleep(10)
        }
        idleMain()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    private fun configureBatchOpenAi(transcript: String, cleanupResponse: String? = null, gate: CountDownLatch? = null) {
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(
                listOf(
                    ProviderChainEntry(
                        ProviderKind.OPENAI,
                        "gpt-5.4-mini",
                        baseUrlOverride = base,
                        transcriptionModel = "gpt-transcribe",
                        vaultKeyId = "vault_openai",
                    )
                )
            ),
        )
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-p3b2-batch")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, cleanupResponse != null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target.contains("/audio/transcriptions")) {
                    gate?.await(5, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("""{"text":"$transcript"}""").build()
                }
                return MockResponse.Builder()
                    .body("""{"choices":[{"message":{"content":"${cleanupResponse ?: transcript}"}}]}""")
                    .build()
            }
        }
    }

    // --- 1. LiveTranscriptionConfigStore round-trip & LiveTranscriptionActivity UI ---

    @Test fun `LiveTranscriptionConfigStore round-trips liveWindowEnabled and all LiveDisconnectPolicy values with legacy fallback`() {
        for (policy in LiveDisconnectPolicy.entries) {
            for (windowEnabled in listOf(true, false)) {
                val cfg = LiveTranscriptionConfig(
                    enabled = true,
                    liveWindowEnabled = windowEnabled,
                    disconnectPolicy = policy,
                )
                val parsed = LiveTranscriptionConfigStore.deserialize(LiveTranscriptionConfigStore.serialize(cfg))
                assertNotNull(parsed)
                assertEquals(windowEnabled, parsed!!.liveWindowEnabled)
                assertEquals(policy, parsed.disconnectPolicy)
            }
        }

        // Legacy JSON missing the new keys defaults safely.
        val legacy = LiveTranscriptionConfigStore.deserialize("""{"enabled":true,"mode":"SMART"}""")!!
        assertTrue(legacy.liveWindowEnabled)
        assertEquals(LiveDisconnectPolicy.AUTO_REPLACE_WITH_BATCH, legacy.disconnectPolicy)

        // Unknown enum value falls back safely to AUTO_REPLACE_WITH_BATCH.
        val unknown = LiveTranscriptionConfigStore.deserialize("""{"enabled":true,"disconnectPolicy":"FUTURE_VALUE"}""")!!
        assertEquals(LiveDisconnectPolicy.AUTO_REPLACE_WITH_BATCH, unknown.disconnectPolicy)
    }

    @Test fun `LiveTranscriptionActivity renders Live transcript window toggle and 3 selectionDot rows with zero RadioButtons`() {
        val controller = Robolectric.buildActivity(LiveTranscriptionActivity::class.java).setup()
        val activity = controller.get()
        try {
            val root = activity.window.decorView
            assertFalse("Zero RadioButtons allowed", hasWidgetType(root, RadioButton::class.java))
            assertFalse("Zero RadioGroups allowed", hasWidgetType(root, RadioGroup::class.java))

            val texts = collectTexts(root)
            assertTrue(texts.contains("Live transcript window"))
            assertTrue(texts.contains("On live disconnect"))
            assertTrue(texts.contains("Auto-replace with Batch + swap chip"))
            assertTrue(texts.contains("Ask me (Keep Live or Use Batch)"))
            assertTrue(texts.contains("Keep Live text only"))

            // Tap Live transcript window switch row -> persists false.
            tapRowContaining(root, "Live transcript window")
            assertFalse(LiveTranscriptionConfigStore.load(app).liveWindowEnabled)

            // Tap Ask me -> persists ASK_ME.
            tapRowContaining(root, "Ask me (Keep Live or Use Batch)")
            assertEquals(LiveDisconnectPolicy.ASK_ME, LiveTranscriptionConfigStore.load(app).disconnectPolicy)

            // Tap Keep Live text only -> persists KEEP_LIVE_ONLY.
            tapRowContaining(root, "Keep Live text only")
            assertEquals(LiveDisconnectPolicy.KEEP_LIVE_ONLY, LiveTranscriptionConfigStore.load(app).disconnectPolicy)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- 2. Pure LiveWindowStateMachine & Android Overlay Visibility / Keyguard / Countdown Peek ---

    @Test fun `LiveWindowStateMachine handles collapse reopen countdown peek alert routing and keyguard gating`() {
        val sm = LiveWindowStateMachine()
        assertEquals(LiveWindowDisplayMode.HIDDEN, sm.effectiveMode(overlayAllowed = true))
        assertEquals(LiveWindowAlertRoute.BUBBLE_STATUS_PILL, sm.alertRoute(overlayAllowed = true))

        // Start Live -> EXPANDED, inline alerts, body visible.
        assertEquals(LiveWindowDisplayMode.EXPANDED, sm.onLiveStarted(windowEnabled = true, overlayAllowed = true))
        assertTrue(sm.showsTranscriptBody(overlayAllowed = true))
        assertEquals(LiveWindowAlertRoute.INLINE_LIVE_WINDOW, sm.alertRoute(overlayAllowed = true))

        // Collapse via ✕ -> COLLAPSED, reopen chip visible, alert routes to bubble pill.
        sm.onInterim("First words spoken")
        assertEquals(LiveWindowDisplayMode.COLLAPSED, sm.onCollapseTapped(overlayAllowed = true))
        assertFalse(sm.showsTranscriptBody(overlayAllowed = true))
        assertTrue(sm.showsReopenChip(overlayAllowed = true))
        assertEquals(LiveWindowAlertRoute.BUBBLE_STATUS_PILL, sm.alertRoute(overlayAllowed = true))

        // Updates accumulate while collapsed without showing body.
        sm.onInterim("First words spoken and more words")
        assertEquals("First words spoken and more words", sm.transcript)
        assertFalse(sm.showsTranscriptBody(overlayAllowed = true))

        // 7:00 countdown arrives while collapsed -> promotes to COUNTDOWN_PEEK with ZERO transcript words!
        assertEquals(LiveWindowDisplayMode.COUNTDOWN_PEEK, sm.onRecordingCountdown(180_000L, overlayAllowed = true))
        assertFalse("ZERO transcript words may show in COUNTDOWN_PEEK", sm.showsTranscriptBody(overlayAllowed = true))
        assertTrue(sm.showsCountdownPeekHeaderOnly(overlayAllowed = true))
        assertFalse(sm.showsReopenChip(overlayAllowed = true))
        assertTrue(sm.formatHeaderStatus(420).contains("3:00 left"))

        // Dismiss peek back to COLLAPSED, then 8:00->9:00 cutoff approach promotes back to COUNTDOWN_PEEK.
        assertEquals(LiveWindowDisplayMode.COLLAPSED, sm.onCollapseTapped(overlayAllowed = true))
        assertEquals(LiveWindowDisplayMode.COUNTDOWN_PEEK, sm.onCutoffApproachOrTrigger(45, overlayAllowed = true))
        assertFalse(sm.showsTranscriptBody(overlayAllowed = true))
        assertTrue(sm.formatHeaderStatus(495).contains("0:45 to cutoff"))

        // Reopen chip -> EXPANDED with full accumulated transcript.
        assertEquals(LiveWindowDisplayMode.EXPANDED, sm.onReopenTapped(overlayAllowed = true))
        assertTrue(sm.showsTranscriptBody(overlayAllowed = true))
        assertEquals("First words spoken and more words", sm.transcript)

        // Keyguard / screen-off suppresses immediately (HIDDEN) and restores when unlocked.
        assertEquals(LiveWindowDisplayMode.HIDDEN, sm.effectiveMode(overlayAllowed = false))
        assertFalse(sm.showsTranscriptBody(overlayAllowed = false))
        assertFalse(sm.showsReopenChip(overlayAllowed = false))
        assertEquals(LiveWindowDisplayMode.EXPANDED, sm.effectiveMode(overlayAllowed = true))
    }

    @Test fun `WhisperAccessibilityService Live Window collapses without stopping recording, peeks header-only, hides on keyguard-AOD, and rethemes on uiMode`() {
        val service = buildService()
        try {
            val listener = runtimeListenerOf(service)

            // Start recording + Live connected -> Live Window lazily attached & EXPANDED.
            service.runtime.onTap()
            idleMain()
            listener.onCloudLiveConnected()
            listener.onCloudLiveInterim("Secret live transcript words")
            idleMain()

            val win = service.liveWindowView!!
            val winParams = service.liveWindowParams!!
            val chip = service.liveReopenChipView!!
            val chipParams = service.liveReopenChipParams!!

            assertEquals(View.VISIBLE, win.visibility)
            assertEquals(1f, win.alpha, 0.001f)
            assertEquals(View.VISIBLE, service.liveWindowBodyScroll!!.visibility)
            assertEquals("Secret live transcript words", service.liveTranscriptTextView!!.text.toString())

            // Stage status while expanded routes inline (does NOT duplicate in feedbackView).
            listener.onStageStatus("Live paused · recording continues")
            idleMain()
            assertEquals(View.VISIBLE, service.liveWindowInlineStatusText!!.visibility)
            assertEquals("Live paused · recording continues", service.liveWindowInlineStatusText!!.text.toString())
            assertEquals(0f, feedbackViewOf(service).alpha, 0.001f)

            // Tap ✕ -> collapses window WITHOUT stopping recording; shows reopen chip.
            service.liveWindowCloseButton!!.performClick()
            idleMain()
            assertEquals("Recording MUST stay active when collapsing Live Window", RecordingStateMachine.State.RECORDING, service.runtime.currentState())
            assertEquals(View.VISIBLE, win.visibility)
            assertEquals(0f, win.alpha, 0.001f)
            assertTrue(winParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(1f, chip.alpha, 0.001f)
            assertEquals(0, chipParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

            // 7:00 countdown while collapsed -> COUNTDOWN_PEEK header-only (body + footer GONE: ZERO transcript words!).
            listener.onRecordingCountdown(165_000L)
            idleMain()
            assertEquals(1f, win.alpha, 0.001f)
            assertEquals(View.GONE, service.liveWindowBodyScroll!!.visibility)
            assertEquals(View.GONE, service.liveWindowFooter!!.visibility)
            assertEquals(View.VISIBLE, service.liveWindowPeekReopenButton!!.visibility)
            assertTrue(service.liveWindowHeaderText!!.text.toString().contains("2:45 left"))

            // Tap "Show live" on peek bar -> EXPANDED with full transcript.
            service.liveWindowPeekReopenButton!!.performClick()
            idleMain()
            assertEquals(View.VISIBLE, service.liveWindowBodyScroll!!.visibility)
            assertEquals(View.GONE, service.liveWindowPeekReopenButton!!.visibility)
            assertEquals("Secret live transcript words", service.liveTranscriptTextView!!.text.toString())

            // Keyguard lock + SCREEN_OFF -> hides both Live Window & reopen chip via alpha=0 + NOT_TOUCHABLE + SCREEN_OFF_WINDOW_ALPHA, roots stay VISIBLE.
            shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            service.applyOverlayVisibility()
            idleMain()
            assertEquals(View.VISIBLE, win.visibility)
            assertEquals(0f, win.alpha, 0.001f)
            assertTrue(winParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, winParams.alpha, 0.0001f)
            assertEquals(View.VISIBLE, chip.visibility)
            assertEquals(0f, chip.alpha, 0.001f)
            assertTrue(chipParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, chipParams.alpha, 0.0001f)

            // Unlock + SCREEN_ON -> restores cleanly.
            shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
            service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            service.applyOverlayVisibility()
            idleMain()
            assertEquals(1f, win.alpha, 0.001f)
            assertEquals(1f, winParams.alpha, 0.0001f)

            // Dark <-> Light configuration change refreshes chrome without crashing.
            val lightConfig = Configuration(service.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }
            service.onConfigurationChanged(lightConfig)
            assertNotNull(win.background)
        } finally {
            service.onDestroy()
        }
    }

    // --- 3. DictationRuntime Reconciliation Policies & Lease Safety ---

    @Test fun `AUTO_REPLACE_WITH_BATCH delivers Batch result with non-null liveText attached for swap chip`() {
        configureBatchOpenAi("Full batch transcript")
        val listener = CapturingListener()
        val liveFactory = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { liveFactory },
            liveConfigProvider = {
                LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.AUTO_REPLACE_WITH_BATCH)
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }

        try {
            rt.onTap()
            val session = liveFactory.sessions.single()
            session.interim("Partial live words")
            idleMain()
            session.terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()

            rt.onTap()
            engines.single().finishAs(usablePcm())
            awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }

            val d = listener.deliveries.single()
            assertEquals("Full batch transcript", d.text)
            assertEquals("Partial live words", d.liveText)
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `KEEP_LIVE_ONLY skips Batch when Live text exists and falls back to Batch when Live is blank`() {
        val batchHits = AtomicInteger(0)
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", vaultKeyId = "vault_openai"))),
        )
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-keep-live")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                batchHits.incrementAndGet()
                return MockResponse.Builder().body("""{"text":"Batch fallback when live blank"}""").build()
            }
        }

        // Case 1: Live text captured -> skips Batch STT and deletes PCM.
        val l1 = CapturingListener()
        val lf1 = FakeLiveFactory()
        val eng1 = mutableListOf<FakeRecordingEngine>()
        var fallbacks1 = 0
        val rt1 = DictationRuntime(
            context = app,
            listener = l1,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf1 },
            onCloudLiveBatchFallback = { fallbacks1++ },
            liveConfigProvider = {
                LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.KEEP_LIVE_ONLY)
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng1 += it } }
        try {
            val pcm1 = usablePcm()
            rt1.onTap()
            lf1.sessions.single().interim("Captured live only text")
            idleMain()
            lf1.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt1.onTap()
            eng1.single().finishAs(pcm1)
            awaitCondition { rt1.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(0, fallbacks1)
            assertEquals(0, batchHits.get())
            assertFalse("Preserved PCM must be deleted", pcm1.exists())
            assertEquals(listOf("Captured live only text"), l1.deliveries.map { it.text })
        } finally {
            rt1.shutdown()
        }

        // Case 2: Live text was blank -> falls back seamlessly to Batch STT.
        val l2 = CapturingListener()
        val lf2 = FakeLiveFactory()
        val eng2 = mutableListOf<FakeRecordingEngine>()
        var fallbacks2 = 0
        val rt2 = DictationRuntime(
            context = app,
            listener = l2,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf2 },
            onCloudLiveBatchFallback = { fallbacks2++ },
            liveConfigProvider = {
                LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.KEEP_LIVE_ONLY)
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng2 += it } }
        try {
            rt2.onTap()
            lf2.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt2.onTap()
            eng2.single().finishAs(usablePcm())
            awaitCondition { rt2.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(1, fallbacks2)
            assertEquals(1, batchHits.get())
            assertEquals(listOf("Batch fallback when live blank"), l2.deliveries.map { it.text })
        } finally {
            rt2.shutdown()
        }
    }

    @Test fun `ASK_ME supports Keep Live, Use Batch ready, Use Batch queued, non-interactive fallback, timeout, dismiss, and new dictation tap`() {
        // 1. Keep Live immediately cancels in-flight Batch and releases lease.
        val gate1 = CountDownLatch(1)
        configureBatchOpenAi("Slow batch result", gate = gate1)
        val lease1 = InMemoryDictationSessionLeaseRegistry()
        val l1 = CapturingListener(interactiveReconciliation = true)
        val lf1 = FakeLiveFactory()
        val eng1 = mutableListOf<FakeRecordingEngine>()
        val rt1 = DictationRuntime(
            context = app,
            listener = l1,
            leaseRegistry = lease1,
            cloudLiveFactory = { lf1 },
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.ASK_ME) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng1 += it } }
        try {
            rt1.onTap()
            lf1.sessions.single().interim("Live choice text")
            idleMain()
            lf1.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt1.onTap()
            eng1.single().finishAs(usablePcm())
            awaitCondition { l1.reconciliationReady.isNotEmpty() }
            assertEquals("Live choice text" to true, l1.reconciliationReady.single())
            assertTrue(rt1.hasPendingReconciliation())

            rt1.chooseReconciliationKeepLive()
            idleMain()
            assertEquals(RecordingStateMachine.State.IDLE, rt1.currentState())
            assertFalse(rt1.hasPendingReconciliation())
            assertEquals(listOf("Live choice text"), l1.deliveries.map { it.text })
            gate1.countDown()
        } finally {
            gate1.countDown()
            rt1.shutdown()
        }

        // 2. Use Batch when already ready delivers Batch with liveText attached.
        configureBatchOpenAi("Ready batch result")
        val l2 = CapturingListener(interactiveReconciliation = true)
        val lf2 = FakeLiveFactory()
        val eng2 = mutableListOf<FakeRecordingEngine>()
        val rt2 = DictationRuntime(
            context = app,
            listener = l2,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf2 },
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.ASK_ME) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng2 += it } }
        try {
            rt2.onTap()
            lf2.sessions.single().interim("Earlier live words")
            idleMain()
            lf2.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt2.onTap()
            eng2.single().finishAs(usablePcm())
            awaitCondition { l2.reconciliationBatchReady.isNotEmpty() }
            assertEquals("Earlier live words" to "Ready batch result", l2.reconciliationBatchReady.single())
            assertTrue("Must remain in reconciliation until user chooses", rt2.hasPendingReconciliation())

            rt2.chooseReconciliationUseBatch()
            idleMain()
            assertEquals(RecordingStateMachine.State.IDLE, rt2.currentState())
            val d2 = l2.deliveries.single()
            assertEquals("Ready batch result", d2.text)
            assertEquals("Earlier live words", d2.liveText)
        } finally {
            rt2.shutdown()
        }

        // 3. Use Batch queued while Batch is still in flight auto-commits immediately on completion.
        val gate3 = CountDownLatch(1)
        configureBatchOpenAi("Queued batch result", gate = gate3)
        val l3 = CapturingListener(interactiveReconciliation = true)
        val lf3 = FakeLiveFactory()
        val eng3 = mutableListOf<FakeRecordingEngine>()
        val rt3 = DictationRuntime(
            context = app,
            listener = l3,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf3 },
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.ASK_ME) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng3 += it } }
        try {
            rt3.onTap()
            lf3.sessions.single().interim("Live while queued")
            idleMain()
            lf3.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt3.onTap()
            eng3.single().finishAs(usablePcm())
            awaitCondition { l3.reconciliationReady.isNotEmpty() }

            // Queue Use Batch BEFORE batch finishes.
            rt3.chooseReconciliationUseBatch()
            assertTrue(rt3.hasPendingReconciliation())
            gate3.countDown()
            awaitCondition { rt3.currentState() == RecordingStateMachine.State.IDLE }

            val d3 = l3.deliveries.single()
            assertEquals("Queued batch result", d3.text)
            assertEquals("Live while queued", d3.liveText)
        } finally {
            gate3.countDown()
            rt3.shutdown()
        }

        // 4. Non-interactive host (e.g. IME: supportsInteractiveLiveReconciliation == false) falls back cleanly.
        configureBatchOpenAi("IME auto batch result")
        val l4 = CapturingListener(interactiveReconciliation = false)
        val lf4 = FakeLiveFactory()
        val eng4 = mutableListOf<FakeRecordingEngine>()
        val rt4 = DictationRuntime(
            context = app,
            listener = l4,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf4 },
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.ASK_ME) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng4 += it } }
        try {
            rt4.onTap()
            lf4.sessions.single().interim("IME live words")
            idleMain()
            lf4.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt4.onTap()
            eng4.single().finishAs(usablePcm())
            awaitCondition { rt4.currentState() == RecordingStateMachine.State.IDLE }

            assertTrue(l4.reconciliationReady.isEmpty())
            assertEquals("IME auto batch result", l4.deliveries.single().text)
        } finally {
            rt4.shutdown()
        }

        // 5. 30-second timeout commits best available text and releases lease cleanly.
        configureBatchOpenAi("Timeout auto-commit batch")
        val lease5 = InMemoryDictationSessionLeaseRegistry()
        val l5 = CapturingListener(interactiveReconciliation = true)
        val lf5 = FakeLiveFactory()
        val eng5 = mutableListOf<FakeRecordingEngine>()
        val rt5 = DictationRuntime(
            context = app,
            listener = l5,
            leaseRegistry = lease5,
            cloudLiveFactory = { lf5 },
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.ASK_ME) },
            reconciliationTimeoutMs = 500L,
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng5 += it } }
        try {
            rt5.onTap()
            lf5.sessions.single().interim("Live before timeout")
            idleMain()
            lf5.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt5.onTap()
            eng5.single().finishAs(usablePcm())
            awaitCondition { l5.reconciliationBatchReady.isNotEmpty() }

            shadowOf(Looper.getMainLooper()).idleFor(600L, TimeUnit.MILLISECONDS)
            assertEquals(RecordingStateMachine.State.IDLE, rt5.currentState())
            assertFalse(rt5.hasPendingReconciliation())
            assertEquals("Timeout auto-commit batch", l5.deliveries.single().text)
            assertEquals("Live before timeout", l5.deliveries.single().liveText)

            // Verify lease is completely released: a brand-new dictation starts cleanly.
            rt5.onTap()
            assertEquals(RecordingStateMachine.State.RECORDING, rt5.currentState())
        } finally {
            rt5.shutdown()
        }

        // 6. Tapping bubble to start a new dictation while ASK_ME is open clears reconciliation and starts cleanly.
        configureBatchOpenAi("Abandoned batch")
        val l6 = CapturingListener(interactiveReconciliation = true)
        val lf6 = FakeLiveFactory()
        val eng6 = mutableListOf<FakeRecordingEngine>()
        val rt6 = DictationRuntime(
            context = app,
            listener = l6,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf6 },
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true, disconnectPolicy = LiveDisconnectPolicy.ASK_ME) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng6 += it } }
        try {
            rt6.onTap()
            lf6.sessions.single().interim("First session live")
            idleMain()
            lf6.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            rt6.onTap()
            eng6.single().finishAs(usablePcm())
            awaitCondition { l6.reconciliationBatchReady.isNotEmpty() }

            // User taps bubble to start a new dictation instead of choosing -> old lease released, new recording active!
            rt6.onTap()
            idleMain()
            assertFalse(rt6.hasPendingReconciliation())
            assertEquals(RecordingStateMachine.State.RECORDING, rt6.currentState())
            assertTrue(l6.deliveries.isEmpty())
        } finally {
            rt6.shutdown()
        }
    }

    @Test fun `finishWithCapturedLiveText delivers live immediately after cut while forceDiscardRecording truly discards`() {
        // 1. finishWithCapturedLiveText during post-cut RECORDING
        val l1 = CapturingListener()
        val lf1 = FakeLiveFactory()
        val rt1 = DictationRuntime(
            context = app,
            listener = l1,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf1 },
        ) { dir, sm -> FakeRecordingEngine(dir, sm) }
        try {
            rt1.onTap()
            lf1.sessions.single().interim("Captured before cut")
            idleMain()
            lf1.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            assertEquals(RecordingStateMachine.State.RECORDING, rt1.currentState())

            rt1.finishWithCapturedLiveText()
            idleMain()
            assertEquals(RecordingStateMachine.State.IDLE, rt1.currentState())
            assertEquals(listOf("Captured before cut"), l1.deliveries.map { it.text })
        } finally {
            rt1.shutdown()
        }

        // 2. forceDiscardRecording truly discards without inserting anything even with live text captured
        val l2 = CapturingListener()
        val lf2 = FakeLiveFactory()
        val rt2 = DictationRuntime(
            context = app,
            listener = l2,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf2 },
        ) { dir, sm -> FakeRecordingEngine(dir, sm) }
        try {
            rt2.onTap()
            lf2.sessions.single().interim("Bad dictation to discard")
            idleMain()
            lf2.sessions.single().terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()

            assertTrue(rt2.forceDiscardRecording())
            idleMain()
            assertEquals(RecordingStateMachine.State.IDLE, rt2.currentState())
            assertTrue("forceDiscardRecording must deliver nothing", l2.deliveries.isEmpty())
        } finally {
            rt2.shutdown()
        }
    }

    @Test fun `Live Post-Cleanup passes liveText alongside rawText for Use live swap chip`() {
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-live-clean")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().body("""{"choices":[{"message":{"content":"Cleaned live output sentence."}}]}""").build()
        }
        val listener = CapturingListener()
        val lf = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf },
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    postCleanupEnabled = true,
                    postCleanupSlots = listOf(
                        LivePostCleanupSlot("s1", ProviderCapabilityRegistry.ID_OPENAI, "gpt-5.4-mini", vaultKeyId = "vault_openai", baseUrlOverride = base, attempts = 1, locked = true)
                    ),
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }
        try {
            rt.onTap()
            rt.onTap()
            engines.single().finishAs(usablePcm())
            lf.sessions.single().terminal(CloudLiveTerminal.Success("raw live output sentence", CloudLiveTiming(1L, finalAtMs = 2L)))
            awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }

            val d = listener.deliveries.single()
            assertEquals("Cleaned live output sentence.", d.text)
            assertEquals("raw live output sentence", d.rawText)
            assertEquals("raw live output sentence", d.liveText)
        } finally {
            rt.shutdown()
        }
    }

    // --- 4. WhisperAccessibilityService Swap Chips (Use live / Use raw + ✕) & feedbackView.alpha sync ---

    @Test fun `swap chips offer Use live with X, swap cleanly, dismiss cleanly, sync feedbackView alpha, and omit X on transient banners`() {
        val service = buildService()
        try {
            // Prepare a DIRECT-injectable AccessibilityNodeInfo backed by Robolectric's shadow so
            // both finishInjection (obtain copy) and swapInjectedForRaw in-place rewrite execute cleanly.
            var nodeText = "Draft:"
            val editableNode = object : AccessibilityNodeInfo() {
                override fun isEditable(): Boolean = true
                override fun isFocused(): Boolean = true
                override fun getClassName(): CharSequence = "android.widget.EditText"
                override fun getPackageName(): CharSequence = "com.example.notes"
                override fun getText(): CharSequence = nodeText
                override fun getTextSelectionStart(): Int = nodeText.length
                override fun getTextSelectionEnd(): Int = nodeText.length
                override fun isShowingHintText(): Boolean = false
                override fun isPassword(): Boolean = false
                override fun refresh(): Boolean = true
                override fun recycle() {}
                override fun findFocus(focus: Int): AccessibilityNodeInfo? =
                    if (focus == FOCUS_INPUT) this else null
                override fun performAction(action: Int, arguments: Bundle?): Boolean {
                    if (action == ACTION_SET_TEXT) {
                        nodeText = arguments?.getCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                        text = nodeText
                        setTextSelection(nodeText.length, nodeText.length)
                        return true
                    }
                    return action == ACTION_SET_SELECTION
                }
            }.apply {
                isEditable = true
                isFocused = true
                className = "android.widget.EditText"
                packageName = "com.example.notes"
                text = nodeText
                setTextSelection(nodeText.length, nodeText.length)
            }
            shadowOf(editableNode).setOnPerformActionListener { action, arguments ->
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    nodeText = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                    editableNode.text = nodeText
                    true
                } else {
                    action == AccessibilityNodeInfo.ACTION_SET_SELECTION
                }
            }
            service.fakeRoot = editableNode

            val listener = runtimeListenerOf(service)
            val feedbackView = feedbackViewOf(service)
            val feedbackContainer = service.feedbackContainer!!
            val useLiveBtn = service.feedbackUseLiveButton!!
            val useRawBtn = service.feedbackUseRawButton!!
            val closeBtn = service.feedbackCloseButton!!

            // 1. Transient retry/error banner has NO ✕ and NO swap chips.
            listener.onStageStatus("OpenAI busy — retrying…")
            idleMain()
            assertEquals(View.GONE, closeBtn.visibility)
            assertEquals(View.GONE, useLiveBtn.visibility)
            assertEquals(View.GONE, useRawBtn.visibility)
            assertEquals(feedbackContainer.alpha, feedbackView.alpha, 0.001f)

            // 2. Post-Batch delivery with distinct liveText + rawText shows Use live, Use raw, and ✕.
            listener.deliverText(
                text = "Cleaned batch sentence.",
                rawText = "raw batch sentence",
                paidFallbackGroup = null,
                cleanupError = null,
                feedbackDurationMs = 2000L,
                liveText = "original live sentence",
            )
            idleMain()
            assertEquals("Draft: Cleaned batch sentence.", nodeText)
            assertEquals(View.VISIBLE, useLiveBtn.visibility)
            assertEquals(View.VISIBLE, useRawBtn.visibility)
            assertEquals(View.VISIBLE, closeBtn.visibility)
            assertEquals(1f, feedbackContainer.alpha, 0.001f)
            assertEquals(1f, feedbackView.alpha, 0.001f)

            // Attach the live mutable node onto pendingInjection so replaceConfirmed reads updated text back.
            val pendingField = WhisperAccessibilityService::class.java.getDeclaredField("pendingInjection")
                .apply { isAccessible = true }
            val pendingObj = pendingField.get(service)!!
            val pendingNodeField = pendingObj.javaClass.getDeclaredField("node").apply { isAccessible = true }
            pendingNodeField.set(pendingObj, editableNode)

            // Tapping Use live swaps text in place via swapInjectedForRaw and hides swap buttons.
            useLiveBtn.performClick()
            idleMain()
            assertEquals("Draft: original live sentence", nodeText)
            assertEquals("Live text inserted", feedbackView.text.toString())
            assertEquals(View.GONE, useLiveBtn.visibility)
            assertEquals(View.GONE, useRawBtn.visibility)
            assertEquals(View.GONE, closeBtn.visibility)

            // 3. Post-Live-Cleanup delivery (liveText == rawText) shows Use live + ✕ (no duplicate Use raw).
            nodeText = ""
            editableNode.text = ""
            editableNode.setTextSelection(0, 0)
            listener.deliverText(
                text = "Polished live note.",
                rawText = "raw live note",
                paidFallbackGroup = null,
                cleanupError = null,
                feedbackDurationMs = 2000L,
                liveText = "raw live note",
            )
            idleMain()
            assertEquals("Polished live note.", nodeText)
            assertEquals(View.VISIBLE, useLiveBtn.visibility)
            assertEquals(View.GONE, useRawBtn.visibility)
            assertEquals(View.VISIBLE, closeBtn.visibility)

            // Tapping ✕ dismisses cleanly and keeps feedbackView.alpha synced with feedbackContainer.alpha.
            closeBtn.performClick()
            idleMain()
            assertEquals(0f, feedbackContainer.alpha, 0.001f)
            assertEquals(0f, feedbackView.alpha, 0.001f)
            assertEquals(View.GONE, closeBtn.visibility)
            assertEquals(View.GONE, useLiveBtn.visibility)

            // 4. Safe clipboard fallback when field was manually edited so in-place swap refuses safely.
            nodeText = ""
            editableNode.text = ""
            editableNode.setTextSelection(0, 0)
            listener.deliverText(
                text = "Batch final text.",
                rawText = null,
                paidFallbackGroup = null,
                cleanupError = null,
                feedbackDurationMs = 2000L,
                liveText = "Live clipboard fallback",
            )
            idleMain()
            val pendingObj4 = pendingField.get(service)!!
            pendingNodeField.set(pendingObj4, editableNode)
            nodeText = "User manually edited something else"
            editableNode.text = nodeText
            useLiveBtn.performClick()
            idleMain()
            assertEquals("Live text copied", feedbackView.text.toString())
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("Live clipboard fallback", cm.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            service.onDestroy()
        }
    }

    // --- Helpers ---

    private fun buildService(): TestService {
        val service = Robolectric.buildService(TestService::class.java, null).create().get()
        val runtimeListener = runtimeListenerOf(service)
        val runtime = DictationRuntime(
            app,
            runtimeListener,
            InMemoryDictationSessionLeaseRegistry(),
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }.set(service, runtime)
        WhisperAccessibilityService::class.java.getDeclaredMethod("registerScreenStateReceiver")
            .apply { isAccessible = true }.invoke(service)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }.invoke(service)
        idleMain()
        return service
    }

    private fun runtimeListenerOf(service: WhisperAccessibilityService): RuntimeListener =
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener

    private fun feedbackViewOf(service: WhisperAccessibilityService): TextView =
        WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
            .apply { isAccessible = true }.get(service) as TextView

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun hasWidgetType(root: View, cls: Class<*>): Boolean {
        if (cls.isInstance(root)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasWidgetType(root.getChildAt(i), cls)) return true
            }
        }
        return false
    }

    private fun tapRowContaining(root: View, text: String) {
        fun find(v: View): TextView? {
            if (v is TextView && v.text.toString() == text) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var cur: View? = find(root) ?: error("No row with text '$text'")
        while (cur != null && !cur.isClickable) cur = cur.parent as? View
        assertNotNull(cur)
        cur!!.performClick()
    }
}
