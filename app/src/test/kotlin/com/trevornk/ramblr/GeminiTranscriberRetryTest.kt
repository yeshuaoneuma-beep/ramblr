package com.trevornk.ramblr

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Gemini's "high demand" 503s are retried a couple of times before the dictation is given up. */
class GeminiTranscriberRetryTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.close()

    private val overloaded = MockResponse.Builder().code(503).body(
        """{"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}""",
    ).build()

    private fun transcript(text: String) = MockResponse.Builder().body(
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]},"finishReason":"STOP"}]}""",
    ).build()

    private fun transcribe(
        holder: InFlightCall = InFlightCall(),
        delays: List<Long> = listOf(10L, 10L),
        onStarted: () -> Unit = {},
    ): GeminiTranscriberClient.Result {
        val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
        val latch = CountDownLatch(1)
        val result = AtomicReference<GeminiTranscriberClient.Result>()
        GeminiTranscriberClient.transcribe(
            pcm, "key", "gemini-3.1-flash-lite", holder,
            endpoint = server.url("/v1beta/models/gemini-3.1-flash-lite:generateContent").toString(),
            retryDelaysMs = delays,
        ) { result.set(it); latch.countDown() }
        onStarted()
        assertTrue("no result", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun `one overload then success returns the transcript`() {
        server.enqueue(overloaded)
        server.enqueue(transcript("hello there"))

        val result = transcribe()

        assertEquals("hello there", result.text)
        assertEquals(2, server.requestCount)
    }

    @Test fun `gives up after the last retry with a message naming the model`() {
        repeat(3) { server.enqueue(overloaded) }

        val result = transcribe()

        assertNull(result.text)
        assertEquals(3, server.requestCount)
        assertEquals(GeminiTranscriberClient.overloadedMessage("gemini-3.1-flash-lite", 3), result.error)
    }

    @Test fun `a non-overload error is reported at once`() {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""").build())

        val result = transcribe()

        assertEquals("API key not valid.", result.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a cancel during the backoff stops the retry`() {
        server.enqueue(overloaded)
        server.enqueue(transcript("too late"))
        val holder = InFlightCall()

        val result = transcribe(holder, delays = listOf(1_000L)) {
            server.takeRequest(5, TimeUnit.SECONDS)
            holder.cancel()
        }

        assertNull(result.text)
        assertEquals(1, server.requestCount)
    }

    @Test fun `overload detection`() {
        assertTrue(GeminiTranscriberClient.isOverloaded(503, null))
        assertTrue(GeminiTranscriberClient.isOverloaded(500, "Internal error"))
        assertTrue(GeminiTranscriberClient.isOverloaded(200, "The model is overloaded. Please try again later."))
        assertFalse(GeminiTranscriberClient.isOverloaded(429, "Quota exceeded"))
        assertFalse(GeminiTranscriberClient.isOverloaded(400, "API key not valid."))
    }

    @Test fun `okhttp callTimeout without cancelHolder is treated as transient timeout not cancellation`() {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"candidates":[{"content":{"parts":[{"text":"late"}]},"finishReason":"STOP"}]}""")
                .headersDelay(900, TimeUnit.MILLISECONDS)
                .build()
        )
        val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
        val holder = InFlightCall().apply { beginWork() }
        val latch = CountDownLatch(1)
        val result = AtomicReference<GeminiTranscriberClient.Result>()
        GeminiTranscriberClient.transcribe(
            pcmFile = pcm,
            apiKey = "key",
            model = "gemini-3.1-flash-lite",
            cancelHolder = holder,
            endpoint = server.url("/v1beta/models/gemini-3.1-flash-lite:generateContent").toString(),
            retryDelaysMs = emptyList(),
            callTimeoutMs = 250L,
        ) { result.set(it); latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val r = result.get()
        assertFalse("callTimeout must not report isCancelled", r.isCancelled)
        assertTrue(r.isTransientIo)
        assertEquals(CloudErrorKind.TEMPORARY_BUSY, r.errorKind)
    }

    @Test fun `explicit cancelHolder cancellation reports isCancelled true`() {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"candidates":[{"content":{"parts":[{"text":"never"}]},"finishReason":"STOP"}]}""")
                .headersDelay(2_000, TimeUnit.MILLISECONDS)
                .build()
        )
        val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
        val holder = InFlightCall().apply { beginWork() }
        val latch = CountDownLatch(1)
        val result = AtomicReference<GeminiTranscriberClient.Result>()
        GeminiTranscriberClient.transcribe(
            pcmFile = pcm,
            apiKey = "key",
            model = "gemini-3.1-flash-lite",
            cancelHolder = holder,
            endpoint = server.url("/v1beta/models/gemini-3.1-flash-lite:generateContent").toString(),
            retryDelaysMs = emptyList(),
            callTimeoutMs = 5_000L,
        ) { result.set(it); latch.countDown() }
        server.takeRequest(5, TimeUnit.SECONDS)
        holder.cancel()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(result.get().isCancelled)
    }

    @Test fun `DNS failure sets isTransientIo false and does not retry`() {
        val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
        val holder = InFlightCall().apply { beginWork() }
        val latch = CountDownLatch(1)
        val result = AtomicReference<GeminiTranscriberClient.Result>()
        GeminiTranscriberClient.transcribe(
            pcmFile = pcm,
            apiKey = "key",
            model = "gemini-3.1-flash-lite",
            cancelHolder = holder,
            endpoint = "https://nonexistent.invalid/v1beta/models/gemini-3.1-flash-lite:generateContent",
            retryDelaysMs = listOf(10L, 10L),
            callTimeoutMs = 5_000L,
        ) { result.set(it); latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val r = result.get()
        assertFalse(r.isCancelled)
        assertFalse("UnknownHostException must not be marked transient IO", r.isTransientIo)
        assertEquals(CloudErrorKind.PERMANENT_OR_QUOTA, r.errorKind)
    }
}
