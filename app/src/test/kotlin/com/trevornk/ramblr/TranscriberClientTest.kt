package com.trevornk.ramblr

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TranscriberClientTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    // --- transcript trimming (#140) ---
    // A trailing newline in the model's transcript was injected verbatim into the user's field,
    // leaving the cursor several lines below the dictated text (reported against Google Keep).
    // Every other transcription parser in the app already trims; this one was the exception.

    @Test fun `trailing newlines are trimmed off the transcript`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world\n\n\n"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `leading whitespace is trimmed off the transcript`() {
        val r = TranscriberClient.parseResponse("""{"text": "  Hello world"}""")
        assertEquals("Hello world", r.text)
    }

    @Test fun `interior newlines are preserved`() {
        // Only the edges are trimmed -- a genuine multi-line dictation must survive intact.
        val r = TranscriberClient.parseResponse("""{"text": "\nline one\nline two\n"}""")
        assertEquals("line one\nline two", r.text)
    }

    // -- endpoint construction honors the base-URL override (M5) --

    @Test fun `default base url resolves to OpenAI's transcriptions endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionEndpoint(PostProcessor.DEFAULT_BASE_URL),
        )
    }

    @Test fun `a proxy base url is honored, not hardcoded to api openai com (M5)`() {
        assertEquals(
            "https://proxy.example.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionEndpoint("https://proxy.example.com/v1"),
        )
    }

    @Test fun `a blank base url falls back to the OpenAI default`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            TranscriberClient.transcriptionEndpoint(""),
        )
    }

    // -- #114: vocabulary terms as structured keywords[] vs legacy prompt --

    @Test fun `gpt-transcribe gets one keywords part per term, preserving multi-word terms`() {
        val parts = TranscriberClient.vocabularyFormParts(
            "gpt-transcribe", listOf("Solveit", "Claude Code", "Hetzner"),
        )
        assertEquals(
            listOf("keywords[]" to "Solveit", "keywords[]" to "Claude Code", "keywords[]" to "Hetzner"),
            parts,
        )
    }

    @Test fun `dated gpt-transcribe snapshots still qualify for keywords`() {
        assertTrue(TranscriberClient.supportsKeywords("gpt-transcribe-2026-07-31"))
    }

    @Test fun `gpt-4o-transcribe and whisper-1 keep the legacy comma-joined prompt`() {
        for (model in listOf("gpt-4o-transcribe", "whisper-1")) {
            assertEquals(
                listOf("prompt" to "Solveit, Claude Code"),
                TranscriberClient.vocabularyFormParts(model, listOf("Solveit", "Claude Code")),
            )
        }
    }

    @Test fun `the shipped default model uses structured keywords`() {
        // Guard: if DEFAULT_MODEL ever moves to a family without keywords support,
        // supportsKeywords must be updated in the same change.
        assertTrue(TranscriberClient.supportsKeywords(TranscriberClient.DEFAULT_MODEL))
    }

    @Test fun `no terms yields no vocabulary form parts on any model`() {
        assertEquals(emptyList<Pair<String, String>>(), TranscriberClient.vocabularyFormParts("gpt-transcribe", emptyList()))
        assertEquals(emptyList<Pair<String, String>>(), TranscriberClient.vocabularyFormParts("whisper-1", emptyList()))
    }

    // --- #290: dictation language ---

    @Test fun `auto-detect sends no language part, same as every pre-290 build`() {
        assertEquals(emptyList<Pair<String, String>>(), TranscriberClient.languageFormParts(null))
        assertEquals(emptyList<Pair<String, String>>(), TranscriberClient.languageFormParts(""))
    }

    @Test fun `a chosen language is sent as the ISO-639-1 language field`() {
        assertEquals(listOf("language" to "de"), TranscriberClient.languageFormParts("de"))
    }

    // --- Phase 2D: OkHttp callTimeout vs user cancel ---

    @Test fun `okhttp callTimeout without cancelHolder is treated as transient timeout not user cancellation`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"text":"late"}""")
                    .headersDelay(900, TimeUnit.MILLISECONDS)
                    .build()
            )
            val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
            val holder = InFlightCall().apply { beginWork() }
            val latch = CountDownLatch(1)
            val ref = AtomicReference<TranscriberClient.Result>()
            TranscriberClient.transcribe(
                pcmFile = pcm,
                apiKey = "key",
                cancelHolder = holder,
                baseUrl = server.url("/v1").toString(),
                callTimeoutMs = 250L,
            ) {
                ref.set(it)
                latch.countDown()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            val result = ref.get()
            assertFalse("callTimeout must never report user cancellation", result.isCancelled)
            assertTrue("callTimeout must be retryable transient IO", result.isTransientIo)
            assertEquals(CloudErrorKind.TEMPORARY_BUSY, result.errorKind)
            assertNotNull(result.error)
        } finally {
            server.close()
        }
    }

    @Test fun `explicit cancelHolder cancellation still reports isCancelled true`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"text":"never"}""")
                    .headersDelay(2_000, TimeUnit.MILLISECONDS)
                    .build()
            )
            val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
            val holder = InFlightCall().apply { beginWork() }
            val latch = CountDownLatch(1)
            val ref = AtomicReference<TranscriberClient.Result>()
            TranscriberClient.transcribe(
                pcmFile = pcm,
                apiKey = "key",
                cancelHolder = holder,
                baseUrl = server.url("/v1").toString(),
                callTimeoutMs = 5_000L,
            ) {
                ref.set(it)
                latch.countDown()
            }
            server.takeRequest(5, TimeUnit.SECONDS)
            holder.cancel()
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertTrue(ref.get().isCancelled)
        } finally {
            server.close()
        }
    }

    @Test fun `DNS failure sets isTransientIo false and PERMANENT_OR_QUOTA`() {
        val pcm = File(temp.root, "clip.pcm").apply { writeBytes(ByteArray(320)) }
        val holder = InFlightCall().apply { beginWork() }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<TranscriberClient.Result>()
        TranscriberClient.transcribe(
            pcmFile = pcm,
            apiKey = "key",
            cancelHolder = holder,
            baseUrl = "https://nonexistent.invalid/v1",
            callTimeoutMs = 5_000L,
        ) {
            ref.set(it)
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val result = ref.get()
        assertFalse(result.isCancelled)
        assertFalse("UnknownHostException must not be marked transient IO", result.isTransientIo)
        assertEquals(CloudErrorKind.PERMANENT_OR_QUOTA, result.errorKind)
    }
}
