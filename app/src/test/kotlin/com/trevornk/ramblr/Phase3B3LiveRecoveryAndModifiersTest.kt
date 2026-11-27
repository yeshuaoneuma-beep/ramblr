package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3B3LiveRecoveryAndModifiersTest {

    class TestService : WhisperAccessibilityService() {
        var fakeRoot: AccessibilityNodeInfo? = null
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? = fakeRoot
    }

    private class CapturingListener(
        var interactive: Boolean = true,
    ) : RuntimeListener {
        data class Delivery(
            val text: String,
            val rawText: String?,
            val liveText: String?,
            val cleanupError: String?,
        )

        val deliveries = mutableListOf<Delivery>()
        val interims = mutableListOf<String>()
        val pausedEvents = mutableListOf<Pair<String, LiveDisconnectEvent>>()
        val continuedPrefixes = mutableListOf<String>()
        var connectingCount = 0
        var connectedCount = 0
        var cleaningCount = 0
        var idleCount = 0

        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onCleaningStarted() { cleaningCount++ }
        override fun onIdleUi() { idleCount++ }
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveConnecting() { connectingCount++ }
        override fun onCloudLiveConnected() { connectedCount++ }
        override fun onCloudLiveInterim(text: String) { interims += text }
        override fun onCloudLiveInterim(text: String, bufferOnly: Boolean) { interims += text }
        override fun onCloudLivePausedForContinue(accumulatedLiveText: String, event: LiveDisconnectEvent) {
            pausedEvents += accumulatedLiveText to event
        }
        override fun onCloudLiveContinued(preservedPrefix: String) {
            continuedPrefixes += preservedPrefix
        }
        override fun supportsInteractiveLiveReconciliation(): Boolean = interactive
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

        fun finishAs(pcmFile: File, discarded: Boolean = false) {
            if (!discarded) stateMachine.tryStartTranscribing()
            onFinished!!(
                Result(
                    pcmFile = if (discarded) null else pcmFile,
                    bytesRecorded = if (discarded) 0L else pcmFile.length(),
                    discarded = discarded,
                    stopReason = StopReason.USER,
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
        fun connected() = listener.onConnected()
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
        DictationHistoryStore.forContext(app).clear()
        QualityLogger.logFile(app).delete()
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
        DictationHistoryStore.forContext(app).clear()
        QualityLogger.logFile(app).delete()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun usablePcm(): File =
        File.createTempFile("p3b3_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(32_000)) }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) {
            idleMain()
            Thread.sleep(10)
        }
        idleMain()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    // --- 1. LiveRecoveryMode serialization & LiveTranscriptionActivity mutual-exclusivity UI ---

    @Test fun `LiveRecoveryMode round-trips and LiveTranscriptionActivity enforces mutual exclusivity with zero RadioButtons`() {
        for (mode in LiveRecoveryMode.entries) {
            val cfg = LiveTranscriptionConfig(enabled = true, recoveryMode = mode)
            val parsed = LiveTranscriptionConfigStore.deserialize(LiveTranscriptionConfigStore.serialize(cfg))!!
            assertEquals(mode, parsed.recoveryMode)
        }
        // Legacy JSON and unknown enum values default safely to PARALLEL_BATCH_FALLBACK.
        assertEquals(
            LiveRecoveryMode.PARALLEL_BATCH_FALLBACK,
            LiveTranscriptionConfigStore.deserialize("""{"enabled":true}""")!!.recoveryMode,
        )
        assertEquals(
            LiveRecoveryMode.PARALLEL_BATCH_FALLBACK,
            LiveTranscriptionConfigStore.deserialize("""{"enabled":true,"recoveryMode":"UNKNOWN_FUTURE"}""")!!.recoveryMode,
        )

        val controller = Robolectric.buildActivity(LiveTranscriptionActivity::class.java).setup()
        val activity = controller.get()
        try {
            val root = activity.window.decorView
            assertFalse("Zero RadioButtons allowed", hasWidgetType(root, RadioButton::class.java))
            assertFalse("Zero RadioGroups allowed", hasWidgetType(root, RadioGroup::class.java))
            val texts = collectTexts(root)
            assertTrue(texts.contains("Parallel audio fallback (Batch STT)"))
            assertTrue(texts.contains("Continue / restart Live session"))

            // Switch to CONTINUE_LIVE_SESSION -> persists and dims/disables Batch disconnect sub-options.
            tapRowContaining(root, "Continue / restart Live session")
            assertEquals(LiveRecoveryMode.CONTINUE_LIVE_SESSION, LiveTranscriptionConfigStore.load(app).recoveryMode)
            val afterTexts = collectTexts(activity.window.decorView)
            assertTrue(afterTexts.any { it.contains("Only applies in Parallel audio fallback", ignoreCase = true) })
            val askRow = findRowContaining(activity.window.decorView, "Ask me (Keep Live or Use Batch)")!!
            assertFalse("Batch sub-options must be non-clickable in Continue mode", askRow.isClickable)

            // Switch back to PARALLEL_BATCH_FALLBACK -> Batch sub-options re-enabled.
            tapRowContaining(activity.window.decorView, "Parallel audio fallback (Batch STT)")
            assertEquals(LiveRecoveryMode.PARALLEL_BATCH_FALLBACK, LiveTranscriptionConfigStore.load(app).recoveryMode)
            assertTrue(findRowContaining(activity.window.decorView, "Ask me (Keep Live or Use Batch)")!!.isClickable)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- 2. CONTINUE_LIVE_SESSION pause, Continue append, Cleanup, Insert, Discard, and bubble tap ---

    @Test fun `CONTINUE_LIVE_SESSION pauses without Batch fallback, appends on Continue, and supports Cleanup, Insert, Discard, and bubble tap`() {
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-p3b3-clean")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .body("""{"choices":[{"message":{"content":"Part one, and part two."}}]}""")
                    .build()
        }
        val continueConfig = LiveTranscriptionConfig(
            enabled = true,
            recoveryMode = LiveRecoveryMode.CONTINUE_LIVE_SESSION,
            postCleanupEnabled = true,
            postCleanupSlots = listOf(
                LivePostCleanupSlot("s1", ProviderCapabilityRegistry.ID_OPENAI, "gpt-5.4-mini", vaultKeyId = "vault_openai", baseUrlOverride = base, attempts = 1, locked = true),
            ),
        )

        // Scenario A: Disconnect -> pause (no Batch, no auto-cleanup/insert) -> Continue -> append -> Cleanup
        val l1 = CapturingListener()
        val lf1 = FakeLiveFactory()
        val eng1 = mutableListOf<FakeRecordingEngine>()
        var batchFallbacks1 = 0
        val rt1 = DictationRuntime(
            context = app,
            listener = l1,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf1 },
            onCloudLiveBatchFallback = { batchFallbacks1++ },
            liveConfigProvider = { continueConfig },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng1 += it } }
        try {
            rt1.onTap()
            assertEquals(1, l1.connectingCount)
            assertEquals(0, l1.connectedCount)
            lf1.sessions[0].connected()
            idleMain()
            assertEquals(1, l1.connectedCount)

            lf1.sessions[0].interim("Part one")
            idleMain()
            lf1.sessions[0].terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.STREAM_STALLED, "stalled", CloudLiveTiming(1L)))
            idleMain()
            // Simulate reader thread finishing after stateMachine.reset()
            eng1[0].finishAs(usablePcm(), discarded = true)
            idleMain()

            assertEquals(0, batchFallbacks1)
            assertTrue("Must enter PendingLiveContinue", rt1.hasPendingContinue())
            assertEquals(RecordingStateMachine.State.IDLE, rt1.currentState())
            assertEquals(1, l1.pausedEvents.size)
            assertEquals("Part one", l1.pausedEvents.single().first)
            assertFalse(l1.pausedEvents.single().second.continuingLocalRecording)
            assertTrue("Must NEVER auto-insert while paused", l1.deliveries.isEmpty())
            assertEquals("Must NEVER auto-run cleanup while paused", 0, l1.cleaningCount)

            // User taps Continue -> opens fresh session #2 on SAME lease, appends below "Part one"
            rt1.continueLiveSession()
            idleMain()
            assertFalse(rt1.hasPendingContinue())
            assertEquals(RecordingStateMachine.State.RECORDING, rt1.currentState())
            assertEquals(2, lf1.sessions.size)
            assertEquals(listOf("Part one"), l1.continuedPrefixes)
            lf1.sessions[1].connected()
            lf1.sessions[1].interim("and part two")
            idleMain()
            assertEquals("Part one and part two", l1.interims.last())

            // Disconnect again, then user taps Cleanup
            lf1.sessions[1].terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.SESSION_LIMIT, "9:00", CloudLiveTiming(2L)))
            idleMain()
            eng1[1].finishAs(usablePcm(), discarded = true)
            idleMain()
            assertTrue(rt1.hasPendingContinue())
            assertEquals("Part one and part two", l1.pausedEvents.last().first)

            rt1.finishContinueWithPostCleanup()
            awaitCondition { rt1.currentState() == RecordingStateMachine.State.IDLE && l1.deliveries.isNotEmpty() }
            assertEquals(0, batchFallbacks1)
            assertEquals("Part one, and part two.", l1.deliveries.single().text)
            assertEquals("Part one and part two", l1.deliveries.single().rawText)
            assertFalse(rt1.hasPendingContinue())
        } finally {
            rt1.shutdown()
            idleMain()
        }

        // Scenario B: Insert raw & Discard & main-bubble tap (postCleanup OFF -> inserts raw)
        val l2 = CapturingListener()
        val lf2 = FakeLiveFactory()
        val eng2 = mutableListOf<FakeRecordingEngine>()
        val rt2 = DictationRuntime(
            context = app,
            listener = l2,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf2 },
            liveConfigProvider = { continueConfig.copy(postCleanupEnabled = false) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { eng2 += it } }
        try {
            rt2.onTap()
            lf2.sessions[0].interim("Raw paused text")
            idleMain()
            lf2.sessions[0].terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(1L)))
            idleMain()
            eng2[0].finishAs(usablePcm(), discarded = true)
            idleMain()
            // Main-bubble tap while paused with postCleanupEnabled = false -> inserts raw and releases lease!
            rt2.onTap()
            idleMain()
            assertEquals(listOf("Raw paused text"), l2.deliveries.map { it.text })
            assertNull(l2.deliveries.single().rawText)
            assertFalse(rt2.hasPendingContinue())

            // Next dictation starts cleanly (lease released), then Discard drops text
            rt2.onTap()
            lf2.sessions[1].interim("Throwaway text")
            idleMain()
            lf2.sessions[1].terminal(CloudLiveTerminal.Failure(CloudLiveFailureReason.NETWORK_ERROR, "drop", CloudLiveTiming(2L)))
            idleMain()
            eng2[1].finishAs(usablePcm(), discarded = true)
            idleMain()
            rt2.discardContinueSession()
            idleMain()
            assertEquals(1, l2.deliveries.size)
            assertFalse(rt2.hasPendingContinue())
            // Lease released: can start again
            rt2.onTap()
            assertEquals(RecordingStateMachine.State.RECORDING, rt2.currentState())
        } finally {
            rt2.shutdown()
            idleMain()
        }
    }

    @Test fun `active-streaming Insert raw button delivers raw Live text immediately skipping Post-Cleanup`() {
        val l = CapturingListener()
        val lf = FakeLiveFactory()
        val rt = DictationRuntime(
            context = app,
            listener = l,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf },
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    postCleanupEnabled = true,
                    recoveryMode = LiveRecoveryMode.PARALLEL_BATCH_FALLBACK,
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm) }
        try {
            rt.onTap()
            lf.sessions.single().connected()
            lf.sessions.single().interim("Insert me raw right now")
            idleMain()

            rt.finishWithCapturedLiveText()
            idleMain()
            assertEquals(RecordingStateMachine.State.IDLE, rt.currentState())
            assertEquals(0, l.cleaningCount)
            assertEquals("Insert me raw right now", l.deliveries.single().text)
            assertNull(l.deliveries.single().rawText)
        } finally {
            rt.shutdown()
        }
    }

    // --- 3. Collapsed Disconnect Amber Chips, LIVE_CONNECTING, & LiveWindowStateMachine guards ---

    @Test fun `collapsed Live Window shows amber disconnect chips with zero transcript words and preserves BLASTSyncEngine invariants`() {
        LiveTranscriptionConfigStore.save(
            app,
            LiveTranscriptionConfig(
                enabled = true,
                liveWindowEnabled = true,
                postCleanupEnabled = true,
                recoveryMode = LiveRecoveryMode.CONTINUE_LIVE_SESSION,
            ),
        )
        val service = buildService()
        try {
            val listener = runtimeListenerOf(service)
            service.runtime.onTap()
            idleMain()

            // LIVE_CONNECTING shows Connecting header/body before setupComplete.
            listener.onCloudLiveConnecting()
            idleMain()
            assertEquals("LIVE \u00b7 Connecting\u2026", service.liveWindowHeaderText!!.text.toString())
            assertEquals("Connecting\u2026", service.liveTranscriptTextView!!.text.toString())

            // Connected -> LIVE_STREAMING with Insert + Discard when text arrives.
            listener.onCloudLiveConnected()
            listener.onCloudLiveInterim("Confidential secret phrase for streaming")
            idleMain()
            assertEquals(View.VISIBLE, service.liveWindowPrimaryActionButton!!.visibility)
            assertEquals("Insert", service.liveWindowPrimaryActionButton!!.text.toString())
            assertEquals("Discard", service.liveWindowSecondaryActionButton!!.text.toString())

            // Collapse via ✕ -> normal "Show live" chip (no transcript words).
            service.liveWindowCloseButton!!.performClick()
            idleMain()
            val chip = service.liveReopenChipView!!
            val chipParams = service.liveReopenChipParams!!
            assertEquals(View.VISIBLE, chip.visibility)
            assertEquals(1f, chip.alpha, 0.001f)
            assertEquals("Show live", service.liveReopenChipPrimaryButton!!.text.toString())
            assertEquals(View.GONE, service.liveReopenChipSecondaryButton!!.visibility)

            // Disconnect in CONTINUE_LIVE_SESSION while collapsed -> amber "Live cut · Continue" + "Show", ZERO transcript words!
            listener.onCloudLivePausedForContinue(
                "Confidential secret phrase for streaming",
                LiveDisconnectEvent("stream_stalled", 45, continuingLocalRecording = false),
            )
            idleMain()
            assertEquals(0f, service.liveWindowView!!.alpha, 0.001f)
            assertEquals(1f, chip.alpha, 0.001f)
            val chipTexts = collectTexts(chip)
            assertTrue(chipTexts.contains("Live cut \u00b7 Continue"))
            assertTrue(chipTexts.contains("Show"))
            assertFalse("Chip must NEVER contain transcript words", chipTexts.any { it.contains("Confidential") })

            // Reopen via Show -> expanded with Continue, Cleanup, Insert, Discard buttons.
            service.liveReopenChipSecondaryButton!!.performClick()
            idleMain()
            assertEquals(1f, service.liveWindowView!!.alpha, 0.001f)
            assertEquals("Continue", service.liveWindowPrimaryActionButton!!.text.toString())
            assertEquals("Cleanup", service.liveWindowTertiaryActionButton!!.text.toString())
            assertEquals("Insert", service.liveWindowQuaternaryActionButton!!.text.toString())
            assertEquals("Discard", service.liveWindowSecondaryActionButton!!.text.toString())

            // Verify collapsed PARALLEL_BATCH_FALLBACK chip text ("Live cut · Batch" + "Show")
            service.liveWindowCloseButton!!.performClick()
            idleMain()
            listener.onCloudLiveDisconnected(LiveDisconnectEvent("network_error", 30, continuingLocalRecording = true))
            idleMain()
            val batchChipTexts = collectTexts(chip)
            assertTrue(batchChipTexts.contains("Live cut \u00b7 Batch"))
            assertTrue(batchChipTexts.contains("Show"))

            // Keyguard + SCREEN_OFF BLASTSyncEngine invariants hold on LinearLayout reopen chip too!
            shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            service.applyOverlayVisibility()
            idleMain()
            assertEquals(View.VISIBLE, chip.visibility)
            assertEquals(0f, chip.alpha, 0.001f)
            assertTrue(chipParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(WhisperAccessibilityService.SCREEN_OFF_WINDOW_ALPHA, chipParams.alpha, 0.0001f)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun `LiveWindowStateMachine onInterim protects long transcript from tiny unrelated fragment wipe`() {
        val sm = LiveWindowStateMachine()
        sm.onLiveStarted(windowEnabled = true, overlayAllowed = true)
        val longText = "We need to finish the quarterly deployment checklist before five o'clock today."
        sm.onInterim(longText)
        assertEquals(longText, sm.transcript)
        // Tiny unrelated fragment must NOT wipe the established 12-word transcript.
        sm.onInterim("um yeah")
        assertEquals(longText, sm.transcript)
        // Full cumulative update still updates cleanly.
        val extended = "$longText Also verify backups."
        sm.onInterim(extended)
        assertEquals(extended, sm.transcript)
    }

    // --- 4. CleanupModifiersPrefs, PostProcessor.withCleanupModifiers, & CleanupStylesActivity ---

    @Test fun `CleanupModifiersPrefs defaults and CleanupStylesActivity switches wire cleanly`() {
        assertTrue(CleanupModifiersPrefs.isSmartSelfCorrectionEnabled(app))
        assertFalse(CleanupModifiersPrefs.isSpokenEmojiCommandsEnabled(app))

        val base = PostProcessor.NATURAL_PROMPT
        val withSelfOnly = PostProcessor.withCleanupModifiers(base, smartSelfCorrection = true, spokenEmojiCommands = false)
        assertTrue(withSelfOnly.contains("Smart self-correction"))
        assertFalse(withSelfOnly.contains("Spoken emoji commands"))
        assertTrue("preservesWording must remain true", PostProcessor.preservesWording(withSelfOnly))

        val withBoth = PostProcessor.withCleanupModifiers(base, smartSelfCorrection = true, spokenEmojiCommands = true)
        assertTrue(withBoth.contains("Smart self-correction"))
        assertTrue(withBoth.contains("Spoken emoji commands"))

        val withNeither = PostProcessor.withCleanupModifiers(base, smartSelfCorrection = false, spokenEmojiCommands = false)
        assertEquals(base, withNeither)

        val controller = Robolectric.buildActivity(CleanupStylesActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            val texts = collectTexts(root)
            assertTrue(texts.contains("Smart self-correction"))
            assertTrue(texts.contains("Spoken emoji commands"))

            tapRowContaining(root, "Smart self-correction")
            assertFalse(CleanupModifiersPrefs.isSmartSelfCorrectionEnabled(app))

            tapRowContaining(root, "Spoken emoji commands")
            assertTrue(CleanupModifiersPrefs.isSpokenEmojiCommandsEnabled(app))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // --- 5. History retains cleanedText + liveText + rawText when Use live / Use raw swaps ---

    @Test fun `Use live swap in WhisperAccessibilityService preserves cleanedText in DictationHistoryStore and updates insertedSource`() {
        val service = buildService()
        try {
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
                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun recycle() {}
                override fun findFocus(focus: Int): AccessibilityNodeInfo? = if (focus == FOCUS_INPUT) this else null
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
            listener.deliverText(
                text = "Polished batch result.",
                rawText = "raw batch result",
                paidFallbackGroup = CleanupStepGroup.OPENAI_DIRECT,
                cleanupError = null,
                feedbackDurationMs = 2000L,
                liveText = "live streaming draft",
            )
            idleMain()

            val pendingField = WhisperAccessibilityService::class.java.getDeclaredField("pendingInjection")
                .apply { isAccessible = true }
            val pendingObj = pendingField.get(service)!!
            pendingObj.javaClass.getDeclaredField("node").apply { isAccessible = true }.set(pendingObj, editableNode)

            service.feedbackUseLiveButton!!.performClick()
            idleMain()

            val store = DictationHistoryStore.forContext(app)
            awaitCondition { store.all().singleOrNull()?.insertedSource == "live" }
            val saved = store.all().single()
            assertEquals("raw batch result", saved.rawText)
            assertEquals("Polished batch result.", saved.cleanedText)
            assertEquals("live streaming draft", saved.liveText)
            assertEquals("live", saved.insertedSource)
            assertEquals(CleanupStepGroup.OPENAI_DIRECT, saved.paidFallbackGroup)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun `QualityLogger records GEMINI_LIVE raw transcription and Live Post-Cleanup pair when enabled`() {
        QualityLogger.setEnabled(app, true)
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-qlog-test")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().body("""{"choices":[{"message":{"content":"Cleaned live quality text."}}]}""").build()
        }
        val l = CapturingListener()
        val lf = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        val rt = DictationRuntime(
            context = app,
            listener = l,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { lf },
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    modelId = LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID,
                    postCleanupEnabled = true,
                    postCleanupSlots = listOf(
                        LivePostCleanupSlot("s1", ProviderCapabilityRegistry.ID_OPENAI, "gpt-5.4-mini", vaultKeyId = "vault_openai", baseUrlOverride = base, attempts = 1, locked = true),
                    ),
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }
        try {
            rt.onTap()
            rt.onTap()
            engines.single().finishAs(usablePcm())
            lf.sessions.single().terminal(CloudLiveTerminal.Success("raw live quality text", CloudLiveTiming(1L, finalAtMs = 2L)))
            awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }

            val lines = QualityLogger.logFile(app).readLines().filter { it.isNotBlank() }
            assertTrue("Expected GEMINI_LIVE transcription line in quality log", lines.any { it.contains("GEMINI_LIVE") && it.contains("raw live quality text") })
            assertTrue("Expected cleanedText line in quality log", lines.any { it.contains("Cleaned live quality text.") })
        } finally {
            rt.shutdown()
        }
    }

    // --- 6. AppNotificationStore shade sync & phantom badge elimination ---

    @Test fun `AppNotificationStore posts 0x1C0B shade alert for actionable categories, cancels on markAllRead-clear, and disables badges on ongoing channels`() {
        val nm = app.getSystemService(NotificationManager::class.java)!!
        val shadowNm = shadowOf(nm)

        // DICTATION_ALERT must NOT post 0x1C0B (BackgroundDictationNotifications already posts RESULT_ID).
        AppNotificationStore.record(
            context = app,
            category = AppNotificationCategory.DICTATION_ALERT,
            title = "Dictation failed",
            body = "Could not transcribe",
        )
        assertNull(shadowNm.getNotification(AppAlertNotifications.ALERT_NOTIFICATION_ID))

        // LIVE_DISCONNECT & FALLBACK post/update 0x1C0B with unread count.
        AppNotificationStore.record(
            context = app,
            category = AppNotificationCategory.LIVE_DISCONNECT,
            title = "Live transcription disconnected",
            body = "Paused with Continue controls.",
        )
        val posted1 = shadowNm.getNotification(AppAlertNotifications.ALERT_NOTIFICATION_ID)
        assertNotNull("Expected shade alert 0x1C0B for LIVE_DISCONNECT", posted1)
        assertEquals(2, posted1!!.number)

        AppNotificationStore.record(
            context = app,
            category = AppNotificationCategory.FALLBACK,
            title = "Fell back to on-device",
            body = "Switched to local model.",
        )
        val posted2 = shadowNm.getNotification(AppAlertNotifications.ALERT_NOTIFICATION_ID)
        assertNotNull(posted2)
        assertEquals(3, posted2!!.number)

        // Also post RESULT_ID to verify markAllRead cancels BOTH 0x1C0B and RESULT_ID.
        BackgroundDictationNotifications.postResult(app, BackgroundNotice("Test", "Body", opensHistory = false, isFailure = true))
        assertNotNull(shadowNm.getNotification(BackgroundDictationNotifications.RESULT_ID))

        AppNotificationStore.markAllRead(app)
        assertNull("markAllRead must cancel 0x1C0B", shadowNm.getNotification(AppAlertNotifications.ALERT_NOTIFICATION_ID))
        assertNull("markAllRead must cancel RESULT_ID", shadowNm.getNotification(BackgroundDictationNotifications.RESULT_ID))

        // Re-post and test clear().
        AppNotificationStore.record(app, AppNotificationCategory.UPDATE, "Update ready", "New version available")
        assertNotNull(shadowNm.getNotification(AppAlertNotifications.ALERT_NOTIFICATION_ID))
        AppNotificationStore.clear(app)
        assertNull("clear must cancel 0x1C0B", shadowNm.getNotification(AppAlertNotifications.ALERT_NOTIFICATION_ID))

        // Verify phantom launcher badge elimination: setShowBadge(false) on all ongoing/progress/utility channels.
        BackgroundDictationNotifications.ensureChannels(app)
        IconVisibilityNotifications.ensureChannel(app)
        DownloadNotifications.ensureChannel(app)
        AudioJobNotifications.ensureChannels(app)
        AppAlertNotifications.ensureChannel(app)

        assertFalse(nm.getNotificationChannel(BackgroundDictationNotifications.WORK_CHANNEL_ID)!!.canShowBadge())
        assertFalse(nm.getNotificationChannel(IconVisibilityNotifications.CHANNEL_ID)!!.canShowBadge())
        assertFalse(nm.getNotificationChannel(DownloadNotifications.CHANNEL_ID)!!.canShowBadge())
        assertFalse(nm.getNotificationChannel(AudioJobNotifications.PROGRESS_CHANNEL)!!.canShowBadge())
        assertFalse(nm.getNotificationChannel(AudioJobNotifications.RECORDING_CHANNEL)!!.canShowBadge())
        assertTrue(nm.getNotificationChannel(AppAlertNotifications.ALERT_CHANNEL_ID)!!.canShowBadge())
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

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out.add(v.text.toString())
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

    private fun findRowContaining(root: View, text: String): View? {
        fun find(v: View): TextView? {
            if (v is TextView && v.text.toString() == text) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var cur: View? = find(root) ?: return null
        while (cur != null && cur.parent is ViewGroup && !cur.isClickable) {
            val parent = cur.parent as ViewGroup
            if (parent.childCount > 3 && cur is android.widget.LinearLayout) return cur
            cur = parent
        }
        return cur
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
