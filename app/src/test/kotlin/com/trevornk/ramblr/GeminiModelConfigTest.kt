package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelConfigTest {

    @Test fun `gemma-4 models send MINIMAL thinkingLevel and never thinkingBudget`() {
        for (model in listOf("gemma-4-31b-it", "gemma-4-26b-a4b-it")) {
            val cleanupBody = GeminiCleanupProvider.buildRequestBody("raw text", "system prompt", model)
            val cleanupGenConfig = cleanupBody.getJSONObject("generationConfig")
            val cleanupThinking = cleanupGenConfig.getJSONObject("thinkingConfig")
            assertEquals("MINIMAL", cleanupThinking.getString("thinkingLevel"))
            assertFalse(cleanupThinking.has("thinkingBudget"))
            assertEquals(0.0, cleanupGenConfig.getDouble("temperature"), 0.0)

            val sttBody = GeminiTranscriberClient.buildRequestBody(ByteArray(4), model = model)
            val sttThinking = sttBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
            assertEquals("MINIMAL", sttThinking.getString("thinkingLevel"))
            assertFalse(sttThinking.has("thinkingBudget"))
        }
    }

    @Test fun `gemini-3_8 models send LOW thinkingLevel and proactively omit temperature`() {
        val model = "gemini-3.8-flash"
        val cleanupBody = GeminiCleanupProvider.buildRequestBody("raw text", "system prompt", model)
        val cleanupGenConfig = cleanupBody.getJSONObject("generationConfig")
        assertFalse("temperature must be omitted on $model", cleanupGenConfig.has("temperature"))
        val cleanupThinking = cleanupGenConfig.getJSONObject("thinkingConfig")
        assertEquals("LOW", cleanupThinking.getString("thinkingLevel"))
        assertFalse(cleanupThinking.has("thinkingBudget"))

        val sttBody = GeminiTranscriberClient.buildRequestBody(ByteArray(4), model = model)
        val sttGenConfig = sttBody.getJSONObject("generationConfig")
        assertFalse(sttGenConfig.has("temperature"))
        assertEquals("LOW", sttGenConfig.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test fun `existing gemini-3_1 and gemini-3_5 models stay completely untouched`() {
        for (model in listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite")) {
            val cleanupBody = GeminiCleanupProvider.buildRequestBody("raw text", "system prompt", model)
            val cleanupGenConfig = cleanupBody.getJSONObject("generationConfig")
            assertEquals(0.0, cleanupGenConfig.getDouble("temperature"), 0.0)
            assertFalse(cleanupGenConfig.has("thinkingConfig"))

            val sttBody = GeminiTranscriberClient.buildRequestBody(ByteArray(4), model = model)
            assertFalse(sttBody.has("generationConfig"))
        }
    }

    @Test fun `response parsers skip thought parts in both cleanup and transcription`() {
        val json = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"text": "internal chain of thought", "thought": true},
                    {"text": "actual "},
                    {"text": "answer", "thought": false}
                  ]
                },
                "finishReason": "STOP"
              }]
            }
        """.trimIndent()

        val cleanupResult = GeminiCleanupProvider.parseResponse(json)
        assertEquals("actual answer", cleanupResult.text)
        assertNull(cleanupResult.error)

        val sttResult = GeminiTranscriberClient.parseResponse(json)
        assertEquals("actual answer", sttResult.text)
        assertNull(sttResult.error)
    }

    @Test fun `thought-only response with no output text does not leak thinking text`() {
        val thoughtOnly = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"text": "secret reasoning", "thought": true}
                  ]
                },
                "finishReason": "STOP"
              }]
            }
        """.trimIndent()

        val cleanupResult = GeminiCleanupProvider.parseResponse(thoughtOnly)
        assertNull(cleanupResult.text)
        assertEquals("No text content in response (finishReason=STOP)", cleanupResult.error)

        val sttResult = GeminiTranscriberClient.parseResponse(thoughtOnly)
        assertEquals("", sttResult.text)
        assertNull(sttResult.error)
        assertEquals("empty_stop(thoughtParts=1)", sttResult.dropReason)
    }

    @Test fun `CleanupWaterfallExecutor applies model-specific Gemini config on GEMINI_DIRECT steps`() {
        val sentBodies = mutableListOf<JSONObject>()
        val transport = CleanupHttpTransport { _, _, jsonBody, _, _, callback ->
            sentBodies += JSONObject(jsonBody)
            callback(CleanupHttpOutcome.Ok("""{"candidates":[{"content":{"parts":[{"text":"reasoning","thought":true},{"text":"cleaned"}]}}]}"""))
        }

        var result: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "gemini-3.8-flash"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "gem-key" },
            transport = transport,
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, action -> action() },
            callback = { result = it },
        )

        val genConfig = sentBodies.single().getJSONObject("generationConfig")
        assertFalse(genConfig.has("temperature"))
        assertEquals("LOW", genConfig.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertEquals("cleaned", result?.text)
    }

    @Test fun `supportsAudioTranscription blocks text-only Gemma 4 models and allows audio-capable models`() {
        for (blocked in listOf("gemma-4-31b-it", "gemma-4-26b-a4b-it", "models/gemma-4-31b-it", "gemma-4-27b-it")) {
            assertFalse("Expected $blocked to be blocked from audio STT", GeminiTranscriberClient.supportsAudioTranscription(blocked))
        }
        for (allowed in listOf("gemma-4-e2b-it", "gemma-4-e4b-it", "gemma-4-12b-it", "models/gemma-4-e4b-it", "gemini-3.1-flash-lite", "gemini-3.8-flash", null, "")) {
            assertTrue("Expected $allowed to be allowed for audio STT", GeminiTranscriberClient.supportsAudioTranscription(allowed))
        }
    }

    @Test fun `GeminiTranscriberClient transcribe rejects text-only Gemma 4 immediately without IO`() {
        var result: GeminiTranscriberClient.Result? = null
        GeminiTranscriberClient.transcribe(
            pcmFile = java.io.File("/nonexistent/should-not-read.pcm"),
            apiKey = "key",
            model = "gemma-4-31b-it",
            cancelHolder = InFlightCall(),
        ) { result = it }
        assertNull(result?.text)
        assertTrue(result?.error?.contains("does not support audio transcription") == true)
    }
}
