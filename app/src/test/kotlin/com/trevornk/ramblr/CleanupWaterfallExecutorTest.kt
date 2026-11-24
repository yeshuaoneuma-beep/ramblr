package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupWaterfallPlannerTest {

    @Test fun `empty steps produce no groups`() {
        assertEquals(emptyList<List<CleanupStep>>(), CleanupWaterfallPlanner.groupConsecutive(emptyList()))
    }

    @Test fun `single step is its own group`() {
        val steps = listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"))
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(1, groups.size)
        assertEquals(steps, groups[0])
    }

    @Test fun `consecutive same-group steps merge into one group`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test fun `different groups stay separate even when adjacent`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
        assertEquals(CleanupStepGroup.OMNIROUTE, groups[0][0].group)
        assertEquals(CleanupStepGroup.OPENAI_DIRECT, groups[1][0].group)
        assertEquals(CleanupStepGroup.ANTHROPIC_DIRECT, groups[2][0].group)
    }

    @Test fun `the full 5-step waterfall from ADR-0001 groups into 3 units`() {
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
        assertEquals(3, groups[0].size) // OmniRoute sub-steps fail together
        assertEquals(1, groups[1].size)
        assertEquals(1, groups[2].size)
    }

    @Test fun `revisiting the same group later stays a separate group (non-consecutive)`() {
        // Pathological but should not silently merge non-adjacent runs of the same group.
        val steps = listOf(
            CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
        )
        val groups = CleanupWaterfallPlanner.groupConsecutive(steps)
        assertEquals(3, groups.size)
    }
}

class CleanupWaterfallCursorTest {

    @Test fun `starts at index 0 with no prior success`() {
        val cursor = CleanupWaterfallCursor()
        assertEquals(0, cursor.startIndex(nowMs = 1_000L))
    }

    @Test fun `starts at last successful index within the idle window`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        assertEquals(3, cursor.startIndex(nowMs = 5_000L))
    }

    @Test fun `expires back to 0 after the idle window elapses`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        assertEquals(0, cursor.startIndex(nowMs = 20_000L))
    }

    @Test fun `reset forces back to 0 immediately, simulating a network change`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        cursor.reset()
        assertEquals(0, cursor.startIndex(nowMs = 1_001L))
    }

    @Test fun `a fresh success after reset moves the cursor again`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 3, nowMs = 1_000L)
        cursor.reset()
        cursor.recordSuccess(stepIndex = 1, nowMs = 2_000L)
        assertEquals(1, cursor.startIndex(nowMs = 2_500L))
    }

    @Test fun `idle expiry is evaluated relative to the last success, not first`() {
        val cursor = CleanupWaterfallCursor(idleExpiryMs = 10_000L)
        cursor.recordSuccess(stepIndex = 2, nowMs = 1_000L)
        cursor.recordSuccess(stepIndex = 2, nowMs = 15_000L) // renews the clock
        assertTrue(cursor.startIndex(nowMs = 20_000L) == 2) // only 5s since last success
    }
}

/** Fakes the network layer (see [CleanupHttpTransport]) so executor step-sequencing logic is
 *  testable with no real I/O: each call to [send] pops and returns the next queued outcome. */
private class FakeCleanupHttpTransport(private val outcomes: MutableList<CleanupHttpOutcome>) : CleanupHttpTransport {
    val requestedUrls = mutableListOf<String>()
    val requestedBodies = mutableListOf<String>()

    override fun send(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeouts: CleanupStepTimeouts,
        cancelHolder: InFlightCall,
        callback: (CleanupHttpOutcome) -> Unit,
    ) {
        requestedUrls.add(url)
        requestedBodies.add(jsonBody)
        check(outcomes.isNotEmpty()) { "FakeCleanupHttpTransport ran out of queued outcomes for url=$url" }
        callback(outcomes.removeAt(0))
    }
}

