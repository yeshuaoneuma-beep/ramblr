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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 2C tests for the proactive 9:00 Gemini Live cutoff:
 * - Cutoff fires at 540_000 ms (9:00), closing only the live WebSocket stream.
 * - RecordingEngine stays alive up to the 10:00 cap (600_000 ms) without dropping PCM.
 * - On stop (user tap or 10:00 MAX_DURATION), preserved PCM falls back cleanly once to batch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLiveCutoffTest {

    private class CapturingListener : RuntimeListener {
        val events = mutableListOf<String>()
        val delivered = mutableListOf<String>()

        override fun onRecordingStartRequested() { events += "startRequested" }
        override fun onRecordingStartFailed() { events += "startFailed" }
        override fun onRecordingStarted() { events += "recordingStarted" }
        override fun onEnterTranscribingUi() { events += "transcribingUi" }
        override fun onIdleUi() { events += "idleUi" }
        override fun onStreamingTeardown() { events += "streamingTeardown" }
        override fun onStreamingPartial(text: String) {}
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            delivered += text
        }
        override fun foregroundPackageName(): String? = null
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

        fun finishAs(pcmFile: File?, stopReason: StopReason) {
            if (RecorderHandoff.shouldClaimTranscribing(superseded = false)) stateMachine.tryStartTranscribing()
            val discarded = RecorderHandoff.discarded(superseded = false, stateMachine.current())
            onFinished!!(
                Result(
                    pcmFile = if (!discarded) pcmFile else null,
                    bytesRecorded = pcmFile?.length() ?: 0L,
                    discarded = discarded,
                    stopReason = stopReason,
                )
            )
        }
    }

    private class FakeCloudLiveFactory : CloudLiveTranscriptionSessionFactory {
        val sessions = mutableListOf<FakeCloudLiveSession>()
        override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession =
            FakeCloudLiveSession(listener).also(sessions::add)
    }

    private class FakeCloudLiveSession(
        private val listener: CloudLiveTranscriptionListener,
    ) : CloudLiveTranscriptionSession {
        var connects = 0
        var starts = 0
        var ends = 0
        var cancels = 0
        var closes = 0
        val pcm = mutableListOf<ByteArray>()

        override fun connect() { connects++ }
        override fun startActivity(): Boolean { starts++; return true }
        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            pcm += buffer.copyOf(length)
            return true
        }
        override fun endActivity(): Boolean { ends++; return true }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }

        fun complete(result: CloudLiveTerminal) = listener.onTerminal(result)
    }

    private lateinit var app: Application
    private lateinit var listener: CapturingListener
    private lateinit var engines: MutableList<FakeRecordingEngine>
    private lateinit var leaseRegistry: InMemoryDictationSessionLeaseRegistry
    private lateinit var batchServer: MockWebServer

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        listener = CapturingListener()
        engines = mutableListOf()
        leaseRegistry = InMemoryDictationSessionLeaseRegistry()
        batchServer = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        batchServer.close()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun realSizedPcm(): File = File.createTempFile("rec_", ".pcm", app.cacheDir).apply {
        writeBytes(ByteArray(32_000))
    }

    private fun stubBatchProvider(transcript: String) {
        val base = batchServer.url("/v1").toString().trimEnd('/')
        ProviderChainStore.save(
            app,
            ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"))),
        )
        ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "test-batch-key")
        batchServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.contains("/audio/transcriptions") ->
                    MockResponse.Builder().body(JSONObject().put("text", transcript).toString()).build()
                else -> MockResponse.Builder().body("""{"choices":[{"message":{"content":"cleanup"}}]}""").build()
            }
        }
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("use_local", false)
            .apply()
        PostProcessingToggle.setEnabled(app, false)
    }

    private fun awaitIdle(runtime: DictationRuntime) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runtime.currentState() != RecordingStateMachine.State.IDLE) {
            idleMainLooper()
            Thread.sleep(10)
        }
        assertEquals(RecordingStateMachine.State.IDLE, runtime.currentState())
    }

    @Test fun `live cutoff constants match 9-00 ahead of 10-00 recording cap`() {
        assertEquals(540_000L, GeminiCloudLiveTranscriptionClient.DEFAULT_MAX_SESSION_MS)
        assertEquals(540_000L, DictationRuntime.CLOUD_LIVE_SESSION_CUTOFF_MS)
        assertEquals(600_000L, RecordingEngine.MAX_DURATION_MS)
    }

    @Test fun `9-00 cutoff closes live stream at 540s, keeps RecordingEngine alive to 10-00 cap, and delivers batch`() {
        stubBatchProvider("ten minute batch transcript")
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        val runtime = DictationRuntime(
            app,
            listener,
            leaseRegistry,
            cloudLiveFactory = { liveFactory },
            onCloudLiveBatchFallback = { fallbacks++ },
            cloudLiveSessionCutoffMs = DictationRuntime.CLOUD_LIVE_SESSION_CUTOFF_MS,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it } }
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = liveFactory.sessions.single()
        engines.single().onChunk!!(byteArrayOf(1, 2), 2)
        assertEquals(1, session.pcm.size)

        // At 8:59 (539_000 ms), live session is still open and receiving PCM.
        shadowOf(Looper.getMainLooper()).idleFor(539_000, TimeUnit.MILLISECONDS)
        assertEquals(0, session.closes)
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        engines.single().onChunk!!(byteArrayOf(3, 4), 2)
        assertEquals(2, session.pcm.size)

        // Crossing 9:00 (540_000 ms) closes only the live stream; RecordingEngine stays active.
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        assertTrue("cutoff must close the live session", session.closes >= 1)
        assertEquals("batch fallback must wait until capture finishes", 0, fallbacks)

        // Further audio up to 10:00 is captured in PCM but NOT forwarded to the closed live stream.
        engines.single().onChunk!!(byteArrayOf(5, 6), 2)
        assertEquals(2, session.pcm.size)

        // 10:00 max duration fires on RecordingEngine.
        shadowOf(Looper.getMainLooper()).idleFor(60_000, TimeUnit.MILLISECONDS)
        engines.single().finishAs(pcm, RecordingEngine.StopReason.MAX_DURATION)
        awaitIdle(runtime)

        assertEquals(0, session.ends)
        assertEquals(1, fallbacks)
        assertEquals(listOf("ten minute batch transcript"), listener.delivered)
        assertFalse("batch pipeline must delete preserved PCM after completion", pcm.exists())
        runtime.beginShutdown()
    }

    @Test fun `user stop after 9-00 cutoff falls back once to batch without calling endActivity`() {
        stubBatchProvider("post cutoff user stop")
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        val runtime = DictationRuntime(
            app,
            listener,
            leaseRegistry,
            cloudLiveFactory = { liveFactory },
            onCloudLiveBatchFallback = { fallbacks++ },
            cloudLiveSessionCutoffMs = 250L,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it } }
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = liveFactory.sessions.single()
        shadowOf(Looper.getMainLooper()).idleFor(260, TimeUnit.MILLISECONDS)
        assertTrue(session.closes >= 1)
        assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())

        runtime.onTap()
        assertEquals(0, session.ends)
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        awaitIdle(runtime)

        assertEquals(1, fallbacks)
        assertEquals(listOf("post cutoff user stop"), listener.delivered)
        assertFalse(pcm.exists())
        runtime.beginShutdown()
    }

    @Test fun `stopping before 9-00 cancels cutoff timer cleanly`() {
        val liveFactory = FakeCloudLiveFactory()
        var fallbacks = 0
        val runtime = DictationRuntime(
            app,
            listener,
            leaseRegistry,
            cloudLiveFactory = { liveFactory },
            onCloudLiveBatchFallback = { fallbacks++ },
            cloudLiveSessionCutoffMs = 300L,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine).also { engines += it } }
        val pcm = realSizedPcm()

        runtime.onTap()
        val session = liveFactory.sessions.single()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        runtime.onTap()
        engines.single().finishAs(pcm, RecordingEngine.StopReason.USER)
        session.complete(CloudLiveTerminal.Success("short live transcript", CloudLiveTiming(1, finalAtMs = 2)))
        awaitIdle(runtime)

        val closesAfterLiveDelivery = session.closes
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)

        assertEquals(0, fallbacks)
        assertEquals(closesAfterLiveDelivery, session.closes)
        assertEquals(listOf("short live transcript"), listener.delivered)
        runtime.beginShutdown()
    }
}
