package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCleanupProviderTest {

    @Test fun `endpoint url builds the resource path and carries no api key`() {
        val url = GeminiCleanupProvider.endpointUrl("gemini-2.5-flash")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            url,
        )
        // The key must never ride in the URL (M-3) -- it's a header instead.
        assertFalse(url.contains("key"))
        assertFalse(url.contains("?"))
    }

    @Test fun `blank model falls back to the default model`() {
        val url = GeminiCleanupProvider.endpointUrl("")
        assertTrue(url.endsWith("/models/${GeminiCleanupProvider.DEFAULT_MODEL}:generateContent"))
    }

    @Test fun `endpointUrl strips leading models prefix so resource names do not double up`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemma-4-31b-it:generateContent",
            GeminiCleanupProvider.endpointUrl("models/gemma-4-31b-it"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            GeminiCleanupProvider.endpointUrl("  MODELS/gemini-3.5-flash-lite  "),
        )
    }

    @Test fun `key travels in the x-goog-api-key header`() {
        val headers = GeminiCleanupProvider.headers("secret-key")
        assertEquals("secret-key", headers["x-goog-api-key"])
        assertNull(headers["Authorization"])
        assertNull(headers["key"])
    }

    @Test fun `request body uses native contents and systemInstruction shape`() {
        val body = GeminiCleanupProvider.buildRequestBody("raw text", "system prompt")
        assertEquals(
            "system prompt",
            body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals(
            "raw text",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        assertEquals(0.0, body.getJSONObject("generationConfig").getDouble("temperature"), 0.0)
    }

    @Test fun `parses a successful candidates response`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"cleaned up text"}]}}]}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertEquals("cleaned up text", result.text)
        assertNull(result.error)
    }

    @Test fun `concatenates multiple text parts of a single candidate`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"world"}]}}]}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertEquals("hello world", result.text)
        assertNull(result.error)
    }

    @Test fun `empty candidates array reports no candidates`() {
        val result = GeminiCleanupProvider.parseResponse("""{"candidates":[]}""")
        assertNull(result.text)
        assertEquals("No candidates in response", result.error)
    }

    @Test fun `a safety-blocked candidate with no content parts reports safety finish reason`() {
        // Gemini returns a candidate with finishReason SAFETY and no content.parts on a blocked
        // response; the parser must report the finishReason failure, not crash or return null text.
        val json = """{"candidates":[{"finishReason":"SAFETY","content":{}}]}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertNull(result.text)
        assertEquals("Gemini finished with SAFETY", result.error)
    }

    @Test fun `cleanup and transcription reject non-blank text when finishReason is MAX_TOKENS SAFETY or RECITATION`() {
        for (reason in listOf("MAX_TOKENS", "SAFETY", "RECITATION")) {
            val body = """{"candidates":[{"finishReason":"$reason","content":{"parts":[{"text":"partial or unsafe output"}]}}]}"""
            val cleanupRes = GeminiCleanupProvider.parseResponse(body)
            assertNull(cleanupRes.text)
            assertEquals("Gemini finished with $reason", cleanupRes.error)

            val sttRes = GeminiTranscriberClient.parseResponse(body)
            assertNull(sttRes.text)
            assertEquals("Gemini finished with $reason", sttRes.error)
        }
    }

    @Test fun `parses a Gemini error envelope`() {
        val json = """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
        val result = GeminiCleanupProvider.parseResponse(json)
        assertNull(result.text)
        assertEquals("API key not valid", result.error)
    }

    @Test fun `malformed json reports a parse error instead of throwing`() {
        val result = GeminiCleanupProvider.parseResponse("not json")
        assertNull(result.text)
        assertTrue(result.error != null)
    }

    @Test fun `parseResponse filters thought parts and ignores explicit json null text`() {
        val thoughtAndNull = """{
            "candidates": [{
                "content": {
                    "parts": [
                        {"text": "internal thought", "thought": true},
                        {"text": null},
                        {"text": "cleaned text"}
                    ]
                }
            }]
        }"""
        val result = GeminiCleanupProvider.parseResponse(thoughtAndNull)
        assertEquals("cleaned text", result.text)
        assertNull(result.error)

        val onlyNull = """{"candidates":[{"content":{"parts":[{"text":null}]}}]}"""
        val nullResult = GeminiCleanupProvider.parseResponse(onlyNull)
        assertNull(nullResult.text)
        assertEquals("No text content in response (finishReason=unknown)", nullResult.error)
    }
}

class GeminiTranscriberClientTest {

    @Test fun `parses a candidates transcript`() {
        val r = GeminiTranscriberClient.parseResponse("""{"candidates":[{"content":{"parts":[{"text":"hi there"}]}}]}""")
        assertEquals("hi there", r.text)
        assertNull(r.error)
    }

    @Test fun `parseResponse filters thought parts and ignores explicit json null text`() {
        val mixed = """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":null},{"text":"spoken words"}]}}]}"""
        val ok = GeminiTranscriberClient.parseResponse(mixed)
        assertEquals("spoken words", ok.text)
        assertNull(ok.error)

        val nullStop = """{"candidates":[{"content":{"parts":[{"text":null}]},"finishReason":"STOP"}]}"""
        val stop = GeminiTranscriberClient.parseResponse(nullStop)
        assertEquals("", stop.text)
        assertNull(stop.error)
        assertEquals("empty_stop(thoughtParts=0)", stop.dropReason)

        val twoThoughtsStop = """{"candidates":[{"content":{"parts":[{"text":"t1","thought":true},{"text":"t2","thought":true}]},"finishReason":"STOP"}]}"""
        val thoughtStop = GeminiTranscriberClient.parseResponse(twoThoughtsStop)
        assertEquals("", thoughtStop.text)
        assertNull(thoughtStop.error)
        assertEquals("empty_stop(thoughtParts=2)", thoughtStop.dropReason)
    }

    @Test fun `small recordings are allowed on the inline-audio path (M6)`() {
        assertTrue(GeminiTranscriberClient.canInlineAudio(1L))
        assertTrue(GeminiTranscriberClient.canInlineAudio(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES))
    }

    @Test fun `an oversized recording falls off the inline-audio path (M6)`() {
        assertFalse(GeminiTranscriberClient.canInlineAudio(GeminiTranscriberClient.MAX_INLINE_PCM_BYTES + 1))
    }

    @Test fun `buildRequestBody defaults to audio wav mime type`() {
        val body = GeminiTranscriberClient.buildRequestBody(ByteArray(4))
        val audioPart = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1)
        assertEquals("audio/wav", audioPart.getJSONObject("inline_data").getString("mime_type"))
    }

    @Test fun `buildRequestBody honors an explicit audio aac mime type (#109)`() {
        val body = GeminiTranscriberClient.buildRequestBody(ByteArray(4), mimeType = "audio/aac")
        val audioPart = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1)
        assertEquals("audio/aac", audioPart.getJSONObject("inline_data").getString("mime_type"))
    }
}