private fun okOutcome(text: String): CleanupHttpOutcome =
    CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"$text"}}]}""")

/** Fakes on-device inference (see [LocalInferenceEngine]) so LOCAL_LLM step-sequencing logic is
 *  testable with no native code involved -- see #37: real llama.cpp inference cannot be exercised
 *  in a JVM unit test, only this seam's plumbing can. */
private class FakeLocalInferenceEngine(private val outcomes: MutableList<LocalInferenceResult>) : LocalInferenceEngine {
    val calls = mutableListOf<Triple<String, String, String>>()
    val deadlines = mutableListOf<Long>()

    override fun complete(
        systemPrompt: String,
        userText: String,
        modelPath: String,
        deadlineAtMs: Long,
        isCancelled: () -> Boolean,
    ): LocalInferenceResult {
        calls.add(Triple(systemPrompt, userText, modelPath))
        deadlines.add(deadlineAtMs)
        check(outcomes.isNotEmpty()) { "FakeLocalInferenceEngine ran out of queued outcomes" }
        return outcomes.removeAt(0)
    }
}

class CleanupWaterfallExecutorTest {
    private val cancelHolder = InFlightCall()

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
        cursor: CleanupWaterfallCursor = CleanupWaterfallCursor(),
        credentials: Map<CleanupCredentialSlot, String> = mapOf(
            CleanupCredentialSlot.OMNIROUTE to "omni-key",
            CleanupCredentialSlot.OPENAI_DIRECT to "openai-key",
            CleanupCredentialSlot.ANTHROPIC_DIRECT to "anthropic-key",
        ),
        localInference: LocalInferenceEngine = LocalInferenceEngine { _, _, _, _, _ -> error("no local step expected") },
        localModelPath: () -> String? = { null },
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = cursor,
            cancelHolder = cancelHolder,
            credentialLookup = { slot -> credentials[slot] ?: "" },
            transport = transport,
            localInference = localInference,
            localModelPath = localModelPath,
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, action -> action() },
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `explicit temperature rejection retries once without temperature and preserves configured value initially`() {
        val bodies = mutableListOf<String>()
        var callbackCount = 0
        var final: PostProcessor.Result? = null
        val transport = CleanupHttpTransport { _, _, jsonBody, _, _, callback ->
            bodies += jsonBody
            if (bodies.size == 1) {
                callback(CleanupHttpOutcome.HttpError(400,
                    """{"error":{"type":"unsupported_value","code":"unsupported_value","param":"temperature","message":"temperature is unsupported"}}"""))
            } else {
                callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
            }
        }
        CleanupWaterfallExecutor.execute(
            text = "synthetic-independent transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "model"))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport,
            localInference = LocalInferenceEngine { _, _, _, _, _ -> error("unexpected local step") },
            callback = { callbackCount++; final = it },
        )
        assertEquals(2, bodies.size)
        assertTrue(org.json.JSONObject(bodies[0]).has("temperature"))
        assertFalse(org.json.JSONObject(bodies[1]).has("temperature"))
        assertEquals(1, callbackCount)
        assertEquals("cleaned", final?.text)
    }

    @Test fun `known rejecting model family is sent without temperature without a rejection round trip`() {
        var requestCount = 0
        var firstBody = ""
        val transport = CleanupHttpTransport { _, _, jsonBody, _, _, callback ->
            requestCount++
            firstBody = jsonBody
            callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
        }
        execute(
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "o3-mini"))),
            transport = transport,
        )
        assertEquals(1, requestCount)
        assertFalse(org.json.JSONObject(firstBody).has("temperature"))
    }

    @Test fun `expired temperature retry deadline does not send another request`() {
        var now = 100L
        var requests = 0
        var callbacks = 0
        val transport = CleanupHttpTransport { _, _, _, _, _, callback ->
            requests++
            now = 100L + CLOUD_CLEANUP_PER_PROVIDER_CAP_MS
            callback(CleanupHttpOutcome.HttpError(400,
                """{"error":{"type":"unsupported_value","code":"unsupported_value","param":"temperature","message":"temperature is unsupported"}}"""))
        }
        CleanupWaterfallExecutor.execute(
            text = "synthetic-independent transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "model"))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport,
            localInference = LocalInferenceEngine { _, _, _, _, _ -> error("unexpected local step") },
            nowMs = { now }, callback = { callbacks++ },
        )
        assertEquals(1, requests)
        assertEquals(1, callbacks)
    }

    @Test fun `sub 250ms temperature retry uses only actual remaining deadline`() {
        var now = 100L
        var requests = 0
        var retryTimeouts: CleanupStepTimeouts? = null
        var result: PostProcessor.Result? = null
        val transport = CleanupHttpTransport { _, _, _, timeouts, _, callback ->
            requests++
            if (requests == 1) {
                now = 100L + cloudCleanupPerProviderCapMs("synthetic-independent transcript".length) - 150L
                callback(CleanupHttpOutcome.HttpError(400,
                    """{"error":{"type":"unsupported_value","code":"unsupported_value","param":"temperature","message":"temperature is unsupported"}}"""))
            } else {
                retryTimeouts = timeouts
                callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
            }
        }
        CleanupWaterfallExecutor.execute(
            text = "synthetic-independent transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "model"))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport,
            localInference = LocalInferenceEngine { _, _, _, _, _ -> error("unexpected local step") },
            nowMs = { now }, callback = { result = it },
        )
        assertEquals(2, requests)
        assertEquals(150L, retryTimeouts?.callMs)
        assertEquals(150L, retryTimeouts?.connectMs)
        assertEquals("cleaned", result?.text)
    }

    @Test fun `a second structured temperature rejection ends after exactly one retry`() {
        var requests = 0
        var callbacks = 0
        var result: PostProcessor.Result? = null
        val transport = CleanupHttpTransport { _, _, _, _, _, callback ->
            requests++
            callback(CleanupHttpOutcome.HttpError(400,
                """{"error":{"type":"unsupported_parameter","code":"unsupported_parameter","param":"temperature","message":"temperature is unsupported"}}"""))
        }
        CleanupWaterfallExecutor.execute(
            text = "transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "model"))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport,
            localInference = LocalInferenceEngine { _, _, _, _, _ -> error("unexpected local step") },
            callback = { callbacks++; result = it },
        )
        assertEquals(2, requests)
        assertEquals(1, callbacks)
        assertTrue(result?.error?.contains("unsupported") == true)
    }

    @Test fun `a connection failure on the first OmniRoute sub-step skips its remaining siblings`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("host unreachable"),
                okOutcome("cleaned via direct openai"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "gemini/gemini-2.5-flash"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via direct openai", result.text)
        // Only 2 requests: the first (dead) OmniRoute attempt, then straight to OPENAI_DIRECT —
        // the other 2 OmniRoute sub-steps were skipped, not attempted and failed individually.
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a step-level failure only advances one step, siblings in the group are still tried`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(400, """{"error":{"message":"bad model"}}"""),
                okOutcome("cleaned via second omniroute model"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/typo-model"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via second omniroute model", result.text)
        assertEquals(2, transport.requestedUrls.size)
        assertEquals(transport.requestedUrls[0], transport.requestedUrls[1]) // both hit the OmniRoute host
    }

    @Test fun `a failing step surfaces the provider error message, not just the status code (L2)`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.HttpError(401, """{"error":{"message":"invalid x-api-key"}}"""))
        )
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5")))
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertTrue("expected provider detail in '${result.error}'", result.error!!.contains("invalid x-api-key"))
        assertTrue("expected the status code too", result.error!!.contains("401"))
    }

    @Test fun `a failing step with an unparseable error body falls back to a truncated raw snippet (L2)`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(502, "<html><body>Bad Gateway</body></html>"),
                CleanupHttpOutcome.HttpError(502, "<html><body>Bad Gateway</body></html>"),
            )
        )
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5")))
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertTrue("expected raw snippet in '${result.error}'", result.error!!.contains("Bad Gateway"))
    }

    @Test fun `an all-steps-failed result carries the last step's failure detail (L2)`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("dead host"),
                CleanupHttpOutcome.HttpError(429, """{"error":{"message":"rate limit exceeded"}}""", retryAfterMs = 10_000L),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertTrue("expected the last failure detail in '${result.error}'", result.error!!.contains("rate limit exceeded"))
    }

    @Test fun `waterfall signature changes when a step is added, reordered, or its model edited (M3)`() {
        val base = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val same = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val reordered = CleanupWaterfall(base.steps.asReversed())
        val modelEdited = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-opus-4-8"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val extraStep = CleanupWaterfall(base.steps + CleanupStep(CleanupStepGroup.LOCAL_LLM, ""))

        assertEquals(cleanupWaterfallSignature(base), cleanupWaterfallSignature(same))
        assertNotEquals(cleanupWaterfallSignature(base), cleanupWaterfallSignature(reordered))
        assertNotEquals(cleanupWaterfallSignature(base), cleanupWaterfallSignature(modelEdited))
        assertNotEquals(cleanupWaterfallSignature(base), cleanupWaterfallSignature(extraStep))
    }

    @Test fun `the waterfall aborts with a time-budget error once the hard cap is exceeded mid-walk (test-gap #6)`() {
        // Clock: startedAtMs and the first step's between-step check read "0" (under budget), then
        // the next between-step check jumps past the hard cap so attempt(1) reports the budget error
        // instead of trying the second step.
        var calls = 0
        val clock = { if (calls++ < 2) 0L else CLOUD_CLEANUP_STAGE_BUDGET_MS + 1L }
        val transport = FakeCleanupHttpTransport(mutableListOf(CleanupHttpOutcome.HttpError(500, "")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            )
        )
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = cancelHolder,
            credentialLookup = { "omni-key" },
            transport = transport,
            nowMs = clock,
            callback = { captured = it },
        )
        assertNull(captured?.text)
        assertTrue("expected the time-budget error, was '${captured?.error}'", captured!!.error!!.contains("time budget"))
        assertEquals(1, transport.requestedUrls.size) // only the first step ran before the cap tripped
    }

    @Test fun `a missing credential fails that step without ever calling the transport`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.Ok("""{"content":[{"type":"text","text":"fell through to anthropic"}]}"""))
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(
            waterfall,
            transport,
            credentials = mapOf(CleanupCredentialSlot.ANTHROPIC_DIRECT to "anthropic-key"), // OPENAI_DIRECT unset
        )
        assertEquals("fell through to anthropic", result.text)
        // Exactly 1 real request: the unconfigured OPENAI_DIRECT step never hit the network.
        assertEquals(1, transport.requestedUrls.size)
        assertTrue(transport.requestedUrls[0].contains("anthropic.com"))
    }

    @Test fun `success advances the cursor so the next call starts past earlier dead steps`() {
        val cursor = CleanupWaterfallCursor()
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val firstCallTransport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.ConnectionFailure(null), okOutcome("first call result"))
        )
        val first = execute(waterfall, firstCallTransport, cursor)
        assertEquals("first call result", first.text)

        // Second call: only the previously-successful step (index 1) should be attempted.
        val secondCallTransport = FakeCleanupHttpTransport(mutableListOf(okOutcome("second call result")))
        val second = execute(waterfall, secondCallTransport, cursor)
        assertEquals("second call result", second.text)
        assertEquals(1, secondCallTransport.requestedUrls.size)
    }

    @Test fun `all steps failing reports an error instead of throwing`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("dead"),
                CleanupHttpOutcome.HttpError(500, ""),
                CleanupHttpOutcome.HttpError(500, ""),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertTrue(result.error != null)
    }

    @Test fun `the Anthropic step hits the native messages endpoint with an x-api-key header shape`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.Ok("""{"content":[{"type":"text","text":"anthropic result"}]}"""))
        )
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5")))
        val result = execute(waterfall, transport)
        assertEquals("anthropic result", result.text)
        assertEquals(AnthropicCleanupProvider.ENDPOINT_URL, transport.requestedUrls.single())
    }

    /** Per ADR-0001, every non-2xx status is an immediate, non-retried step failure -- no
     *  provider-specific status-code handling. These confirm the Anthropic step participates in
     *  that shared classification like every other provider: 401/429/5xx all just fall through
     *  to the next step, and a connection-level failure is classified separately. */
    @Test fun `a 401 from the Anthropic step falls through to the next step, no retry`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a 429 from the Anthropic step with Retry-After over 3s falls through immediately`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(429, """{"type":"error","error":{"type":"rate_limit_error","message":"rate limited"}}""", retryAfterMs = 10_000L),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `stage 2 cloud cleanup tries up to 3 providers sequentially and stops on first valid result`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(401, """{"error":{"message":"bad key"}}"""),
                CleanupHttpOutcome.HttpError(403, """{"error":{"message":"forbidden"}}"""),
                okOutcome("cleaned by provider 3"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m1"),
                CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "m2"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m3"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "m4"),
            )
        )
        val result = execute(
            waterfall,
            transport,
            credentials = mapOf(
                CleanupCredentialSlot.OPENAI_DIRECT to "k1",
                CleanupCredentialSlot.GEMINI_DIRECT to "k2",
                CleanupCredentialSlot.ANTHROPIC_DIRECT to "k3",
            ),
        )
        assertEquals("cleaned by provider 3", result.text)
        assertEquals(3, transport.requestedUrls.size)
    }

    @Test fun `stage 2 cloud cleanup caps at 3 cloud providers and falls through to Local Cleanup`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(401, """{"error":{"message":"bad key 1"}}"""),
                CleanupHttpOutcome.HttpError(401, """{"error":{"message":"bad key 2"}}"""),
                CleanupHttpOutcome.HttpError(401, """{"error":{"message":"bad key 3"}}"""),
            )
        )
        val local = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m1"),
                CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "m2"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "m3"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m4-skipped"),
                CleanupStep(CleanupStepGroup.LOCAL_LLM, "local"),
            )
        )
        val result = execute(
            waterfall,
            transport,
            credentials = mapOf(
                CleanupCredentialSlot.OPENAI_DIRECT to "k1",
                CleanupCredentialSlot.GEMINI_DIRECT to "k2",
                CleanupCredentialSlot.ANTHROPIC_DIRECT to "k3",
            ),
            localInference = local,
            localModelPath = { "/model.gguf" },
        )
        assertEquals("cleaned locally", result.text)
        assertEquals(3, transport.requestedUrls.size)
        assertEquals(1, local.calls.size)
    }

    @Test fun `a 5xx from the Anthropic step falls through to the next step without retrying when fallback exists`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(529, """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}"""),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test fun `a network timeout on the Anthropic step is classified as a connection failure and falls through`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.ConnectionFailure("timeout"),
                okOutcome("cleaned via fallback"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )
        val result = execute(waterfall, transport)
        assertEquals("cleaned via fallback", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }
}

/** LOCAL_LLM step-sequencing behavior (#37) -- exercises the executor's fakeable seam over
 *  native inference ([LocalInferenceEngine]), never real llama.cpp/JNI code. See
 *  app/src/main/cpp/llama_cleanup/README.md for why real on-device inference can't be exercised
 *  in a JVM unit test; only this seam's plumbing (routing, credential bypass, fallthrough) is
 *  covered here. */
class LocalLlmWaterfallStepTest {
    private val cancelHolder = InFlightCall()

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
        localInference: LocalInferenceEngine,
        localModelPath: () -> String?,
        credentialLookup: (CleanupCredentialSlot) -> String = { "" },
        localPrompt: String = PostProcessor.SIMPLE_PROMPT,
        localVocabulary: List<String> = emptyList(),
        text: String = "raw transcript",
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = text,
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = cancelHolder,
            credentialLookup = credentialLookup,
            transport = transport,
            localInference = localInference,
            localModelPath = localModelPath,
            localPrompt = localPrompt,
            localVocabulary = localVocabulary,
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `a local step succeeds with no credential and no legacy api key configured`() {
        val transport = FakeCleanupHttpTransport(mutableListOf()) // never touched
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" })

        assertEquals("cleaned locally", result.text)
        assertEquals(0, transport.requestedUrls.size) // no network call at all
        assertEquals(1, engine.calls.size)
    }

    @Test fun `local step uses the dedicated local prompt, not the cloud persona prompt`() {
        // #54 follow-up: today's small on-device models (e.g. LFM2.5-350M) don't reliably follow
        // the longer, few-shot DEV_PROMPT-style cloud persona prompts -- they can drift into
        // answering/chatting instead of restyling. The executor must always route LOCAL_LLM
        // through its own localPrompt (default PostProcessor.SIMPLE_PROMPT), independent of
        // whatever persona prompt the caller passes as [prompt] for cloud steps.
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        execute(
            waterfall, transport, engine,
            localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" },
            localPrompt = "a distinct local-only prompt",
        )

        assertEquals(1, engine.calls.size)
        assertEquals("a distinct local-only prompt", engine.calls[0].first) // systemPrompt arg
    }

    @Test fun `local step defaults to SIMPLE_PROMPT when no localPrompt is supplied`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        execute(waterfall, transport, engine, localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" })

        assertEquals(1, engine.calls.size)
        assertEquals(PostProcessor.SIMPLE_PROMPT, engine.calls[0].first)
    }

    // --- #182 option 2: vocabulary post-pass over accepted local output ------------------------

    @Test fun `accepted local output goes through the vocabulary post-pass`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("Deployed it on hetzler last night.")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" },
            localVocabulary = listOf("Hetzner"),
        )

        assertEquals("Deployed it on Hetzner last night.", result.text)
        // The terms must reach the model's OUTPUT, never its prompt (#182: prompt interpolation
        // is what made LFM2.5 echo the term list).
        assertEquals(PostProcessor.SIMPLE_PROMPT, engine.calls[0].first)
    }

    @Test fun `the vocabulary post-pass defaults to a no-op when no terms are passed`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("Deployed it on hetzler last night.")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { "/data/data/app/files/cleanup_models/model.gguf" })

        assertEquals("Deployed it on hetzler last night.", result.text)
    }

    @Test fun `cloud output is not run through the vocabulary post-pass`() {
        // Cloud cleanup gets vocabulary via prompt interpolation on [prompt] -- applying the
        // post-pass there too would double-apply the feature. A cloud result containing a
        // near-miss must come back verbatim even with localVocabulary configured.
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("Deployed it on hetzler last night.")))
        val engine = FakeLocalInferenceEngine(mutableListOf())
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OMNIROUTE, "some-model")))

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { null },
            credentialLookup = { "a-key" },
            localVocabulary = listOf("Hetzner"),
        )

        assertEquals("Deployed it on hetzler last night.", result.text)
    }

    @Test fun `a cancelled local inference stops the waterfall instead of falling through`() {
        // Like a cancelled HTTP call (#63), a cancel during local inference must not advance to
        // the next (possibly paid) step (#83).
        val transport = FakeCleanupHttpTransport(mutableListOf()) // must never be touched
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Cancelled))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        assertNull(result.text)
        assertEquals("Cleanup cancelled", result.error)
        assertEquals(0, transport.requestedUrls.size)
    }

    @Test fun `a timed-out local inference falls through to the next step, unlike a real cancel`() {
        // Regression (#98 live bug, Trevor's device): BoundedBlockingCall returning null on a
        // plain LOCAL_LLM_STEP_BUDGET_MS timeout was previously mapped to the same
        // LocalInferenceResult.Cancelled value a real user/watchdog cancel produces, which
        // aborted the ENTIRE waterfall instead of advancing -- so switching to a cloud-only
        // chain still injected raw text because a slow local model's timeout looked identical
        // to a cancel. A timeout must behave like any other step failure and fall through.
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned by openai")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.TimedOut("Local cleanup timed out")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { "openai-key" },
        )
        assertEquals("cleaned by openai", result.text)
        assertNull(result.error)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test fun `a single LOCAL_LLM step -- the last step in the waterfall -- gets its independent local budget`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("ok")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val before = System.currentTimeMillis()
        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })
        val after = System.currentTimeMillis()

        val deadline = engine.deadlines.single()
        assertTrue(deadline >= before + LOCAL_CHUNKED_TOTAL_BUDGET_MS)
        assertTrue(deadline <= after + LOCAL_CHUNKED_TOTAL_BUDGET_MS)
    }

    @Test fun `a LOCAL_LLM step followed by another step gets the tighter step budget, not the full waterfall deadline`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via cloud")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("ok")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )

        val before = System.currentTimeMillis()
        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })
        val after = System.currentTimeMillis()

        val deadline = engine.deadlines.single()
        assertTrue(deadline >= before + LOCAL_LLM_STEP_BUDGET_MS)
        assertTrue(deadline <= after + LOCAL_LLM_STEP_BUDGET_MS)
        assertTrue(deadline <= after + CLOUD_CLEANUP_STAGE_BUDGET_MS)
    }

    @Test fun `a failed local step still falls through to a following cloud step within the waterfall deadline`() {
        // #98: the whole point of LOCAL_LLM_STEP_BUDGET_MS being tighter than
        // CLEANUP_WATERFALL_HARD_CAP_MS is that a fast-failing (or fast-aborted) local attempt
        // leaves real time for a subsequent cloud step to actually be attempted, instead of the
        // local step silently consuming the entire waterfall budget.
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via cloud")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Failure("local model produced an empty response")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { slot -> if (slot == CleanupCredentialSlot.OMNIROUTE) "omni-key" else "" },
        )

        assertEquals("cleaned via cloud", result.text)
        assertEquals(1, transport.requestedUrls.size) // the cloud step really was attempted
    }

    @Test fun `each waterfall run clears a stale cancel from the previous dictation`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned locally")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        cancelHolder.cancel() // left over from a previous dictation
        val result = execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        assertEquals("cleaned locally", result.text)
    }

    @Test fun `local output is trimmed before it becomes a Success, matching the cloud parsers`() {
        // Small local models routinely emit a leading space/newline as the first sampled piece;
        // both cloud parsers trim, so untrimmed local output was a visible behavior difference
        // whenever the waterfall switched between local and cloud (#84).
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("\n cleaned locally ")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        assertEquals("cleaned locally", result.text)
    }

    @Test fun `a whitespace-only local success falls through as a step failure instead of injecting blanks`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via fallback")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("   \n ")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { slot -> if (slot == CleanupCredentialSlot.OPENAI_DIRECT) "openai-key" else "" },
        )

        assertEquals("cleaned via fallback", result.text)
    }

    @Test fun `the local step uses localPrompt (not the waterfall's cloud prompt) as its system message, with the waterfall text as user`() {
        // Superseded by the #54 follow-up fix above: LOCAL_LLM must NOT receive the cloud
        // persona's `prompt` verbatim -- see "local step uses the dedicated local prompt" and
        // "local step defaults to SIMPLE_PROMPT" for the actual current contract.
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("cleaned")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        execute(waterfall, transport, engine, localModelPath = { "/path/to/model.gguf" })

        val (systemPrompt, userText, modelPath) = engine.calls.single()
        assertEquals(PostProcessor.SIMPLE_PROMPT, systemPrompt) // default localPrompt, not the `prompt` arg
        assertEquals("raw transcript", userText) // the `text` arg passed to execute()
        assertEquals("/path/to/model.gguf", modelPath)
    }

    @Test fun `a local step fails without ever calling the engine when the model isn't downloaded`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf())
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val result = execute(waterfall, transport, engine, localModelPath = { null })

        // Single-step waterfall: the executor's generic "All cleanup steps failed" message
        // (see CleanupWaterfallExecutor.attempt) subsumes this step's specific failure reason --
        // same behavior the existing missing-credential test works around with a 2-step
        // waterfall. What matters here is that it fails without ever touching the engine.
        assertNull(result.text)
        assertTrue(result.error != null)
        assertEquals(0, engine.calls.size)
        assertEquals(0, transport.requestedUrls.size)
    }

    @Test fun `a local inference failure falls through to the next configured step`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via fallback")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Failure("context size reached")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            )
        )

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { slot -> if (slot == CleanupCredentialSlot.OPENAI_DIRECT) "openai-key" else "" },
        )

        assertEquals("cleaned via fallback", result.text)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test fun `local step normalizes before inference and accepts punctuation-only phone formatting`() {
        val transport = FakeCleanupHttpTransport(mutableListOf())
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("Call (212) 555-3476.")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive)))

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            text = "call two one two five five five three four seven six",
        )

        assertEquals("call 2125553476", engine.calls.single().second)
        assertEquals("Call (212) 555-3476.", result.text)
    }

    @Test fun `numeric verification failure rejects local output and cloud still receives raw text`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned safely by cloud")))
        val engine = FakeLocalInferenceEngine(mutableListOf(LocalInferenceResult.Success("The round was $1,200 dollars.")))
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.LOCAL_LLM, LOCAL_CLEANUP_MODEL.archive),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
            ),
        )
        val raw = "the round was one point two million dollars"

        val result = execute(
            waterfall, transport, engine,
            localModelPath = { "/path/to/model.gguf" },
            credentialLookup = { "openai-key" },
            text = raw,
        )

        assertEquals("the round was 1200000 dollars", engine.calls.single().second)
        assertEquals("cleaned safely by cloud", result.text)
        assertTrue(transport.requestedBodies.single().contains(raw))
        assertTrue(!transport.requestedBodies.single().contains("1200000"))
    }
}

/** Pure unit tests for [localLlmStepDeadline] (#37 follow-up) -- the exact bug Trevor hit live:
 *  a single-step Local-only cleanup chain got the tight LOCAL_LLM_STEP_BUDGET_MS budget meant to
 *  protect a subsequent step that didn't exist, causing llama_decode to abort mid-generation on
 *  a genuinely healthy completion. */
class LocalLlmStepDeadlineTest {
    @Test fun `not the last step gets the tighter budget, capped at the overall deadline`() {
        val now = 1_000_000L
        val overallDeadline = now + CLOUD_CLEANUP_STAGE_BUDGET_MS
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = false)
        assertEquals(now + LOCAL_LLM_STEP_BUDGET_MS, deadline)
    }

    @Test fun `the last step gets its independent 240s local budget from now`() {
        val now = 1_000_000L
        val overallDeadline = now + CLOUD_CLEANUP_STAGE_BUDGET_MS
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = true)
        assertEquals(now + LOCAL_CHUNKED_TOTAL_BUDGET_MS, deadline)
        assertEquals(60_000L, RealLocalInferenceEngine.PREPARE_TIMEOUT_MS)
        assertTrue("terminal step must not be held to the tighter budget", deadline > now + LOCAL_LLM_STEP_BUDGET_MS)
    }

    @Test fun `not the last step never exceeds the overall deadline even if the tighter budget would`() {
        val now = 1_000_000L
        val overallDeadline = now + 1_000L
        val deadline = localLlmStepDeadline(overallDeadline, now, isLastStep = false)
        assertEquals(overallDeadline, deadline)
    }

    @Test fun `terminal local cleanup starts at 0ms consumed even after cloud cleanup spends its entire 120s budget`() {
        val cloudStartedAtMs = 1_000_000L
        val cloudDeadline = cloudStartedAtMs + CLOUD_CLEANUP_STAGE_BUDGET_MS
        val nowAfterCloudExhausted = cloudStartedAtMs + CLOUD_CLEANUP_STAGE_BUDGET_MS + 5_000L

        val deadline = localLlmStepDeadline(cloudDeadline, nowAfterCloudExhausted, isLastStep = true)
        assertEquals(nowAfterCloudExhausted + LOCAL_CHUNKED_TOTAL_BUDGET_MS, deadline)
        assertEquals(LOCAL_CHUNKED_TOTAL_BUDGET_MS, deadline - nowAfterCloudExhausted)
    }

    @Test fun `cloud steps are clamped to the 40s per-provider cap and 120s stage budget`() {
        val start = 1_000_000L
        val deadline = start + CLOUD_CLEANUP_STAGE_BUDGET_MS

        val fresh = cloudStepTimeouts(deadline, start)
        assertEquals(CLOUD_CLEANUP_PER_PROVIDER_CAP_MS, fresh.callMs)

        val late = cloudStepTimeouts(deadline, deadline - 1_200L)
        assertEquals(1_200L, late.callMs)
        assertEquals(1_200L, late.connectMs)
        assertEquals(1_200L, late.readMs)

        assertEquals(MIN_STEP_CALL_BUDGET_MS, cloudStepTimeouts(deadline, deadline + 5_000L).callMs)

        for (spentMs in listOf(0L, 1L, 40_000L, 80_000L, 119_750L)) {
            val nowMs = start + spentMs
            val t = cloudStepTimeouts(deadline, nowMs)
            assertTrue("cloud step at +${spentMs}ms must not outlive stage deadline", nowMs + t.callMs!! <= deadline)
            assertTrue("cloud step must not exceed 40s per-provider cap", t.callMs!! <= CLOUD_CLEANUP_PER_PROVIDER_CAP_MS)
        }
    }
}

/** #63: a cancelled in-flight call must stop the waterfall outright — previously it reported
 *  through the same onFailure path as a dead host, so a user's cancel *advanced* the waterfall
 *  into the next (possibly paid) group for a result the guard token would discard anyway. */
class CleanupWaterfallExecutorCancellationTest {

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "some-key" },
            transport = transport,
            localInference = LocalInferenceEngine { _, _, _, _, _ -> error("no local step expected") },
            localModelPath = { null },
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `a cancelled call stops the waterfall without attempting the next group`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.Cancelled,
                okOutcome("must never be requested"),
            )
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "claude-haiku-4-5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertEquals("Cleanup cancelled", result.error)
        // Exactly 1 request: the cancelled step. The paid direct-provider steps were never called.
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test fun `a cancelled call stops the waterfall even within the same group`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.Cancelled, okOutcome("sibling must not run"))
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OMNIROUTE, "claude/claude-sonnet-4-6"),
                CleanupStep(CleanupStepGroup.OMNIROUTE, "cx/gpt-5.5"),
            )
        )
        val result = execute(waterfall, transport)
        assertNull(result.text)
        assertEquals(1, transport.requestedUrls.size)
    }
}

/**
 * #274: [CleanupWaterfallExecutor.execute]'s entry-based credential lookup, keyed by
 * [CleanupStep.entryId] -- the actual fix for #273 at the cleanup-execution layer. Two
 * OPENAI_DIRECT steps sharing [CleanupCredentialSlot.OPENAI_DIRECT] must still resolve to
 * different credentials when they carry different [CleanupStep.entryId]s.
 */
class CleanupWaterfallExecutorEntryCredentialTest {
    private val cancelHolder = InFlightCall()

    private fun execute(
        waterfall: CleanupWaterfall,
        transport: CleanupHttpTransport,
        entryCredentialLookup: (String) -> String,
        credentialLookup: (CleanupCredentialSlot) -> String = { "" },
    ): PostProcessor.Result {
        var captured: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean it up",
            waterfall = waterfall,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = cancelHolder,
            credentialLookup = credentialLookup,
            entryCredentialLookup = entryCredentialLookup,
            transport = transport,
            callback = { captured = it },
        )
        return captured ?: error("callback never fired")
    }

    @Test fun `two same-kind OPENAI_DIRECT steps with different entryIds resolve to different credentials`() {
        val credentials = mapOf("groq-id" to "sk-groq-key", "openrouter-id" to "sk-openrouter-key")
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m", entryId = "groq-id")))

        val result = execute(waterfall, transport, entryCredentialLookup = { credentials[it] ?: "" })

        assertEquals("cleaned", result.text)
        assertTrue(transport.requestedBodies.isNotEmpty())
    }

    @Test fun `entryCredentialLookup is preferred over the slot-based credentialLookup when both resolve`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m", entryId = "entry-x")))

        // If the executor used the slot-based key it would still succeed (both are configured),
        // so this alone can't prove preference -- the missing-credential test below does that by
        // making the two sources disagree on which one HAS a key at all.
        val result = execute(
            waterfall, transport,
            entryCredentialLookup = { if (it == "entry-x") "sk-entry-key" else "" },
            credentialLookup = { "sk-slot-key" },
        )
        assertEquals("cleaned", result.text)
    }

    @Test fun `a step with an entryId configured only in entryCredentialLookup still succeeds even when the slot lookup has nothing`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m", entryId = "entry-x")))

        val result = execute(
            waterfall, transport,
            entryCredentialLookup = { if (it == "entry-x") "sk-entry-key" else "" },
            credentialLookup = { "" }, // slot-based lookup has nothing configured
        )

        assertEquals("cleaned", result.text)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test fun `a step with a blank entryId falls back to the slot-based credentialLookup unchanged`() {
        // Every pre-#274 call site/test fixture never sets entryId -- this is the "zero behavior
        // change" guarantee for that population.
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via slot")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m"))) // entryId defaults to ""

        val result = execute(
            waterfall, transport,
            entryCredentialLookup = { "should never be reached for a blank entryId" },
            credentialLookup = { "sk-slot-key" },
        )

        assertEquals("cleaned via slot", result.text)
    }

    @Test fun `an entryId with no entry-based credential falls through to the slot-based one`() {
        val transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("cleaned via slot fallback")))
        val waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m", entryId = "unmigrated-entry")))

        val result = execute(
            waterfall, transport,
            entryCredentialLookup = { "" }, // migration hasn't copied a key onto this entry yet
            credentialLookup = { "sk-legacy-slot-key" },
        )

        assertEquals("cleaned via slot fallback", result.text)
    }

    @Test fun `two same-kind steps with only one entry's credential configured -- only that one succeeds`() {
        val transport = FakeCleanupHttpTransport(
            mutableListOf(CleanupHttpOutcome.HttpError(401, """{"error":{"message":"invalid key"}}"""), okOutcome("second entry succeeds"))
        )
        val waterfall = CleanupWaterfall(
            listOf(
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m", entryId = "unconfigured-entry"),
                CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m2", entryId = "configured-entry"),
            )
        )

        val result = execute(
            waterfall, transport,
            entryCredentialLookup = { entryId -> if (entryId == "configured-entry") "sk-configured" else "" },
            credentialLookup = { "sk-fallback-shared" }, // both steps WOULD succeed if this were used for both
        )

        // The first step still gets a credential (the shared fallback), sends a real (failing)
        // request, and falls through to the second step, which resolves its own distinct key.
        assertEquals("second entry succeeds", result.text)
        assertEquals(2, transport.requestedUrls.size)
    }

    // ---- Phase 2D: callTimeout vs cancel, onStageStatus, onStepFailure --------------------------

    @Test fun `RealCleanupHttpTransport treats callTimeout without user cancel as ConnectionFailure and advances to local fallback`() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .body("""{"choices":[{"message":{"content":"too late"}}]}""")
                    .headersDelay(900, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
            val holder = InFlightCall().apply { beginWork() }
            val outcomeLatch = java.util.concurrent.CountDownLatch(1)
            val capturedOutcome = java.util.concurrent.atomic.AtomicReference<CleanupHttpOutcome>()
            RealCleanupHttpTransport.send(
                url = server.url("/v1/chat/completions").toString(),
                headers = mapOf("Authorization" to "Bearer key"),
                jsonBody = "{}",
                timeouts = CleanupStepTimeouts(connectMs = 250L, readMs = 250L, callMs = 250L),
                cancelHolder = holder,
            ) {
                capturedOutcome.set(it)
                outcomeLatch.countDown()
            }
            assertTrue(outcomeLatch.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val outcome = capturedOutcome.get()
            assertTrue("callTimeout must produce ConnectionFailure, got $outcome", outcome is CleanupHttpOutcome.ConnectionFailure)

            // End-to-end failover through CleanupWaterfallExecutor to LOCAL_LLM instead of "Cleanup cancelled":
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .body("""{"choices":[{"message":{"content":"too late"}}]}""")
                    .headersDelay(900, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
            val statuses = mutableListOf<String>()
            val wf = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini", baseUrlOverride = server.url("/v1").toString()),
                    CleanupStep(CleanupStepGroup.LOCAL_LLM, "lfm"),
                )
            )
            val wfLatch = java.util.concurrent.CountDownLatch(1)
            val wfResult = java.util.concurrent.atomic.AtomicReference<PostProcessor.Result>()
            var fakeNow = 0L
            CleanupWaterfallExecutor.execute(
                text = "raw transcript",
                prompt = "clean it up",
                waterfall = wf,
                cursor = CleanupWaterfallCursor(),
                cancelHolder = InFlightCall(),
                credentialLookup = { "key" },
                localInference = LocalInferenceEngine { _, _, _, _, _ -> LocalInferenceResult.Success("cleaned locally") },
                localModelPath = { "/fake/model.gguf" },
                // Leave only ~250ms on the stage deadline so callTimeout fires quickly in test.
                nowMs = { if (fakeNow == 0L) { fakeNow = 1_000L; 1_000L } else 1_000L + cloudCleanupStageBudgetMs("raw transcript".length) - 250L },
                onStageStatus = { statuses += it },
                callback = { wfResult.set(it); wfLatch.countDown() },
            )
            assertTrue(wfLatch.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("cleaned locally", wfResult.get().text)
            assertNull(wfResult.get().error)
            assertTrue(statuses.contains("Cloud unavailable — trying on-device…"))
        } finally {
            server.close()
        }
    }

    @Test fun `RealCleanupHttpTransport honors explicit cancelHolder cancellation as Cancelled`() {
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .body("""{"choices":[{"message":{"content":"never"}}]}""")
                    .headersDelay(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
            val holder = InFlightCall().apply { beginWork() }
            val latch = java.util.concurrent.CountDownLatch(1)
            val captured = java.util.concurrent.atomic.AtomicReference<CleanupHttpOutcome>()
            RealCleanupHttpTransport.send(
                url = server.url("/v1/chat/completions").toString(),
                headers = mapOf("Authorization" to "Bearer key"),
                jsonBody = "{}",
                timeouts = CleanupStepTimeouts(connectMs = 5_000L, readMs = 5_000L, callMs = 5_000L),
                cancelHolder = holder,
            ) {
                captured.set(it)
                latch.countDown()
            }
            server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            holder.cancel()
            assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(captured.get() is CleanupHttpOutcome.Cancelled)
        } finally {
            server.close()
        }
    }

    @Test fun `onStageStatus is silent on 1st attempt happy path and emits on cleanup retry, provider failover, and local fallback`() {
        // 1. Happy path: zero status emissions
        val happyStatuses = mutableListOf<String>()
        var happyResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = FakeCleanupHttpTransport(mutableListOf(okOutcome("clean"))),
            onStageStatus = { happyStatuses += it },
            callback = { happyResult = it },
        )
        assertEquals("clean", happyResult?.text)
        assertTrue("happy path must never emit stage status", happyStatuses.isEmpty())

        // 2. Multi-step: 503 skips busy retry -> provider failover -> local fallback + onStepFailure captures raw body
        val failoverStatuses = mutableListOf<String>()
        val stepFailures = mutableListOf<Triple<CleanupStepGroup, Int?, String>>()
        var failoverResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"),
                    CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "gemini-3.5-flash-lite"),
                    CleanupStep(CleanupStepGroup.LOCAL_LLM, "lfm"),
                )
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = FakeCleanupHttpTransport(
                mutableListOf(
                    CleanupHttpOutcome.HttpError(503, """{"error":{"message":"overloaded once"}}"""),
                    CleanupHttpOutcome.HttpError(400, """{"error":{"message":"bad model payload"}}"""),
                )
            ),
            localInference = LocalInferenceEngine { _, _, _, _, _ -> LocalInferenceResult.Success("local clean") },
            localModelPath = { "/fake/lfm.gguf" },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, a -> a() },
            onStageStatus = { failoverStatuses += it },
            onStepFailure = { step, code, detail -> stepFailures += Triple(step.group, code, detail) },
            callback = { failoverResult = it },
        )
        assertEquals("local clean", failoverResult?.text)
        assertEquals(
            listOf(
                "OpenAI failed — trying Gemini…",
                "Cloud unavailable — trying on-device…",
            ),
            failoverStatuses,
        )
        assertEquals(2, stepFailures.size)
        assertEquals(CleanupStepGroup.OPENAI_DIRECT, stepFailures[0].first)
        assertEquals(503, stepFailures[0].second)
        assertTrue(stepFailures[0].third.contains("overloaded once"))
        assertEquals(CleanupStepGroup.GEMINI_DIRECT, stepFailures[1].first)
        assertEquals(400, stepFailures[1].second)
        assertTrue(stepFailures[1].third.contains("bad model payload"))

        // 3. Single-step (no runnable fallback): 503 emits Cleanup retrying… and retries once
        val retryStatuses = mutableListOf<String>()
        var singleStepResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-4o-mini"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = FakeCleanupHttpTransport(
                mutableListOf(
                    CleanupHttpOutcome.HttpError(503, """{"error":{"message":"busy once"}}"""),
                    okOutcome("recovered"),
                )
            ),
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, a -> a() },
            onStageStatus = { retryStatuses += it },
            callback = { singleStepResult = it },
        )
        assertEquals("recovered", singleStepResult?.text)
        assertEquals(listOf("Cleanup retrying…"), retryStatuses)
    }

    @Test fun `HTTP 200 blank content returns diagnostic without leaking reasoning and clears prior retry HTTP code`() {
        val failures = mutableListOf<Pair<Int?, String>>()
        var result: PostProcessor.Result? = null
        val secretThought = "SENSITIVE_REASONING_SHOULD_NEVER_LOG"
        val transport = FakeCleanupHttpTransport(
            mutableListOf(
                CleanupHttpOutcome.HttpError(
                    400,
                    """{"error":{"type":"unsupported_value","code":"unsupported_value","param":"temperature","message":"temperature is unsupported"}}""",
                ),
                CleanupHttpOutcome.Ok(
                    """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"","reasoning":"$secretThought"}}]}""",
                ),
            ),
        )
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "reasoning-model"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = transport,
            onStepFailure = { _, httpCode, detail -> failures += httpCode to detail },
            callback = { result = it },
        )
        assertNull(result?.text)
        val err = result?.error.orEmpty()
        assertTrue(
            "expected empty content diagnostic, got '$err'",
            err.contains("Empty content in response (finish_reason=length, reasoning=true, refusal=false)"),
        )
        assertFalse(err.contains(secretThought))
        val (recordedCode, recordedDetail) = failures.single()
        assertNull("prior 400 retry must not mislabel subsequent HTTP 200 parse failure", recordedCode)
        assertTrue(recordedDetail.contains("Empty content in response (finish_reason=length, reasoning=true, refusal=false)"))
        assertFalse(recordedDetail.contains(secretThought))
    }

    @Test fun `RealLocalInferenceEngine LENGTH_COLLAPSE detail propagates through executor and preserves notice mapping`() {
        val rawInput = "Please remind everyone that the team meeting tomorrow morning will cover the quarterly schedule"
        val truncatedOutput = "Team meeting."
        val localResult = RealLocalInferenceEngine.validated(
            PostProcessor.SIMPLE_PROMPT,
            rawInput,
            truncatedOutput,
        ) as LocalInferenceResult.Failure
        assertTrue(
            "expected LENGTH_COLLAPSE rejection, got '${localResult.message}'",
            localResult.message.startsWith("Local cleanup output rejected (LENGTH_COLLAPSE): "),
        )
        assertTrue(localResult.message.contains("norm, "))
        assertTrue(localResult.message.contains("raw; minimum 50%)"))

        var wfResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = rawInput,
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.LOCAL_LLM, "local-model"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "" },
            localInference = LocalInferenceEngine { _, _, _, _, _ -> localResult },
            localModelPath = { "/fake/model.gguf" },
            callback = { wfResult = it },
        )
        assertNull(wfResult?.text)
        val wfErr = wfResult?.error.orEmpty()
        assertTrue(wfErr.contains("Local cleanup output rejected (LENGTH_COLLAPSE): "))
        assertTrue(wfErr.contains("norm, "))
        assertEquals("model dropped most of the text", CleanupFailureNotice.summarize(wfResult?.error))
    }

    @Test fun `503 busy retry is skipped when a runnable fallback remains and allowed when none remains`() {
        // 1. Two cloud steps both with keys: 503 on step 0 skips retry and fails over immediately.
        var step0Calls = 0
        var step1Calls = 0
        var multiCloudResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m1"),
                    CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "m2"),
                )
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { url, _, _, _, _, cb ->
                if (url.contains("openai")) {
                    step0Calls++
                    cb(CleanupHttpOutcome.HttpError(503, "busy"))
                } else {
                    step1Calls++
                    cb(CleanupHttpOutcome.Ok("""{"candidates":[{"content":{"parts":[{"text":"gemini ok"}]}}]}"""))
                }
            },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, a -> a() },
            callback = { multiCloudResult = it },
        )
        assertEquals(1, step0Calls)
        assertEquals(1, step1Calls)
        assertEquals("gemini ok", multiCloudResult?.text)

        // 2. One cloud step + available LOCAL_LLM: 503 skips retry and fails over to local.
        var cloudPlusLocalCalls = 0
        var localFallbackResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m1"),
                    CleanupStep(CleanupStepGroup.LOCAL_LLM, "local"),
                )
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { _, _, _, _, _, cb ->
                cloudPlusLocalCalls++
                cb(CleanupHttpOutcome.HttpError(503, "busy"))
            },
            localInference = LocalInferenceEngine { _, _, _, _, _ -> LocalInferenceResult.Success("local ok") },
            localModelPath = { "/fake/model.gguf" },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, a -> a() },
            callback = { localFallbackResult = it },
        )
        assertEquals(1, cloudPlusLocalCalls)
        assertEquals("local ok", localFallbackResult?.text)

        // 3. One cloud step + unconfigured cloud step + unavailable local: step 0 HAS NO runnable fallback, so it retries 503.
        var unrunnableFallbackCalls = 0
        var retriedResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m1", entryId = "e1"),
                    CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "m2", entryId = "e2"),
                    CleanupStep(CleanupStepGroup.LOCAL_LLM, "local"),
                )
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "" },
            entryCredentialLookup = { id -> if (id == "e1") "key-1" else "" },
            transport = CleanupHttpTransport { _, _, _, _, _, cb ->
                unrunnableFallbackCalls++
                if (unrunnableFallbackCalls == 1) cb(CleanupHttpOutcome.HttpError(503, "busy"))
                else cb(okOutcome("retried ok"))
            },
            localModelPath = { null },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, a -> a() },
            callback = { retriedResult = it },
        )
        assertEquals(2, unrunnableFallbackCalls)
        assertEquals("retried ok", retriedResult?.text)

        // 4. 3rd cloud step with a 4th cloud step beyond MAX_CLOUD_CLEANUP_PROVIDERS (3) and no local: retries 503.
        val callsByModel = mutableListOf<String>()
        var capBoundaryResult: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw",
            prompt = "clean",
            waterfall = CleanupWaterfall(
                listOf(
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m1"),
                    CleanupStep(CleanupStepGroup.GEMINI_DIRECT, "m2"),
                    CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, "m3"),
                    CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "m4-beyond-cap"),
                )
            ),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { url, _, body, _, _, cb ->
                callsByModel += url
                when {
                    url.contains("openai") -> cb(CleanupHttpOutcome.HttpError(401, "bad openai"))
                    url.contains("googleapis") -> cb(CleanupHttpOutcome.HttpError(401, "bad gemini"))
                    callsByModel.count { it.contains("anthropic") } == 1 -> cb(CleanupHttpOutcome.HttpError(503, "busy"))
                    else -> cb(CleanupHttpOutcome.Ok("""{"content":[{"type":"text","text":"m3 recovered"}]}"""))
                }
            },
            busyRetryDelayMs = 0L,
            scheduleRetry = { _, a -> a() },
            callback = { capBoundaryResult = it },
        )
        assertEquals("m3 recovered", capBoundaryResult?.text)
        assertEquals(2, callsByModel.count { it.contains("anthropic") })
    }

    @Test fun `distinct-host OPENAI_DIRECT steps stay in separate fail-fast groups while same-host steps stay merged`() {
        val cloudOpenAi = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini", entryId = "e1")
        val lanSelfHosted = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "qwen", baseUrlOverride = "http://192.168.1.50:8000/v1", entryId = "e2")
        val lanTrailingSlash = CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "llama", baseUrlOverride = "http://192.168.1.50:8000/v1/", entryId = "e3")

        val groups = CleanupWaterfallPlanner.groupConsecutive(listOf(cloudOpenAi, lanSelfHosted, lanTrailingSlash))
        assertEquals(2, groups.size)
        assertEquals(listOf(cloudOpenAi), groups[0])
        assertEquals(listOf(lanSelfHosted, lanTrailingSlash), groups[1])

        // Runtime execution: DNS/connection failure on cloud OPENAI_DIRECT does NOT skip LAN OPENAI_DIRECT behind it.
        val requestedUrls = mutableListOf<String>()
        var result: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(cloudOpenAi, lanSelfHosted)),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { url, _, _, _, _, cb ->
                requestedUrls += url
                if (url.contains("api.openai.com")) cb(CleanupHttpOutcome.ConnectionFailure("Unable to resolve host"))
                else cb(okOutcome("lan rescued"))
            },
            callback = { result = it },
        )
        assertEquals(listOf("https://api.openai.com/v1/chat/completions", "http://192.168.1.50:8000/v1/chat/completions"), requestedUrls)
        assertEquals("lan rescued", result?.text)
    }

    @Test fun `stage 2 cloud cleanup caps scale with input length`() {
        assertEquals(20_000L, cloudCleanupPerProviderCapMs(0))
        assertEquals(20_000L, cloudCleanupPerProviderCapMs(2_000))
        assertEquals(29_000L, cloudCleanupPerProviderCapMs(5_000))
        assertEquals(40_000L, cloudCleanupPerProviderCapMs(9_000))
        assertEquals(40_000L, cloudCleanupPerProviderCapMs(20_000))

        assertEquals(60_000L, cloudCleanupStageBudgetMs(500))
        assertEquals(60_000L, cloudCleanupStageBudgetMs(2_000))
        assertEquals(87_000L, cloudCleanupStageBudgetMs(5_000))
        assertEquals(90_000L, cloudCleanupStageBudgetMs(9_000))
        assertEquals(90_000L, cloudCleanupStageBudgetMs(20_000))

        val shortTimeouts = mutableListOf<CleanupStepTimeouts>()
        CleanupWaterfallExecutor.execute(
            text = "short transcript",
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { _, _, _, timeouts, _, cb ->
                shortTimeouts += timeouts
                cb(okOutcome("ok"))
            },
            nowMs = { 1_000L },
            callback = {},
        )
        assertEquals(20_000L, shortTimeouts.single().callMs)

        val longTimeouts = mutableListOf<CleanupStepTimeouts>()
        CleanupWaterfallExecutor.execute(
            text = "a".repeat(9_000),
            prompt = "clean",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-5.4-mini"))),
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "key" },
            transport = CleanupHttpTransport { _, _, _, timeouts, _, cb ->
                longTimeouts += timeouts
                cb(okOutcome("ok"))
            },
            nowMs = { 1_000L },
            callback = {},
        )
        assertEquals(40_000L, longTimeouts.single().callMs)
    }
}
