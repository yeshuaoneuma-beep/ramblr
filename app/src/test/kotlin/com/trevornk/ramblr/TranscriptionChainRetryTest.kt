package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionChainRetryTest {

    private lateinit var app: Application
    private lateinit var server: MockWebServer

    private val p1OpenAi = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", id = "p1-openai", transcriptionModel = "gpt-transcribe")
    private val p2Gemini = ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", id = "p2-gemini", transcriptionModel = "gemini-3.1-flash-lite")
    private val localEntry = ProviderChainEntry(ProviderKind.LOCAL, "local-stt", id = "local")

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        server = MockWebServer().apply { start() }
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun tearDown() {
        server.close()
    }

    // --- Pure TranscriptionChain.Walk state machine tests ---

    @Test fun `multi-cloud walks P1 then P2 then revisits P1 once on 503 before Local and Exhausted`() {
        val walk = TranscriptionChain.Walk(
            cloudCandidates = listOf(p1OpenAi, p2Gemini),
            localCandidate = localEntry,
            startedAtMs = 1_000L,
            isCompressedUpload = true,
        )

        val step1 = walk.nextStep(1_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(p1OpenAi.id, step1.entry.id)
        assertFalse(step1.isRevisit)
        assertEquals(0L, step1.backoffMs)
        assertEquals(60_000L, step1.timeoutMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "HTTP 503")

        val step2 = walk.nextStep(2_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(p2Gemini.id, step2.entry.id)
        assertFalse(step2.isRevisit)
        assertEquals(0L, step2.backoffMs)
        assertEquals(60_000L, step2.timeoutMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "HTTP 503")

        val step3 = walk.nextStep(3_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(p1OpenAi.id, step3.entry.id)
        assertTrue(step3.isRevisit)
        assertEquals(1_000L, step3.backoffMs)
        assertEquals(60_000L, step3.timeoutMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "HTTP 503 again")

        val step4 = walk.nextStep(5_000L) as TranscriptionChain.Step.RunLocal
        assertEquals(localEntry.id, step4.entry.id)
        walk.recordLocalFailure("local failed")

        val step5 = walk.nextStep(6_000L) as TranscriptionChain.Step.Exhausted
        assertEquals("local failed", step5.lastError)
    }

    @Test fun `multi-cloud skips P1 revisit on 429 or 402 and goes straight P1 to P2 to Local`() {
        for (code in listOf(429, 402, 401, 403, 400)) {
            val walk = TranscriptionChain.Walk(
                cloudCandidates = listOf(p1OpenAi, p2Gemini),
                localCandidate = localEntry,
                startedAtMs = 1_000L,
                isCompressedUpload = false,
            )
            val step1 = walk.nextStep(1_000L) as TranscriptionChain.Step.RunCloud
            assertEquals(p1OpenAi.id, step1.entry.id)
            assertEquals(75_000L, step1.timeoutMs)
            walk.recordCloudFailure(CloudErrorClassifier.classify(httpCode = code), "HTTP $code")

            val step2 = walk.nextStep(2_000L) as TranscriptionChain.Step.RunCloud
            assertEquals(p2Gemini.id, step2.entry.id)
            walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "HTTP 503")

            val step3 = walk.nextStep(3_000L)
            assertTrue("P1 must not be revisited after HTTP $code, got $step3", step3 is TranscriptionChain.Step.RunLocal)
        }
    }

    @Test fun `single-cloud bounded retry on 503 obeys 180s budget before Local`() {
        val walk = TranscriptionChain.Walk(
            cloudCandidates = listOf(p1OpenAi),
            localCandidate = localEntry,
            startedAtMs = 0L,
            isCompressedUpload = true,
        )

        val a1 = walk.nextStep(0L) as TranscriptionChain.Step.RunCloud
        assertEquals(1, a1.attemptNumber)
        assertEquals(0L, a1.backoffMs)
        assertEquals(60_000L, a1.timeoutMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503")

        val a2 = walk.nextStep(50_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(2, a2.attemptNumber)
        assertEquals(1_000L, a2.backoffMs)
        assertEquals(60_000L, a2.timeoutMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503")

        val a3 = walk.nextStep(150_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(3, a3.attemptNumber)
        assertEquals(1_000L, a3.backoffMs)
        // Remaining after 1_000ms backoff is 180_000 - 151_000 = 29_000 < 60_000.
        assertEquals(29_000L, a3.timeoutMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503")

        assertTrue(walk.nextStep(152_000L) is TranscriptionChain.Step.RunLocal)
    }

    @Test fun `budget exhaustion jumps directly to Local without exceeding 180s`() {
        val walk = TranscriptionChain.Walk(
            cloudCandidates = listOf(p1OpenAi, p2Gemini),
            localCandidate = localEntry,
            startedAtMs = 0L,
            isCompressedUpload = false,
        )
        walk.nextStep(0L)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503")
        val next = walk.nextStep(180_000L)
        assertTrue(next is TranscriptionChain.Step.RunLocal)
    }

    // --- End-to-end DictationRuntime + MockWebServer tests ---

    private class FakeEngine(cacheDir: File, private val stateMachine: RecordingStateMachine) :
        RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            this.onFinished = onFinished
            return stateMachine.tryStartRecording()
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
        fun finish(pcm: File) {
            stateMachine.tryStartTranscribing()
            onFinished!!(Result(pcmFile = pcm, bytesRecorded = pcm.length(), discarded = false, stopReason = StopReason.USER))
        }
    }

    private class CapturingListener : RuntimeListener {
        val delivered = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
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
        ) {
            delivered += text to rawText
        }
        override fun foregroundPackageName(): String? = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun realSizedPcm(): File = File.createTempFile("chain_test_", ".pcm", app.cacheDir).apply {
        writeBytes(ByteArray(32_000))
    }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition timed out", predicate())
    }

    @Test fun `Gemini P1 503 routes P1 to P2 to P1 with zero Gemini double-retry`() {
        val base = server.url("/v1").toString().trimEnd('/')
        val geminiP1 = ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.1-flash-lite", id = "gem-p1")
        val openAiP2 = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", id = "oai-p2")
        ProviderChainStore.save(app, ProviderChain(listOf(geminiP1, openAiP2)))
        ProviderCredentialStore.set(app, "gem-p1", "gem-key")
        ProviderCredentialStore.set(app, "oai-p2", "oai-key")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)

        val requestSequence = Collections.synchronizedList(mutableListOf<String>())
        val geminiCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.contains(":generateContent") -> {
                        requestSequence += "GEMINI"
                        val call = geminiCalls.incrementAndGet()
                        if (call == 1) {
                            MockResponse.Builder().code(503).body("""{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""").build()
                        } else {
                            MockResponse.Builder().body("""{"candidates":[{"content":{"parts":[{"text":"gemini revisit ok"}]},"finishReason":"STOP"}]}""").build()
                        }
                    }
                    target.contains("/audio/transcriptions") -> {
                        requestSequence += "OPENAI"
                        MockResponse.Builder().code(503).body("""{"error":{"message":"busy"}}""").build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val listener = CapturingListener()
        val engines = mutableListOf<FakeEngine>()
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            transcriptionRetryBackoffMs = 0L,
            engineFactory = { dir, sm -> FakeEngine(dir, sm).also { engines += it } },
        )

        runtime.onTap()
        runtime.onTap()
        engines.single().finish(realSizedPcm())
        awaitCondition { listener.delivered.isNotEmpty() && runtime.currentState() == RecordingStateMachine.State.IDLE }

        assertEquals(listOf("GEMINI", "OPENAI", "GEMINI"), requestSequence)
        assertEquals("gemini revisit ok", listener.delivered.single().first)
        runtime.shutdown()
    }

    @Test fun `P1 429 skips revisit and falls through P1 to P2 to Local STT then routes to Cloud Cleanup`() {
        val base = server.url("/v1").toString().trimEnd('/')
        val p1 = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", id = "p1-groq", presetId = ProviderPresets.GROQ.id)
        val p2 = ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.1-flash-lite", id = "p2-gem")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model", id = "loc")
        ProviderChainStore.save(app, ProviderChain(listOf(p1, p2, local)))
        ProviderCredentialStore.set(app, "p1-groq", "key-1")
        ProviderCredentialStore.set(app, "p2-gem", "key-2")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, true)

        // Create installed local model marker so DictationRuntime.awaitLocalTranscriber() loads our fake.
        val modelDir = File(app.filesDir, "models/fake-local").apply { mkdirs() }
        ModelDownloader.completeMarker(modelDir).writeText("")

        val requestSequence = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    target.contains("/audio/transcriptions") -> {
                        requestSequence += "STT_P1"
                        MockResponse.Builder().code(429).body("""{"error":{"message":"quota exceeded"}}""").build()
                    }
                    target.contains(":generateContent") -> {
                        requestSequence += "STT_P2"
                        MockResponse.Builder().code(402).body("""{"error":{"message":"insufficient credits"}}""").build()
                    }
                    target.contains("/chat/completions") -> {
                        requestSequence += "CLEANUP"
                        MockResponse.Builder().body("""{"choices":[{"message":{"content":"Cleaned from local raw transcript"}}]}""").build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val listener = CapturingListener()
        val engines = mutableListOf<FakeEngine>()
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            transcriberHooks = TranscriberHooks(
                createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java) },
                releaseLocal = {},
                decodeLocal = { _, _, _ -> "raw local transcript" },
            ),
            transcriptionRetryBackoffMs = 0L,
            engineFactory = { dir, sm -> FakeEngine(dir, sm).also { engines += it } },
        )

        try {
            runtime.onTap()
            runtime.onTap()
            engines.single().finish(realSizedPcm())
            awaitCondition { listener.delivered.isNotEmpty() && runtime.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(listOf("STT_P1", "STT_P2", "CLEANUP"), requestSequence)
            val (deliveredText, rawText) = listener.delivered.single()
            assertEquals("Cleaned from local raw transcript", deliveredText)
            assertEquals("raw local transcript", rawText)
        } finally {
            runtime.shutdown()
            modelDir.deleteRecursively()
        }
    }
}
