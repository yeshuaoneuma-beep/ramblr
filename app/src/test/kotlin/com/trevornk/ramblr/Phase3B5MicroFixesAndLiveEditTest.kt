package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
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
class Phase3B5MicroFixesAndLiveEditTest {

    class TestService : WhisperAccessibilityService()

    private class CapturingListener : RuntimeListener {
        data class Delivery(
            val text: String,
            val rawText: String?,
            val cleanupError: String?,
        )

        val deliveries = mutableListOf<Delivery>()
        val interims = mutableListOf<String>()
        val continuedPrefixes = mutableListOf<String>()
        var idleCount = 0

        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() { idleCount++ }
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveInterim(text: String) { interims += text }
        override fun onCloudLiveInterim(text: String, bufferOnly: Boolean) { interims += text }
        override fun onCloudLiveContinued(preservedPrefix: String) { continuedPrefixes += preservedPrefix }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            deliveries += Delivery(text, rawText, cleanupError)
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
        override fun connect() {}
        override fun startActivity(): Boolean = true
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean = true
        override fun endActivity(): Boolean = true
        override fun cancel() {}
        override fun close() { closes++ }
        fun connected() = listener.onConnected()
        fun interim(text: String) = listener.onInterim(text, CloudLiveTiming(1_000L, firstInterimAtMs = 1_100L))
        fun terminal(result: CloudLiveTerminal) = listener.onTerminal(result)
    }

    private class RecordingHapticOutput(
        var caps: HapticCapabilities? = HapticCapabilities(hasAmplitudeControl = true, predefinedSupported = true),
    ) : HapticOutput {
        val played = mutableListOf<HapticEffectSpec>()
        override fun capabilities(): HapticCapabilities? = caps
        override fun play(spec: HapticEffectSpec) { played += spec }
    }

    private lateinit var app: Application
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        BaseSettingsActivity.cancelPendingForegroundClear()
        ActiveDictationHostRegistry.resetForTest()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_openrouter", "vault_anthropic")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        ApiKeyVaultStore.save(app, emptyList())
        ProviderChainStore.save(app, ProviderChain(emptyList()))
        DictationHistoryStore.forContext(app).clear()
        UiTapHaptics.onTickForTest = null
        UiTapHaptics.outputFactoryForTest = null
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        UiTapHaptics.onTickForTest = null
        UiTapHaptics.outputFactoryForTest = null
        BaseSettingsActivity.cancelPendingForegroundClear()
        ActiveDictationHostRegistry.resetForTest()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_openrouter", "vault_anthropic")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        ApiKeyVaultStore.save(app, emptyList())
        ProviderChainStore.save(app, ProviderChain(emptyList()))
        DictationHistoryStore.forContext(app).clear()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        server.close()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun usablePcm(): File =
        File.createTempFile("p3b5_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(32_000)) }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) {
            idleMain()
            Thread.sleep(10)
        }
        idleMain()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    // --- 1. SpokenEmojiResolver & CloudCleanupFidelityGuard ---

    @Test fun `SpokenEmojiResolver resolves exact counts, multi-word names, clamping, and leaves prose untouched`() {
        assertEquals("\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02", SpokenEmojiResolver.resolve("Three laughing emojis"))
        assertEquals("\uD83D\uDC94\uD83D\uDC94\uD83D\uDC94", SpokenEmojiResolver.resolve("Three broken heart emojis"))
        assertEquals("\uD83D\uDE02", SpokenEmojiResolver.resolve("One laughing tears emoji"))
        assertEquals("\uD83D\uDE02", SpokenEmojiResolver.resolve("One laughing tears emoji."))
        assertEquals("\uD83D\uDD25\uD83D\uDD25", SpokenEmojiResolver.resolve("2 fire emojis"))
        assertEquals("\uD83D\uDCAF", SpokenEmojiResolver.resolve("100 emoji"))
        assertEquals("\uD83D\uDCAF\uD83D\uDCAF", SpokenEmojiResolver.resolve("2 100 emojis"))
        assertEquals("\uD83D\uDC4D\uD83D\uDC4D", SpokenEmojiResolver.resolve("two thumbs up emojis"))
        assertEquals(
            "That was awesome \uD83D\uDD25\uD83D\uDD25 and \uD83D\uDE02\uD83D\uDE02\uD83D\uDE02",
            SpokenEmojiResolver.resolve("That was awesome two fire emojis and three laughing emojis"),
        )
        // Clamping 1..10
        assertEquals("\uD83D\uDD25", SpokenEmojiResolver.resolve("0 fire emojis"))
        assertEquals("\uD83D\uDD25".repeat(10), SpokenEmojiResolver.resolve("15 fire emojis"))
        // Non-emoji prose untouched
        assertEquals("three hearts beat", SpokenEmojiResolver.resolve("three hearts beat"))
        assertEquals("2 fire trucks", SpokenEmojiResolver.resolve("2 fire trucks"))
    }

