package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CloudErrorClassifierTest {

    @Test fun `temporary busy HTTP codes classify as TEMPORARY_BUSY`() {
        val temporaryCodes = listOf(408, 498, 500, 502, 503, 504, 529)
        for (code in temporaryCodes) {
            assertEquals("Expected TEMPORARY_BUSY for HTTP $code", CloudErrorKind.TEMPORARY_BUSY, CloudErrorClassifier.classify(httpCode = code))
            assertTrue("Expected isTemporaryBusy true for HTTP $code", CloudErrorClassifier.isTemporaryBusy(httpCode = code))
        }
    }

    @Test fun `quota account and permanent HTTP codes classify as PERMANENT_OR_QUOTA`() {
        val permanentCodes = listOf(400, 401, 402, 403, 404, 413, 422, 429)
        for (code in permanentCodes) {
            assertEquals("Expected PERMANENT_OR_QUOTA for HTTP $code", CloudErrorKind.PERMANENT_OR_QUOTA, CloudErrorClassifier.classify(httpCode = code))
            assertFalse("Expected isTemporaryBusy false for HTTP $code", CloudErrorClassifier.isTemporaryBusy(httpCode = code))
        }
    }

    @Test fun `429 never retries even if body mentions high demand or overloaded`() {
        assertEquals(
            CloudErrorKind.PERMANENT_OR_QUOTA,
            CloudErrorClassifier.classify(httpCode = 429, errorText = "Model overloaded due to high demand"),
        )
        assertEquals(
            CloudErrorKind.PERMANENT_OR_QUOTA,
            CloudErrorClassifier.classify(httpCode = 402, errorText = "Insufficient credits; overloaded"),
        )
    }

    @Test fun `overloaded and high demand messages classify as TEMPORARY_BUSY`() {
        assertEquals(
            CloudErrorKind.TEMPORARY_BUSY,
            CloudErrorClassifier.classify(httpCode = 200, errorText = "This model is currently experiencing high demand."),
        )
        assertEquals(
            CloudErrorKind.TEMPORARY_BUSY,
            CloudErrorClassifier.classify(httpCode = null, errorText = "Service OVERLOADED, please retry"),
        )
    }

    @Test fun `transient IO and socket timeouts classify as TEMPORARY_BUSY`() {
        assertEquals(CloudErrorKind.TEMPORARY_BUSY, CloudErrorClassifier.classify(SocketTimeoutException("timeout")))
        assertEquals(CloudErrorKind.TEMPORARY_BUSY, CloudErrorClassifier.classify(IOException("connection reset")))
        assertEquals(CloudErrorKind.TEMPORARY_BUSY, CloudErrorClassifier.classify(httpCode = null, isTransientIo = true))
        assertEquals(CloudErrorKind.TEMPORARY_BUSY, CloudErrorClassifier.classify(httpCode = null, errorText = "Read timed out"))
    }

    @Test fun `cleanup executor bounded busy-retry covers all cloud providers`() {
        val stepsAndSuccessBodies = listOf(
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini") to """{"choices":[{"message":{"content":"openai ok"}}]}""",
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "llama-3.3-70b-versatile", baseUrlOverride = ProviderPresets.GROQ.baseUrl, presetId = ProviderPresets.GROQ.id) to """{"choices":[{"message":{"content":"groq ok"}}]}""",
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "openai/gpt-5.4-mini", baseUrlOverride = ProviderPresets.OPENROUTER.baseUrl, presetId = ProviderPresets.OPENROUTER.id) to """{"choices":[{"message":{"content":"openrouter ok"}}]}""",
            CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "gemini-3.5-flash-lite") to """{"candidates":[{"content":{"parts":[{"text":"gemini ok"}]}}]}""",
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5") to """{"content":[{"type":"text","text":"anthropic ok"}]}""",
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6") to """{"choices":[{"message":{"content":"omniroute ok"}}]}""",
        )

        for ((step, okJson) in stepsAndSuccessBodies) {
            var requests = 0
            val transport = CleanupHttpTransport { _, _, _, _, _, callback ->
                requests++
                if (requests == 1) callback(CleanupHttpOutcome.HttpError(503, """{"error":{"message":"high demand"}}"""))
                else callback(CleanupHttpOutcome.Ok(okJson))
            }
            var result: PostProcessor.Result? = null
            CleanupWaterfallExecutor.execute(
                text = "raw transcript",
                prompt = "clean it up",
                waterfall = CleanupWaterfall(listOf(step)),
                cursor = CleanupWaterfallCursor(),
                cancelHolder = InFlightCall(),
                credentialLookup = { "test-key" },
                transport = transport,
                busyRetryDelayMs = 0L,
                scheduleRetry = { _, action -> action() },
                callback = { result = it },
            )
            assertEquals("Expected 2 requests for ${step.group}/${step.presetId}", 2, requests)
            assertNull(result?.error)
            assertTrue(result?.text?.endsWith("ok") == true)
        }
    }

    @Test fun `cleanup executor does not retry 429 401 402 403 or 400 non-temperature errors`() {
        for (code in listOf(429, 401, 402, 403, 400)) {
            var requests = 0
            val transport = CleanupHttpTransport { _, _, _, _, _, callback ->
                requests++
                callback(CleanupHttpOutcome.HttpError(code, """{"error":{"message":"failed with $code"}}"""))
            }
            var result: PostProcessor.Result? = null
            CleanupWaterfallExecutor.execute(
                text = "raw",
                prompt = "clean",
                waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"))),
                cursor = CleanupWaterfallCursor(),
                cancelHolder = InFlightCall(),
                credentialLookup = { "test-key" },
                transport = transport,
                busyRetryDelayMs = 0L,
                scheduleRetry = { _, action -> action() },
                callback = { result = it },
            )
            assertEquals("HTTP $code must fail over without retry", 1, requests)
            assertNull(result?.text)
        }
    }

    @Test fun `cleanup executor busy-retry respects remaining budget and cancelHolder`() {
        var now = 1_000L
        var requestsOnExpired = 0
        var expiredResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "test-key" },
            transport = CleanupHttpTransport { _, _, _, _, _, callback ->
                requestsOnExpired++
                now = 1_000L + CLEANUP_WATERFALL_HARD_CAP_MS - 100L
                callback(CleanupHttpOutcome.HttpError(503, "overloaded"))
            },
            nowMs = { now },
            busyRetryDelayMs = 400L,
            scheduleRetry = { _, action -> action() },
            callback = { expiredResult = it },
        )
        assertEquals(1, requestsOnExpired)
        assertNull(expiredResult?.text)

        val holder = InFlightCall()
        var requestsOnCancel = 0
        var cancelledResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = holder,
            credentialLookup = { "test-key" },
            transport = CleanupHttpTransport { _, _, _, _, _, callback ->
                requestsOnCancel++
                holder.cancel()
                callback(CleanupHttpOutcome.HttpError(503, "overloaded"))
            },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, action -> action() },
            callback = { cancelledResult = it },
        )
        assertEquals(1, requestsOnCancel)
        assertEquals("Cleanup cancelled", cancelledResult?.error)
    }

    @Test fun `cleanup executor preserves HTTP 400 temperature retry and OmniRoute ConnectionFailed fail-fast`() {
        var tempBodies = 0
        var tempResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "model-x"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { _, _, _, _, _, callback ->
                tempBodies++
                if (tempBodies == 1) {
                    callback(CleanupHttpOutcome.HttpError(400, """{"error":{"type":"unsupported_value","code":"unsupported_value","param":"temperature","message":"temperature is unsupported"}}"""))
                } else {
                    callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"temp ok"}}]}"""))
                }
            },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, action -> action() },
            callback = { tempResult = it },
        )
        assertEquals(2, tempBodies)
        assertEquals("temp ok", tempResult?.text)

        var omniUrls = 0
        var omniResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/sonnet"),
                    CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt"),
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"),
                ),
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { url, _, _, _, _, callback ->
                omniUrls++
                if (omniUrls == 1) callback(CleanupHttpOutcome.ConnectionFailure("unreachable"))
                else callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"fallback ok"}}]}"""))
            },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, action -> action() },
            callback = { omniResult = it },
        )
        assertEquals(2, omniUrls)
        assertEquals("fallback ok", omniResult?.text)
    }

    @Test fun `AudioJobRunner retries once only on TEMPORARY_BUSY`() {
        assertTrue(AudioJobRunner.shouldRetryCloudChunk(0, CloudErrorKind.TEMPORARY_BUSY))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(1, CloudErrorKind.TEMPORARY_BUSY))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(0, CloudErrorKind.PERMANENT_OR_QUOTA))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(1, CloudErrorKind.PERMANENT_OR_QUOTA))
    }
}
