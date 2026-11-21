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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSttBudgetTest {

    private lateinit var app: Application
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun tearDown() {
        server.close()
    }

    private class FakeMultiSegmentVad(
        private val segmentIntervalSamples: Int = 16_000,
    ) : VadHandle, AutoCloseable {
        var closed = false
        private var fedSamples = 0
        private var nextSegmentAt = segmentIntervalSamples
        private val queue = ArrayDeque<VadSegment>()

        override fun acceptWaveform(samples: FloatArray) {
            fedSamples += samples.size
            while (fedSamples >= nextSegmentAt) {
                val start = nextSegmentAt - segmentIntervalSamples
                queue.addLast(VadSegment(start = start, samples = FloatArray(segmentIntervalSamples) { 0.05f }))
                nextSegmentAt += segmentIntervalSamples
            }
        }

        override fun isEmpty(): Boolean = queue.isEmpty()
        override fun front(): VadSegment = queue.first()
        override fun pop() { queue.removeFirst() }
        override fun flush() {}
        override fun close() { closed = true }
    }

    private fun pcm16File(samplesCount: Int): File {
        val file = File.createTempFile("local_stt_", ".pcm", app.cacheDir)
        val bytes = ByteArray(samplesCount * 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesCount) {
            buf.putShort(((i % 100) * 10).toShort())
        }
        file.writeBytes(bytes)
        return file
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

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

    private class ObservingListener : RuntimeListener {
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<BackgroundFailure>())
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
            delivered += text
        }
        override fun foregroundPackageName(): String? = null
        override fun onDictationFailed(failure: BackgroundFailure) {
            failures += failure
        }
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

    @Test fun `constants match 180s local STT budget and 25s unsegmented chunk size`() {
        assertEquals(180_000L, TranscriptionChain.LOCAL_STT_BUDGET_MS)
        assertEquals(180_000L, LocalTranscriber.LOCAL_STT_BUDGET_MS)
        assertEquals(
            AudioTranscriptionRoutes.LOCAL_UNSEGMENTED_CHUNK_SAMPLES.toInt(),
            LocalTranscriber.UNSEGMENTED_CHUNK_SAMPLES,
        )
        assertEquals(25 * 16_000, LocalTranscriber.UNSEGMENTED_CHUNK_SAMPLES)
    }

    @Test fun `VAD-segmented path enforces 180s deadline between segments`() {
        val file = pcm16File(samplesCount = 48_000) // 3s -> 3 segments at 1s each
        val vad = FakeMultiSegmentVad(segmentIntervalSamples = 16_000)
        var fakeNow = 10_000L
        val decodedCount = AtomicInteger(0)

        try {
            LocalTranscriber.transcribeFileSegmented(
                file = file,
                vad = vad,
                sampleRate = 16_000,
                deadlineAtMs = fakeNow + LocalTranscriber.LOCAL_STT_BUDGET_MS,
                nowMs = { fakeNow },
            ) { _, _, remainingMs ->
                assertNotNull(remainingMs)
                decodedCount.incrementAndGet()
                // Simulate segment 1 consuming the entire 180s stage budget.
                fakeNow += LocalTranscriber.LOCAL_STT_BUDGET_MS
                "seg1"
            }
            fail("Expected LocalTranscriptionTimeoutException")
        } catch (e: LocalTranscriptionTimeoutException) {
            assertEquals("only segment 1 should decode before hitting 180s deadline", 1, decodedCount.get())
        } finally {
            file.delete()
        }
    }

    @Test fun `unsegmented path splits PCM into 25s chunks and enforces 180s deadline between chunks`() {
        val file = pcm16File(samplesCount = 60 * 16_000) // 60s -> 25s + 25s + 10s chunks
        try {
            val chunkSizes = mutableListOf<Int>()
            val joined = LocalTranscriber.transcribeFileUnsegmentedChunked(
                file = file,
                sampleRate = 16_000,
                deadlineAtMs = 1_000_000L,
                nowMs = { 0L },
            ) { samples, _, _ ->
                chunkSizes += samples.size
                "c${chunkSizes.size}"
            }
            assertEquals(listOf(400_000, 400_000, 160_000), chunkSizes)
            assertEquals("c1 c2 c3", joined)

            var fakeNow = 0L
            val timedSizes = mutableListOf<Int>()
            try {
                LocalTranscriber.transcribeFileUnsegmentedChunked(
                    file = file,
                    sampleRate = 16_000,
                    deadlineAtMs = LocalTranscriber.LOCAL_STT_BUDGET_MS,
                    nowMs = { fakeNow },
                ) { samples, _, _ ->
                    timedSizes += samples.size
                    fakeNow += LocalTranscriber.LOCAL_STT_BUDGET_MS
                    "c1"
                }
                fail("Expected LocalTranscriptionTimeoutException")
            } catch (_: LocalTranscriptionTimeoutException) {
                assertEquals(listOf(400_000), timedSizes)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun `decodeWithDeadline bounds blocking native call via BoundedBlockingCall`() {
        val block = CountDownLatch(1)
        try {
            val t0 = System.currentTimeMillis()
            try {
                LocalTranscriber.decodeWithDeadline(remainingMs = 150L) {
                    block.await()
                    "unreachable"
                }
                fail("Expected LocalTranscriptionTimeoutException")
            } catch (_: LocalTranscriptionTimeoutException) {
                val elapsed = System.currentTimeMillis() - t0
                assertTrue("Expected timeout near 150ms, took ${elapsed}ms", elapsed < 2_000L)
            }
        } finally {
            block.countDown()
        }
    }

    @Test fun `chain fallback VAD-segmented local 180s timeout records failure and releases session cleanly`() {
        val base = server.url("/v1").toString().trimEnd('/')
        val p1 = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", id = "p1-oai")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model", id = "loc")
        ProviderChainStore.save(app, ProviderChain(listOf(p1, local)))
        ProviderCredentialStore.set(app, "p1-oai", "key-1")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)

        val modelDir = File(app.filesDir, "models/fake-local-vad-timeout").apply { mkdirs() }
        ModelDownloader.completeMarker(modelDir).writeText("")

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(503).body("""{"error":{"message":"busy"}}""").build()
        }

        val vad = FakeMultiSegmentVad(segmentIntervalSamples = 16_000)
        var fakeNow = 10_000L
        val decodedSegments = AtomicInteger(0)
        val listener = ObservingListener()
        val engines = mutableListOf<FakeEngine>()
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            transcriberHooks = TranscriberHooks(
                createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java) },
                releaseLocal = {},
                decodeLocal = { _, _, _ ->
                    decodedSegments.incrementAndGet()
                    fakeNow += TranscriptionChain.LOCAL_STT_BUDGET_MS
                    "partial segment"
                },
                openVad = { vad },
            ),
            nowMs = { fakeNow },
            transcriptionRetryBackoffMs = 0L,
            engineFactory = { dir, sm -> FakeEngine(dir, sm).also { engines += it } },
        )

        val pcm = pcm16File(samplesCount = 48_000)
        try {
            runtime.onTap()
            runtime.onTap()
            engines.single().finish(pcm)

            awaitCondition { listener.failures.isNotEmpty() && runtime.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(1, decodedSegments.get())
            assertTrue("VAD handle must be closed on timeout", vad.closed)
            assertFalse("PCM file must be cleaned up on chain exhaustion", pcm.exists())
            assertTrue(listener.delivered.isEmpty())
            assertEquals(listOf(BackgroundFailure.FAILED), listener.failures)
            val toast = ShadowToast.getTextOfLatestToast()
            assertTrue("Expected timeout error toast, got $toast", toast?.contains("timed out") == true)

            // Confirm lease was released cleanly.
            runtime.onTap()
            assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        } finally {
            runtime.shutdown()
            modelDir.deleteRecursively()
            pcm.delete()
        }
    }

    @Test fun `chain fallback unsegmented blocking local decode hits BoundedBlockingCall deadline and releases session`() {
        val base = server.url("/v1").toString().trimEnd('/')
        val p1 = ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe", id = "p1-oai")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local-model", id = "loc")
        ProviderChainStore.save(app, ProviderChain(listOf(p1, local)))
        ProviderCredentialStore.set(app, "p1-oai", "key-1")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().putBoolean("use_local", false).commit()
        PostProcessingToggle.setEnabled(app, false)

        val modelDir = File(app.filesDir, "models/fake-local-unseg-timeout").apply { mkdirs() }
        ModelDownloader.completeMarker(modelDir).writeText("")

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(503).body("""{"error":{"message":"busy"}}""").build()
        }

        val hangLatch = CountDownLatch(1)
        val listener = ObservingListener()
        val engines = mutableListOf<FakeEngine>()
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            transcriberHooks = TranscriberHooks(
                createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java) },
                releaseLocal = {},
                decodeLocal = { _, _, _ ->
                    hangLatch.await()
                    "unreachable"
                },
                openVad = { null },
            ),
            transcriptionRetryBackoffMs = 0L,
            localSttBudgetMs = 150L,
            engineFactory = { dir, sm -> FakeEngine(dir, sm).also { engines += it } },
        )

        val pcm = pcm16File(samplesCount = 16_000)
        try {
            runtime.onTap()
            runtime.onTap()
            engines.single().finish(pcm)

            awaitCondition { listener.failures.isNotEmpty() && runtime.currentState() == RecordingStateMachine.State.IDLE }

            assertFalse("PCM file must be cleaned up on chain exhaustion", pcm.exists())
            val toast = ShadowToast.getTextOfLatestToast()
            assertTrue("Expected timeout error toast, got $toast", toast?.contains("timed out") == true)

            runtime.onTap()
            assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        } finally {
            hangLatch.countDown()
            runtime.shutdown()
            modelDir.deleteRecursively()
            pcm.delete()
        }
    }

    @Test fun `direct local 180s timeout reports TIMED_OUT and releases session cleanly`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("use_local", true)
            .putString("model_name", "fake-direct-local")
            .commit()
        val modelDir = File(app.filesDir, "models/fake-direct-local").apply { mkdirs() }
        ModelDownloader.completeMarker(modelDir).writeText("")

        val vad = FakeMultiSegmentVad(segmentIntervalSamples = 16_000)
        var fakeNow = 0L
        val listener = ObservingListener()
        val engines = mutableListOf<FakeEngine>()
        val runtime = DictationRuntime(
            app,
            listener,
            InMemoryDictationSessionLeaseRegistry(),
            transcriberHooks = TranscriberHooks(
                createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java) },
                releaseLocal = {},
                decodeLocal = { _, _, _ ->
                    fakeNow += TranscriptionChain.LOCAL_STT_BUDGET_MS
                    "seg1"
                },
                openVad = { vad },
            ),
            nowMs = { fakeNow },
            engineFactory = { dir, sm -> FakeEngine(dir, sm).also { engines += it } },
        )

        val pcm = pcm16File(samplesCount = 48_000)
        try {
            runtime.onTap()
            runtime.onTap()
            engines.single().finish(pcm)

            awaitCondition { listener.failures.isNotEmpty() && runtime.currentState() == RecordingStateMachine.State.IDLE }

            assertEquals(listOf(BackgroundFailure.TIMED_OUT), listener.failures)
            assertTrue(vad.closed)
            assertFalse(pcm.exists())
            assertNull(ShadowToast.getTextOfLatestToast()?.takeIf { it.isBlank() })
            runtime.onTap()
            assertEquals(RecordingStateMachine.State.RECORDING, runtime.currentState())
        } finally {
            runtime.shutdown()
            modelDir.deleteRecursively()
            pcm.delete()
        }
    }
}