    @Test fun `CloudCleanupFidelityGuard preserves repeated emojis and pure-emoji output`() {
        assertEquals(FidelityVerdict.Ok, CloudCleanupFidelityGuard.check("\uD83D\uDE02", "\uD83D\uDE02"))
        assertEquals(
            FidelityVerdict.Ok,
            CloudCleanupFidelityGuard.check("\u2764\uFE0F\u2764\uFE0F\u2764\uFE0F", "\u2764\uFE0F\u2764\uFE0F\u2764\uFE0F"),
        )
        assertEquals(
            FidelityVerdict.Ok,
            CloudCleanupFidelityGuard.check("Three broken heart emojis", "\uD83D\uDC94\uD83D\uDC94\uD83D\uDC94"),
        )
        assertEquals(FidelityVerdict.Ok, CloudCleanupFidelityGuard.checkSafety("One laughing tears emoji", "\uD83D\uDE02"))
        assertEquals(
            FidelityVerdict.Ok,
            CloudCleanupFidelityGuard.checkSafety("\u2764\uFE0F\u2764\uFE0F\u2764\uFE0F", "\u2764\uFE0F\u2764\uFE0F\u2764\uFE0F"),
        )

        assertEquals(
            "Love you \u2764\uFE0F\u2764\uFE0F\u2764\uFE0F.",
            CloudCleanupFidelityGuard.restoreCollapsedEmojiRuns(
                "Love you \u2764\uFE0F\u2764\uFE0F\u2764\uFE0F",
                "Love you \u2764\uFE0F.",
            ),
        )
        assertEquals(
            "\uD83D\uDC94\uD83D\uDC94\uD83D\uDC94",
            CloudCleanupFidelityGuard.restoreCollapsedEmojiRuns(
                "\uD83D\uDC94\uD83D\uDC94\uD83D\uDC94",
                "\uD83D\uDC94",
            ),
        )

        val step = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini")
        val outcome = CleanupWaterfallExecutor.guardCloudOutput(
            step = step,
            rawText = "Love you \u2764\uFE0F\u2764\uFE0F\u2764\uFE0F",
            outcome = CleanupStepOutcome.Success("Love you \u2764\uFE0F."),
        )
        assertTrue(outcome is CleanupStepOutcome.Success)
        assertEquals("Love you \u2764\uFE0F\u2764\uFE0F\u2764\uFE0F.", (outcome as CleanupStepOutcome.Success).text)

        assertTrue(PostProcessor.SPOKEN_EMOJI_COMMANDS_CLAUSE.contains("NEVER deduplicate, collapse, or reduce repeated emojis"))
    }

