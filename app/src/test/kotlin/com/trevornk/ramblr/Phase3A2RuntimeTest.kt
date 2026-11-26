package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3A2RuntimeTest {

    private class CapturingListener : RuntimeListener {
        val interims = mutableListOf<Pair<String, Boolean>>()
        val disconnects = mutableListOf<LiveDisconnectEvent>()
        val stageStatuses = mutableListOf<String>()
        val countdowns = mutableListOf<Long>()
        val deliveries = mutableListOf<Delivery>()
        var cleaningStarts = 0

        data class Delivery(
            val text: String,
            val rawText: String?,
            val cleanupError: String?,
            val feedbackDurationMs: Long,
        )

        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onRecordingCountdown(remainingMs: Long) { countdowns += remainingMs }
        override fun onEnterTranscribingUi() {}
        override fun onCleaningStarted() { cleaningStarts++ }
        override fun onStageStatus(statusText: String) { stageStatuses += statusText }
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onCloudLiveInterim(text: String) { interims += text to false }
        override fun onCloudLiveInterim(text: String, bufferOnly: Boolean) { interims += text to bufferOnly }
        override fun onCloudLiveDisconnected(event: LiveDisconnectEvent) { disconnects += event }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            deliveries += Delivery(text, rawText, cleanupError, feedbackDurationMs)
        }
        override fun foregroundPackageName(): String? = null
    }

    private class FakeHaptics : HapticFeedback {
        var starts = 0
        var stops = 0
        var liveDisconnects = 0
        override fun onRecordingStarted() { starts++ }
        override fun onRecordingStopped() { stops++ }
        override fun onLiveDisconnected() { liveDisconnects++ }
        override fun reset() {}
    }

    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        var onChunk: ((ByteArray, Int) -> Unit)? = null

        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            this.onChunk = onChunk
            return true
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false

        fun finishAs(pcmFile: File, stopReason: StopReason = StopReason.USER, compressedFile: File? = null) {
            stateMachine.tryStartTranscribing()
            onFinished!!(
                Result(
                    pcmFile = pcmFile,
                    bytesRecorded = pcmFile.length(),
                    discarded = false,
                    stopReason = stopReason,
                    compressedFile = compressedFile,
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
        var ends = 0
        var closes = 0
        val pcmChunks = mutableListOf<ByteArray>()
        override fun connect() {}
        override fun startActivity(): Boolean = true
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            pcmChunks += buffer.copyOf(length)
            return true
        }
        override fun endActivity(): Boolean { ends++; return true }
        override fun cancel() {}
        override fun close() { closes++ }
        fun interim(text: String) = listener.onInterim(text, CloudLiveTiming(1_000, firstInterimAtMs = 1_100))
        fun terminal(result: CloudLiveTerminal) = listener.onTerminal(result)
    }

    private lateinit var app: Application
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_openrouter", "vault_anthropic")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        DiagnosticErrorLog.clear(app)
        File(app.filesDir, "models").deleteRecursively()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        idleMain()
        server.close()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_openrouter", "vault_anthropic")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        File(app.filesDir, "models").deleteRecursively()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun usablePcm(bytes: Int = 32_000): File =
        File.createTempFile("p3a2_", ".pcm", app.cacheDir).apply { writeBytes(ByteArray(bytes)) }

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) {
            idleMain()
            Thread.sleep(10)
        }
        idleMain()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun installFakeLocalModelDir(): File =
        File(app.filesDir, "models/fake-local-p3a2").apply {
            mkdirs()
            ModelDownloader.completeMarker(this).writeText("")
        }

    // --- Mid-recording disconnect (1008 / 401) + Smart Disconnect Alert & Haptic ---

    @Test fun `mid-recording live disconnect 1008 emits single LiveDisconnectEvent alert haptic diagnostic and hands off full audio to Batch`() {
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
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-batch-rescue")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().body("""{"text":"full un-stitched batch transcript"}""").build()
        }

        val listener = CapturingListener()
        val haptics = FakeHaptics()
        val liveFactory = FakeLiveFactory()
        val directEvents = mutableListOf<LiveDisconnectEvent>()
        val engines = mutableListOf<FakeRecordingEngine>()
        var clock = 10_000L
        var fallbacks = 0

        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { liveFactory },
            onCloudLiveBatchFallback = { fallbacks++ },
            haptics = haptics,
            nowMs = { clock },
            liveDisconnectListener = { directEvents += it },
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    modelId = LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID,
                    smartDisconnectAlertEnabled = true,
                    smartDisconnectHapticEnabled = true,
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }

        try {
            rt.onTap()
            val session = liveFactory.sessions.single()
            session.interim("partial before drop")
            idleMain()
            assertEquals(listOf("partial before drop" to false), listener.interims)

            // 15 seconds into recording, WebSocket closes with 1008.
            clock += 15_000L
            session.terminal(
                CloudLiveTerminal.Failure(
                    CloudLiveFailureReason.NETWORK_ERROR,
                    "Live transcription connection closed (1008)",
                    CloudLiveTiming(10_000L),
                    errorCode = 1008,
                )
            )
            idleMain()

            assertEquals("recording must continue uninterrupted", RecordingStateMachine.State.RECORDING, rt.currentState())
            assertEquals(1, directEvents.size)
            assertEquals(1, listener.disconnects.size)
            val ev = directEvents.single()
            assertEquals("network_error", ev.reason)
            assertEquals(15, ev.elapsedSec)
            assertTrue(ev.continuingLocalRecording)
            assertEquals(1008, ev.errorCode)
            assertEquals(1, haptics.liveDisconnects)
            assertEquals(listOf("Live disconnected · Still recording (Batch)"), listener.stageStatuses)
            assertEquals("Live disconnected · Still recording (Batch)", ShadowToast.getTextOfLatestToast())
            assertTrue(DiagnosticErrorLog.readRecent(app).any { it.contains("[stt_live GEMINI_LIVE/gemini-3.5-transcribe-live HTTP 1008]") })

            // Local recording continues, then user stops -> full-audio Batch handoff (zero stitching).
            rt.onTap()
            engines.single().finishAs(usablePcm())
            awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(1, fallbacks)
            assertEquals(listOf("full un-stitched batch transcript"), listener.deliveries.map { it.text })
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `mid-recording live disconnect 401 respects smartDisconnectAlertEnabled=false and smartDisconnectHapticEnabled=false`() {
        val listener = CapturingListener()
        val haptics = FakeHaptics()
        val liveFactory = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { liveFactory },
            haptics = haptics,
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    smartDisconnectAlertEnabled = false,
                    smartDisconnectHapticEnabled = false,
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }

        try {
            rt.onTap()
            liveFactory.sessions.single().terminal(
                CloudLiveTerminal.Failure(
                    CloudLiveFailureReason.PROTOCOL_ERROR,
                    "invalid key",
                    CloudLiveTiming(1L),
                    errorCode = 401,
                )
            )
            idleMain()

            assertEquals(1, listener.disconnects.size)
            assertEquals(401, listener.disconnects.single().errorCode)
            assertTrue("alert disabled must emit no stage status", listener.stageStatuses.isEmpty())
            assertEquals("haptic disabled must not vibrate", 0, haptics.liveDisconnects)
            assertTrue(DiagnosticErrorLog.readRecent(app).any { it.contains("HTTP 401") })
        } finally {
            rt.shutdown()
        }
    }

    @Test fun `9-00 cutoff emits single alert and event while preserving 7-00 countdown and full-audio Batch handoff`() {
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", vaultKeyId = "vault_openai"))),
        )
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-batch")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().body("""{"text":"batch after 9m cutoff"}""").build()
        }

        val listener = CapturingListener()
        val haptics = FakeHaptics()
        val liveFactory = FakeLiveFactory()
        val engines = mutableListOf<FakeRecordingEngine>()
        var elapsed = 0L
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { liveFactory },
            haptics = haptics,
            nowMs = { 1000L + elapsed },
            cloudLiveSessionCutoffMs = 540_000L,
            elapsedRealtimeMs = { elapsed },
            recordingCountdownStartMs = 420_000L,
            recordingCapMs = 600_000L,
            recordingCountdownTickMs = 60_000L,
            liveConfigProvider = { LiveTranscriptionConfig(enabled = true) },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }

        try {
            rt.onTap()
            // Advance to 7:00 (420s): countdown starts.
            elapsed = 420_000L
            shadowOf(Looper.getMainLooper()).idleFor(420_000L, TimeUnit.MILLISECONDS)
            assertTrue("7:00 countdown must fire", listener.countdowns.isNotEmpty())
            assertTrue("no disconnect yet before 9:00", listener.disconnects.isEmpty())

            // Advance to 9:00 (540s): cutoff fires once with SESSION_LIMIT alert.
            elapsed = 540_000L
            shadowOf(Looper.getMainLooper()).idleFor(120_000L, TimeUnit.MILLISECONDS)
            assertEquals(1, listener.disconnects.size)
            assertEquals("session_limit", listener.disconnects.single().reason)
            assertEquals(1, haptics.liveDisconnects)
            assertEquals(listOf("Live limit reached (9:00) · Still recording (Batch)"), listener.stageStatuses)

            // Stop at 9:30 -> Batch serves full audio.
            rt.onTap()
            engines.single().finishAs(usablePcm())
            awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }
            assertEquals(listOf("batch after 9m cutoff"), listener.deliveries.map { it.text })
        } finally {
            rt.shutdown()
        }
    }

    // --- Live Post-Cleanup OFF vs ON (VERBATIM & SMART) + non-resetting 810s watchdog ---

    @Test fun `Live Post-Cleanup OFF delivers directly with zero cleanup even when Batch cleanup is enabled`() {
        PostProcessingToggle.setEnabled(app, true)
        for (mode in listOf(LiveTranscriptionMode.VERBATIM, LiveTranscriptionMode.SMART)) {
            val listener = CapturingListener()
            val liveFactory = FakeLiveFactory()
            val engines = mutableListOf<FakeRecordingEngine>()
            val rt = DictationRuntime(
                context = app,
                listener = listener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                cloudLiveFactory = { liveFactory },
                liveConfigProvider = { LiveTranscriptionConfig(enabled = true, mode = mode, postCleanupEnabled = false) },
            ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }
            try {
                rt.onTap()
                liveFactory.sessions.single().interim("live words")
                idleMain()
                assertEquals(listOf("live words" to false), listener.interims)

                rt.onTap()
                engines.single().finishAs(usablePcm())
                liveFactory.sessions.single().terminal(CloudLiveTerminal.Success("final live words", CloudLiveTiming(1, finalAtMs = 2)))
                awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }

                assertEquals(0, listener.cleaningStarts)
                val delivery = listener.deliveries.single()
                assertEquals("final live words", delivery.text)
                assertNull("no post-cleanup ran", delivery.rawText)
                assertNull(delivery.cleanupError)
            } finally {
                rt.shutdown()
            }
        }
    }

    @Test fun `Live Post-Cleanup ON buffers interims runs locked live slots with vault keys 401 skip and preserves 810s watchdog`() {
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderCredentialStore.setVaultSecret(app, "vault_gemini", "AIzaSyDeadLiveCleanupKey12345")
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-live-cleanup-ok-1234567890")
        val cleanupHits = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                cleanupHits += request.target
                return when {
                    request.target.contains("gemini-") ->
                        MockResponse.Builder().code(401).body("""{"error":{"message":"dead gemini key"}}""").build()
                    request.target.contains("/chat/completions") ->
                        MockResponse.Builder().body("""{"choices":[{"message":{"content":"Raw live transcript here."}}]}""").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        for (mode in listOf(LiveTranscriptionMode.VERBATIM, LiveTranscriptionMode.SMART)) {
            cleanupHits.clear()
            val listener = CapturingListener()
            val liveFactory = FakeLiveFactory()
            val engines = mutableListOf<FakeRecordingEngine>()
            var monoMs = 5_000L
            val config = LiveTranscriptionConfig(
                enabled = true,
                mode = mode,
                postCleanupEnabled = true,
                postCleanupSlots = listOf(
                    LivePostCleanupSlot("s1", ProviderCapabilityRegistry.ID_GEMINI, "gemini-3.5-flash-lite", vaultKeyId = "vault_gemini", baseUrlOverride = base, attempts = 1, locked = true),
                    LivePostCleanupSlot("s2", ProviderCapabilityRegistry.ID_GEMINI, "gemini-3.1-flash-lite", vaultKeyId = "vault_gemini", baseUrlOverride = base, attempts = 1, locked = true),
                    LivePostCleanupSlot("s3", ProviderCapabilityRegistry.ID_OPENAI, "gpt-5.4-mini", vaultKeyId = "vault_openai", baseUrlOverride = base, attempts = 1, locked = true),
                ),
            )
            val rt = DictationRuntime(
                context = app,
                listener = listener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                cloudLiveFactory = { liveFactory },
                elapsedRealtimeMs = { monoMs },
                liveConfigProvider = { config },
            ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }

            try {
                rt.onTap()
                liveFactory.sessions.single().interim("raw live partial")
                idleMain()
                assertEquals("bufferOnly must be true when Live Post-Cleanup is ON", listOf("raw live partial" to true), listener.interims)

                rt.onTap()
                val anchoredDeadline = rt.postRecordingDeadlineAtElapsedMs
                assertEquals(5_000L + DictationRuntime.WATCHDOG_TIMEOUT_MS, anchoredDeadline)

                monoMs = 25_000L
                engines.single().finishAs(usablePcm())
                liveFactory.sessions.single().terminal(CloudLiveTerminal.Success("raw live transcript here", CloudLiveTiming(1, finalAtMs = 2)))
                awaitCondition { listener.cleaningStarts > 0 }
                // Watchdog deadline must NEVER reset when entering Live Post-Cleanup.
                if (rt.currentState() != RecordingStateMachine.State.IDLE) {
                    assertEquals(anchoredDeadline, rt.postRecordingDeadlineAtElapsedMs)
                }
                awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }

                assertEquals(1, listener.cleaningStarts)
                val delivery = listener.deliveries.single()
                assertEquals("Raw live transcript here.", delivery.text)
                assertEquals("raw live transcript here", delivery.rawText)
                assertNull(delivery.cleanupError)
                assertEquals("second Gemini slot sharing dead vault key must be fast-skipped", 0, cleanupHits.count { it.contains("gemini-3.1-flash-lite") })
                assertEquals(2, cleanupHits.size)
            } finally {
                rt.shutdown()
            }
        }
    }

    @Test fun `Live Post-Cleanup ON delivers raw live transcript when no active locked slots or all cleanup slots fail`() {
        // 1. Empty locked slots -> raw delivery without cleanup failure notice.
        val emptyListener = CapturingListener()
        val emptyLive = FakeLiveFactory()
        val emptyEngines = mutableListOf<FakeRecordingEngine>()
        val emptyRt = DictationRuntime(
            context = app,
            listener = emptyListener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { emptyLive },
            liveConfigProvider = {
                LiveTranscriptionConfig(enabled = true, postCleanupEnabled = true, postCleanupSlots = emptyList())
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { emptyEngines += it } }
        try {
            emptyRt.onTap()
            emptyRt.onTap()
            emptyEngines.single().finishAs(usablePcm())
            emptyLive.sessions.single().terminal(CloudLiveTerminal.Success("raw when no slots", CloudLiveTiming(1, finalAtMs = 2)))
            awaitCondition { emptyRt.currentState() == RecordingStateMachine.State.IDLE }
            assertEquals(0, emptyListener.cleaningStarts)
            assertEquals("raw when no slots", emptyListener.deliveries.single().text)
            assertNull(emptyListener.deliveries.single().cleanupError)
        } finally {
            emptyRt.shutdown()
        }

        // 2. All slots fail -> delivers raw with cleanupError and 4000ms feedbackDurationMs.
        val base = server.url("/v1").toString().trimEnd('/')
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-fail-key")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(500).body("""{"error":{"message":"server down"}}""").build()
        }
        val failListener = CapturingListener()
        val failLive = FakeLiveFactory()
        val failEngines = mutableListOf<FakeRecordingEngine>()
        val failRt = DictationRuntime(
            context = app,
            listener = failListener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            cloudLiveFactory = { failLive },
            liveConfigProvider = {
                LiveTranscriptionConfig(
                    enabled = true,
                    postCleanupEnabled = true,
                    postCleanupSlots = listOf(
                        LivePostCleanupSlot("s1", ProviderCapabilityRegistry.ID_OPENAI, "gpt-5.4-mini", vaultKeyId = "vault_openai", baseUrlOverride = base, attempts = 1, locked = true),
                    ),
                )
            },
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { failEngines += it } }
        try {
            failRt.onTap()
            failRt.onTap()
            failEngines.single().finishAs(usablePcm())
            failLive.sessions.single().terminal(CloudLiveTerminal.Success("raw fallback text", CloudLiveTiming(1, finalAtMs = 2)))
            awaitCondition { failRt.currentState() == RecordingStateMachine.State.IDLE }
            val delivery = failListener.deliveries.single()
            assertEquals("raw fallback text", delivery.text)
            assertNotNull(delivery.cleanupError)
            assertEquals(4000L, delivery.feedbackDurationMs)
            assertTrue(DiagnosticErrorLog.readRecent(app).any { it.contains("[cleanup_live OPENAI_DIRECT/gpt-5.4-mini HTTP 500]") })
        } finally {
            failRt.shutdown()
        }
    }

    // --- Budgeted Batch STT (3x->Local, 2x+1x->Local, 1x+1x+1x->Local, 401 skip, 3.5-transcribe empty STOP failover) ---

    @Test fun `budgeted Batch STT enforces 3x, 2x+1x, and 1x+1x+1x before falling back to Local`() {
        val modelDir = installFakeLocalModelDir()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)
        DictationModeToggle.setAllowLocalFallback(app, true)
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-oai")
        ProviderCredentialStore.setVaultSecret(app, "vault_groq", "gsk-groq")
        ProviderCredentialStore.setVaultSecret(app, "vault_gemini", "AIzaSyGeminiKey1234567890")

        val base = server.url("/v1").toString().trimEnd('/')
        val hits = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += when {
                    request.target.contains("/p1/") -> "P1"
                    request.target.contains("/p2/") -> "P2"
                    request.target.contains("/p3/") -> "P3"
                    else -> "OTHER"
                }
                return MockResponse.Builder().code(503).body("""{"error":{"message":"busy"}}""").build()
            }
        }

        fun runChain(entries: List<ProviderChainEntry>): List<String> {
            hits.clear()
            ProviderChainStore.save(app, ProviderChain(entries))
            val listener = CapturingListener()
            val engines = mutableListOf<FakeRecordingEngine>()
            val rt = DictationRuntime(
                context = app,
                listener = listener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                transcriberHooks = TranscriberHooks(
                    createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java) },
                    releaseLocal = {},
                    decodeLocal = { _, _, _ -> "local rescued" },
                    openVad = { null },
                ),
                transcriptionRetryBackoffMs = 0L,
            ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }
            return try {
                rt.onTap(); rt.onTap()
                engines.single().finishAs(usablePcm())
                awaitCondition { rt.currentState() == RecordingStateMachine.State.IDLE }
                assertEquals(listOf("local rescued"), listener.deliveries.map { it.text })
                hits.toList()
            } finally {
                rt.shutdown()
            }
        }

        try {
            // 1. 3x -> Local
            assertEquals(
                listOf("P1", "P1", "P1"),
                runChain(
                    listOf(
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = "$base/p1", transcriptionModel = "gpt-transcribe", vaultKeyId = "vault_openai", sttAttempts = 3),
                        ProviderChainEntry(ProviderKind.LOCAL, ""),
                    )
                ),
            )

            // 2. 2x + 1x -> Local
            assertEquals(
                listOf("P1", "P1", "P2"),
                runChain(
                    listOf(
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = "$base/p1", transcriptionModel = "gpt-transcribe", vaultKeyId = "vault_openai", sttAttempts = 2),
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = "$base/p2", transcriptionModel = "whisper-large-v3-turbo", presetId = ProviderPresets.GROQ.id, vaultKeyId = "vault_groq", sttAttempts = 1),
                        ProviderChainEntry(ProviderKind.LOCAL, ""),
                    )
                ),
            )

            // 3. 1x + 1x + 1x -> Local
            assertEquals(
                listOf("P1", "P2", "P3"),
                runChain(
                    listOf(
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = "$base/p1", transcriptionModel = "gpt-transcribe", vaultKeyId = "vault_openai", sttAttempts = 1),
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = "$base/p2", transcriptionModel = "whisper-large-v3-turbo", presetId = ProviderPresets.GROQ.id, vaultKeyId = "vault_groq", sttAttempts = 1),
                        ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = "$base/p3", transcriptionModel = "gemini-3.1-flash-lite", vaultKeyId = "vault_gemini", sttAttempts = 1),
                        ProviderChainEntry(ProviderKind.LOCAL, ""),
                    )
                ),
            )
        } finally {
            modelDir.deleteRecursively()
        }
    }

    @Test fun `Batch STT fast-skips same vault key on 401 and fails over gemini-3_5-transcribe empty 200 STOP on same Gemini key`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)
        DictationModeToggle.setAllowLocalFallback(app, false)
        ProviderCredentialStore.setVaultSecret(app, "vault_gemini", "AIzaSySharedGeminiKey12345678")
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-openai-rescue-key")

        val base = server.url("/v1").toString().trimEnd('/')

        // 1. 401 on Gemini slot 1 fast-skips Gemini slot 2 (same vault_gemini) and goes straight to OpenAI.
        val hits401 = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits401 += request.target
                return if (request.target.contains("gemini-")) {
                    MockResponse.Builder().code(401).body("""{"error":{"message":"bad key"}}""").build()
                } else {
                    MockResponse.Builder().body("""{"text":"openai after 401 skip"}""").build()
                }
            }
        }
        ProviderChainStore.save(
            app,
            ProviderChain(
                listOf(
                    ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.5-transcribe", vaultKeyId = "vault_gemini", sttAttempts = 1),
                    ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.1-flash-lite", vaultKeyId = "vault_gemini", sttAttempts = 1),
                    ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", vaultKeyId = "vault_openai", sttAttempts = 1),
                )
            ),
        )
        val listener1 = CapturingListener()
        val engines1 = mutableListOf<FakeRecordingEngine>()
        val rt1 = DictationRuntime(app, listener1, InMemoryDictationSessionLeaseRegistry(), transcriptionRetryBackoffMs = 0L) { dir, sm ->
            FakeRecordingEngine(dir, sm).also { engines1 += it }
        }
        try {
            rt1.onTap(); rt1.onTap()
            engines1.single().finishAs(usablePcm())
            awaitCondition { rt1.currentState() == RecordingStateMachine.State.IDLE }
            assertEquals(listOf("openai after 401 skip"), listener1.deliveries.map { it.text })
            assertEquals(0, hits401.count { it.contains("gemini-3.1-flash-lite") })
            assertEquals(2, hits401.size)
        } finally {
            rt1.shutdown()
        }

        // 2. Empty HTTP 200 STOP on gemini-3.5-transcribe fails over to gemini-3.1-flash-lite on the SAME Gemini key!
        val hitsEmptyStop = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hitsEmptyStop += request.target
                return if (request.target.contains("gemini-3.5-transcribe")) {
                    MockResponse.Builder().body("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""").build()
                } else {
                    MockResponse.Builder().body("""{"candidates":[{"content":{"parts":[{"text":"flash-lite rescued empty stop"}]},"finishReason":"STOP"}]}""").build()
                }
            }
        }
        ProviderChainStore.save(
            app,
            ProviderChain(
                listOf(
                    ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.5-transcribe", vaultKeyId = "vault_gemini", sttAttempts = 1),
                    ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.1-flash-lite", vaultKeyId = "vault_gemini", sttAttempts = 1),
                )
            ),
        )
        val listener2 = CapturingListener()
        val engines2 = mutableListOf<FakeRecordingEngine>()
        val rt2 = DictationRuntime(app, listener2, InMemoryDictationSessionLeaseRegistry(), transcriptionRetryBackoffMs = 0L) { dir, sm ->
            FakeRecordingEngine(dir, sm).also { engines2 += it }
        }
        try {
            rt2.onTap(); rt2.onTap()
            engines2.single().finishAs(usablePcm())
            awaitCondition { rt2.currentState() == RecordingStateMachine.State.IDLE }
            assertEquals(listOf("flash-lite rescued empty stop"), listener2.deliveries.map { it.text })
            assertEquals(2, hitsEmptyStop.size)
            assertTrue(hitsEmptyStop[0].contains("gemini-3.5-transcribe"))
            assertTrue(hitsEmptyStop[1].contains("gemini-3.1-flash-lite"))
        } finally {
            rt2.shutdown()
        }
    }

    @Test fun `oversized audio on gemini-3_5-transcribe routes via GeminiInteractionsTranscriberClient`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)
        DictationModeToggle.setAllowLocalFallback(app, false)
        ProviderCredentialStore.setVaultSecret(app, "vault_gemini", "AIzaSyInteractionsKey12345678")
        ProviderChainStore.save(
            app,
            ProviderChain(
                listOf(
                    ProviderChainEntry(
                        ProviderKind.GEMINI,
                        "gemini-3.5-flash-lite",
                        transcriptionModel = "gemini-3.5-transcribe",
                        vaultKeyId = "vault_gemini",
                        sttAttempts = 1,
                    )
                )
            ),
        )

        val interactionCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target == "/upload/v1beta/files" -> MockResponse.Builder()
                    .code(200)
                    .addHeader("X-Goog-Upload-URL", server.url("/resumable/s1").toString())
                    .build()
                request.target == "/resumable/s1" -> MockResponse.Builder()
                    .body("""{"file":{"name":"files/file-big","uri":"https://files.example/file-big","mimeType":"audio/mp4"}}""")
                    .build()
                request.target == "/v1beta/interactions" -> {
                    interactionCalls.incrementAndGet()
                    MockResponse.Builder()
                        .body("""{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"oversized interactions transcript"}]}]}""")
                        .build()
                }
                request.target.startsWith("/v1beta/files/") -> MockResponse.Builder().body("{}").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val interactionsClient = GeminiInteractionsTranscriberClient(
            httpClient = NetworkClients.noRedirects,
            uploadEndpoint = server.url("/upload/v1beta/files"),
            interactionsEndpoint = server.url("/v1beta/interactions"),
            filesEndpoint = server.url("/v1beta/files/"),
        )
        val listener = CapturingListener()
        val engines = mutableListOf<FakeRecordingEngine>()
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            geminiInteractionsClient = interactionsClient,
        ) { dir, sm -> FakeRecordingEngine(dir, sm).also { engines += it } }

        // Create a compressed file larger than MAX_INLINE_PCM_BYTES (10 MB) so canInlineAudio returns false.
        val oversizedCompressed = File.createTempFile("big_", ".m4a", app.cacheDir).apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES + 1024L) }
        }
        try {
            rt.onTap(); rt.onTap()
            engines.single().finishAs(usablePcm(), compressedFile = oversizedCompressed)
            awaitCondition(timeoutMs = 10_000L) { rt.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(1, interactionCalls.get())
            assertEquals(listOf("oversized interactions transcript"), listener.deliveries.map { it.text })
            assertNotEquals(0, server.requestCount)
        } finally {
            oversizedCompressed.delete()
            rt.shutdown()
        }
    }
}