    @Test fun `DictationRuntime resolves spoken emojis before cloud cleanup and on raw Live insert`() {
        CleanupModifiersPrefs.setSpokenEmojiCommandsEnabled(app, true)
        assertTrue(CleanupModifiersPrefs.isSpokenEmojiEnabled(app))
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-p3b5-emoji")
        val seenRequests = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenRequests += request.body?.utf8().orEmpty()
                // Simulate a cloud model collapsing repeated emojis down to one.
                return MockResponse.Builder()
                    .body("""{"choices":[{"message":{"content":"I truly appreciate you \uD83D\uDC94."}}]}""")
                    .build()
            }
        }
        val listener = CapturingListener()
        val liveFactory = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { liveFactory },
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    postCleanupEnabled = true,
                    postCleanupSlots = listOf(
                        LivePostCleanupSlot("s1", ProviderCapabilityRegistry.ID_OPENAI, "gpt-5.4-mini", vaultKeyId = "vault_openai", baseUrlOverride = base, attempts = 1, locked = true),
                    ),
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }
        try {
            // 1. Pre-cleanup resolution + collapse repair through Cloud Live cleanup
            rt.onTap()
            rt.onTap()
            engines[0].finishAs(usablePcm())
            liveFactory.sessions[0].terminal(
                CloudLiveTerminal.Success("i truly appreciate you three broken heart emojis", CloudLiveTiming(1L, finalAtMs = 2L)),
            )
            awaitCondition { listener.deliveries.size == 1 }
            assertTrue("Cleanup must receive pre-resolved emojis", seenRequests.single().contains("\uD83D\uDC94\uD83D\uDC94\uD83D\uDC94"))
            assertEquals("I truly appreciate you \uD83D\uDC94\uD83D\uDC94\uD83D\uDC94.", listener.deliveries[0].text)

            // 2. Standalone emoji-only output delivered cleanly without dropping
            rt.onTap()
            rt.onTap()
            engines[1].finishAs(usablePcm())
            liveFactory.sessions[1].terminal(
                CloudLiveTerminal.Success("One laughing tears emoji.", CloudLiveTiming(1L, finalAtMs = 2L)),
            )
            awaitCondition { listener.deliveries.size == 2 }
            assertEquals("\uD83D\uDE02", listener.deliveries[1].text)

            // 3. Raw Live insert resolves spoken emoji counts deterministically
            rt.onTap()
            liveFactory.sessions[2].connected()
            liveFactory.sessions[2].interim("Three broken heart emojis")
            idleMain()
            rt.finishWithCapturedLiveText()
            idleMain()
            assertEquals("\uD83D\uDC94\uD83D\uDC94\uD83D\uDC94", listener.deliveries[2].text)
        } finally {
            rt.shutdown()
        }
    }

    // --- 2. Overlay Tap Haptics (Quick Panel & Edge-Dock Pill Expand) ---

    @Test fun `Quick Panel clicks and Edge-Dock Pill expand fire UiTapHaptics without touching bubble recording haptics`() {
        val service = buildService()
        val fakeOut = RecordingHapticOutput()
        UiTapHaptics.outputFactoryForTest = { fakeOut }
        var tickCount = 0
        UiTapHaptics.onTickForTest = { tickCount++ }

        try {
            // Disabled -> zero ticks everywhere
            HapticPrefs.setUiTapsEnabled(app, false)
            openStyleMenu(service)
            val menu = styleMenuViewOf(service)!!
            findViewByText(menu, "Cleanup: Off")?.let { (it.parent.parent as View).performClick() }
            idleMain()
            assertEquals(0, tickCount)
            assertTrue(fakeOut.played.isEmpty())

            // Enabled -> Quick Panel interactive items & close button tick
            HapticPrefs.setUiTapsEnabled(app, true)
            HapticPrefs.setUiTapStrength(app, HapticStrength.STRONG)
            openStyleMenu(service)
            val menu2 = styleMenuViewOf(service)!!
            findViewByContentDesc(menu2, "Close menu")!!.performClick()
            idleMain()
            assertEquals(1, tickCount)
            assertEquals(HapticEffectSpec.OneShot(28L, 255), fakeOut.played.last())

            openStyleMenu(service)
            val menu3 = styleMenuViewOf(service)!!
            val styleTitle = findViewMatching(menu3) { it is TextView && it.text.toString().startsWith("Style:") }!!
            (styleTitle.parent.parent as View).performClick()
            idleMain()
            assertEquals(2, tickCount)
            val dismissBtn = findViewByContentDesc(menu3, "Close menu")
            dismissBtn?.performClick()
            idleMain()

            // Edge-Dock Pill uncollapse tap fires UiTapHaptics
            val overlay = overlayViewOf(service)
            val isPeekedField = WhisperAccessibilityService::class.java.getDeclaredField("isPeeked").apply { isAccessible = true }
            val prePeekXField = WhisperAccessibilityService::class.java.getDeclaredField("prePeekX").apply { isAccessible = true }
            isPeekedField.setBoolean(service, true)
            prePeekXField.set(service, 16)
            val beforePeekTicks = tickCount
            val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 5f, 5f, 0)
            try {
                overlay.dispatchTouchEvent(down)
            } finally {
                down.recycle()
            }
            idleMain()
            assertFalse("Edge-Dock Pill must uncollapse on tap", isPeekedField.getBoolean(service))
            assertEquals("Edge-Dock Pill expand tap must fire UiTapHaptics", beforePeekTicks + 1, tickCount)

            // Active bubble recording start/stop does NOT fire UiTapHaptics
            val ticksBeforeRecord = tickCount
            val playedBeforeRecord = fakeOut.played.size
            service.runtime.onTap()
            service.runtime.onTap()
            idleMain()
            assertEquals(ticksBeforeRecord, tickCount)
            assertEquals(playedBeforeRecord, fakeOut.played.size)
        } finally {
            service.onDestroy()
        }
    }

    // --- 3. Configurable Haptic Strength & BehaviorActivity Selectors ---

    @Test fun `HapticPrefs and DictationHaptics support LIGHT MEDIUM STRONG for UI taps and Live disconnect while keeping recording haptics unchanged`() {
        assertEquals(HapticStrength.MEDIUM, HapticPrefs.uiTapStrength(app))
        assertEquals(HapticStrength.MEDIUM, HapticPrefs.liveDisconnectStrength(app))
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putString(HapticPrefs.KEY_UI_TAP_STRENGTH, "INVALID")
            .putString(HapticPrefs.KEY_LIVE_DISCONNECT_STRENGTH, "INVALID")
            .apply()
        assertEquals(HapticStrength.MEDIUM, HapticPrefs.uiTapStrength(app))
        assertEquals(HapticStrength.MEDIUM, HapticPrefs.liveDisconnectStrength(app))

        val ampCaps = HapticCapabilities(hasAmplitudeControl = true, predefinedSupported = true)
        val durCaps = HapticCapabilities(hasAmplitudeControl = false, predefinedSupported = true)

        assertEquals(HapticEffectSpec.OneShot(10L, 70), HapticEffectPlanner.planUiTap(HapticStrength.LIGHT, ampCaps))
        assertEquals(HapticEffectSpec.OneShot(18L, 155), HapticEffectPlanner.planUiTap(HapticStrength.MEDIUM, ampCaps))
        assertEquals(HapticEffectSpec.OneShot(28L, 255), HapticEffectPlanner.planUiTap(HapticStrength.STRONG, ampCaps))
        assertEquals(HapticEffectSpec.Duration(10L), HapticEffectPlanner.planUiTap(HapticStrength.LIGHT, durCaps))
        assertEquals(HapticEffectSpec.Duration(18L), HapticEffectPlanner.planUiTap(HapticStrength.MEDIUM, durCaps))
        assertEquals(HapticEffectSpec.Duration(28L), HapticEffectPlanner.planUiTap(HapticStrength.STRONG, durCaps))

        val out = RecordingHapticOutput(ampCaps)
        val dh = DictationHaptics(settings = { HapticPrefs.load(app) }, output = { out })
        HapticPrefs.setLiveDisconnectStrength(app, HapticStrength.LIGHT)
        dh.onLiveDisconnected()
        assertEquals(
            HapticEffectSpec.Waveform(listOf(0L, 35L, 65L, 35L), listOf(0, 90, 0, 90)),
            out.played.last(),
        )
        HapticPrefs.setLiveDisconnectStrength(app, HapticStrength.MEDIUM)
        dh.onLiveDisconnected()
        assertEquals(
            HapticEffectSpec.Waveform(listOf(0L, 55L, 70L, 55L), listOf(0, 175, 0, 175)),
            out.played.last(),
        )
        HapticPrefs.setLiveDisconnectStrength(app, HapticStrength.STRONG)
        dh.onLiveDisconnected()
        assertEquals(
            HapticEffectSpec.Waveform(listOf(0L, 80L, 75L, 80L), listOf(0, 255, 0, 255)),
            out.played.last(),
        )

        // Recording start/stop/cancel remain tied solely to KEY_ENABLED and keep original specs
        out.played.clear()
        HapticPrefs.setEnabled(app, false)
        dh.onRecordingStarted()
        dh.onCancelDiscard()
        assertTrue(out.played.isEmpty())
        HapticPrefs.setEnabled(app, true)
        dh.onRecordingStarted()
        dh.onRecordingStopped()
        dh.onCancelDiscard()
        assertEquals(3, out.played.size)
        assertEquals(HapticEffectSpec.OneShot(25L, 128), out.played[0])
        assertEquals(HapticEffectSpec.OneShot(25L, 128), out.played[1])
        assertEquals(HapticEffectSpec.OneShot(45L, 220), out.played[2])

        // BehaviorActivity strength selectors use selectionDots (zero RadioButtons) & trigger preview immediately
        val fakeUiTapOut = RecordingHapticOutput(ampCaps)
        UiTapHaptics.outputFactoryForTest = { fakeUiTapOut }
        val controller = Robolectric.buildActivity(
            BehaviorActivity::class.java,
            BehaviorActivity.intent(app, BehaviorActivity.Section.DICTATION),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        assertFalse(hasWidgetType(root, RadioButton::class.java))
        assertFalse(hasWidgetType(root, RadioGroup::class.java))
        val texts = collectTexts(root)
        assertTrue(texts.contains("Tap feedback strength"))
        assertTrue(texts.contains("Disconnect alert strength"))

        // Tap "Strong" under Tap feedback strength -> persists & fires preview at STRONG
        val tapRows = findRowsByTitle(root, "Strong")
        assertEquals(2, tapRows.size)
        tapRows[0].performClick()
        assertEquals(HapticStrength.STRONG, HapticPrefs.uiTapStrength(app))
        assertTrue(activity.uiTapStrengthDots[HapticStrength.STRONG]!!.isSelected)
        assertFalse(activity.uiTapStrengthDots[HapticStrength.MEDIUM]!!.isSelected)
        assertEquals(HapticEffectSpec.OneShot(28L, 255), fakeUiTapOut.played.last())

        // Tap "Light" under Disconnect alert strength -> persists
        val lightRows = findRowsByTitle(root, "Light")
        assertEquals(2, lightRows.size)
        lightRows[1].performClick()
        assertEquals(HapticStrength.LIGHT, HapticPrefs.liveDisconnectStrength(app))
        assertTrue(activity.liveDisconnectStrengthDots[HapticStrength.LIGHT]!!.isSelected)
        assertFalse(activity.liveDisconnectStrengthDots[HapticStrength.MEDIUM]!!.isSelected)
        controller.pause().stop().destroy()
    }

    // --- 4. Live Preview Pause-to-Edit & Cursor-Spot Re-Dictation ---

    @Test fun `stitchLiveEditSegments handles middle insertion, selected replacement, punctuation, and end continuation`() {
        assertEquals("Hello wonderful world", DictationRuntime.stitchLiveEditSegments("Hello ", "wonderful", "world"))
        assertEquals("I live in London, France.", DictationRuntime.stitchLiveEditSegments("I live in ", "London", ", France."))
        assertEquals("See you Friday.", DictationRuntime.stitchLiveEditSegments("See you ", "Friday", "."))
        assertEquals("She said (hello) today", DictationRuntime.stitchLiveEditSegments("She said (", "hello", ") today"))
        assertEquals("Part one and part two", DictationRuntime.stitchLiveEditSegments("Part one", "and part two", ""))
        assertEquals("First second", DictationRuntime.stitchLiveEditSegments("", "First", "second"))
        assertEquals("Hello world", DictationRuntime.stitchLiveEditSegments("Hello ", "", "world"))
    }

    @Test fun `Live preview Edit pauses streaming, toggles FLAG_NOT_FOCUSABLE, supports Speak here at cursor, Done, Insert, and cleanup`() {
        LiveTranscriptionConfigStore.save(
            app,
            LiveTranscriptionConfig(
                enabled = true,
                liveWindowEnabled = true,
                postCleanupEnabled = false,
                recoveryMode = LiveRecoveryMode.CONTINUE_LIVE_SESSION,
            ),
        )
        val liveFactory = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        val delivered = mutableListOf<String>()
        val service = Robolectric.buildService(TestService::class.java, null).create().get()
        val baseListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener
        val wrappedListener = object : RuntimeListener by baseListener {
            override fun deliverText(
                text: String,
                rawText: String?,
                paidFallbackGroup: CleanupStepGroup?,
                cleanupError: String?,
                feedbackDurationMs: Long,
            ) {
                delivered += text
                baseListener.deliverText(text, rawText, paidFallbackGroup, cleanupError, feedbackDurationMs)
            }

            override fun deliverText(
                text: String,
                rawText: String?,
                paidFallbackGroup: CleanupStepGroup?,
                cleanupError: String?,
                feedbackDurationMs: Long,
                liveText: String?,
            ) {
                delivered += text
                baseListener.deliverText(text, rawText, paidFallbackGroup, cleanupError, feedbackDurationMs, liveText)
            }
        }
        val runtime = DictationRuntime(
            context = app,
            listener = wrappedListener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { liveFactory },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }.set(service, runtime)
        WhisperAccessibilityService::class.java.getDeclaredMethod("registerScreenStateReceiver")
            .apply { isAccessible = true }.invoke(service)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }.invoke(service)
        idleMain()

        try {
            // 1. Start Live streaming -> Edit appears once transcript arrives
            service.runtime.onTap()
            idleMain()
            liveFactory.sessions[0].connected()
            liveFactory.sessions[0].interim("Hello today.")
            idleMain()
            val win = service.liveWindowView!!
            val params = service.liveWindowParams!!
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
            assertEquals(View.VISIBLE, service.liveWindowEditActionButton!!.visibility)

            // 2. Tap Edit -> pauses recording cleanly, clears FLAG_NOT_FOCUSABLE, shows EditText + 4 edit actions
            service.liveWindowEditActionButton!!.performClick()
            idleMain()
            engines[0].finishAs(usablePcm(), discarded = true)
            idleMain()
            assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
            assertTrue(service.runtime.hasPendingContinue())
            assertTrue(service.liveWindowState.isEditing)
            assertEquals(View.VISIBLE, win.visibility)
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals(View.VISIBLE, service.liveTranscriptEditText!!.visibility)
            assertEquals(View.GONE, service.liveTranscriptTextView!!.visibility)
            assertEquals("Hello today.", service.liveTranscriptEditText!!.text.toString())
            assertEquals("Speak here", service.liveWindowPrimaryActionButton!!.text.toString())
            assertEquals("Done", service.liveWindowTertiaryActionButton!!.text.toString())
            assertEquals("Insert", service.liveWindowQuaternaryActionButton!!.text.toString())
            assertEquals("Discard", service.liveWindowSecondaryActionButton!!.text.toString())

            // 3. Edit text & tap Done -> exits edit mode, restores FLAG_NOT_FOCUSABLE
            service.liveTranscriptEditText!!.setText("Hello world today.")
            service.liveWindowTertiaryActionButton!!.performClick()
            idleMain()
            assertFalse(service.liveWindowState.isEditing)
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
            assertEquals("Hello world today.", service.liveTranscriptTextView!!.text.toString())

            // 4. Re-enter Edit, select "world" and tap "Speak here" -> replaces selection via stitched Live stream
            service.liveWindowEditActionButton!!.performClick()
            idleMain()
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val editText = service.liveTranscriptEditText!!
            editText.setText("I live in Paris, France.")
            val selStart = "I live in ".length
            val selEnd = selStart + "Paris".length
            editText.setSelection(selStart, selEnd)
            service.liveWindowPrimaryActionButton!!.performClick()
            idleMain()
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
            assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())
            liveFactory.sessions[1].connected()
            liveFactory.sessions[1].interim("London")
            idleMain()
            assertEquals("I live in London, France.", service.liveTranscriptTextView!!.text.toString())

            // 5. Enter Edit again and tap Insert -> restores FLAG_NOT_FOCUSABLE and delivers edited text
            service.liveWindowEditActionButton!!.performClick()
            idleMain()
            engines[1].finishAs(usablePcm(), discarded = true)
            idleMain()
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            service.liveTranscriptEditText!!.setText("I live in Madrid, Spain.")
            service.liveWindowQuaternaryActionButton!!.performClick()
            idleMain()
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
            assertEquals(listOf("I live in Madrid, Spain."), delivered)
            assertFalse(service.runtime.hasPendingContinue())

            // 6. Collapse, Discard, onIdleUi, and onDestroy all guarantee FLAG_NOT_FOCUSABLE restored
            service.runtime.onTap()
            idleMain()
            liveFactory.sessions[2].connected()
            liveFactory.sessions[2].interim("Draft sentence")
            idleMain()
            service.liveWindowEditActionButton!!.performClick()
            idleMain()
            engines[2].finishAs(usablePcm(), discarded = true)
            idleMain()
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            service.liveWindowCloseButton!!.performClick()
            idleMain()
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)

            service.liveReopenChipSecondaryButton!!.performClick()
            idleMain()
            service.liveWindowEditActionButton!!.performClick()
            idleMain()
            assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            service.liveWindowSecondaryActionButton!!.performClick()
            idleMain()
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)

            // Simulate stuck focusable state cleaned up by onIdleUi() and onDestroy()
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wrappedListener.onIdleUi()
            idleMain()
            assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        } finally {
            service.onDestroy()
        }
    }

    // --- Helpers ---

    private fun buildService(): TestService {
        val service = Robolectric.buildService(TestService::class.java, null).create().get()
        val runtimeListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener
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

    private fun openStyleMenu(service: WhisperAccessibilityService) {
        val method = WhisperAccessibilityService::class.java.declaredMethods
            .first { it.name == "showStyleMenu" && it.parameterCount == 1 }
            .apply { isAccessible = true }
        val builderMethod = WhisperAccessibilityService::class.java
            .getDeclaredMethod("buildStyleMenuContent")
            .apply { isAccessible = true }
        val builder: () -> android.widget.LinearLayout = {
            builderMethod.invoke(service) as android.widget.LinearLayout
        }
        method.invoke(service, builder)
        idleMain()
    }

    private fun styleMenuViewOf(service: WhisperAccessibilityService): View? =
        WhisperAccessibilityService::class.java.getDeclaredField("styleMenuView")
            .apply { isAccessible = true }.get(service) as? View

    private fun overlayViewOf(service: WhisperAccessibilityService): FrameLayout =
        WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }.get(service) as FrameLayout

    private fun hasWidgetType(root: View, cls: Class<*>): Boolean {
        if (cls.isInstance(root)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasWidgetType(root.getChildAt(i), cls)) return true
            }
        }
        return false
    }

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        if (root is TextView && root.visibility == View.VISIBLE && root.text.isNotBlank()) {
            out += root.text.toString()
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) out += collectTexts(root.getChildAt(i))
        }
        return out
    }

    private fun findViewByText(root: View, text: String): View? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findViewByText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun findViewMatching(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findViewMatching(root.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun findViewByContentDesc(root: View, desc: String): View? {
        if (root.contentDescription?.toString() == desc) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findViewByContentDesc(root.getChildAt(i), desc)?.let { return it }
        }
        return null
    }

    private fun findRowsByTitle(root: View, title: String): List<View> {
        val out = mutableListOf<View>()
        if (root.isClickable && root.findViewWithTag<TextView>("title")?.text?.toString() == title) {
            out += root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) out += findRowsByTitle(root.getChildAt(i), title)
        }
        return out
    }
}
