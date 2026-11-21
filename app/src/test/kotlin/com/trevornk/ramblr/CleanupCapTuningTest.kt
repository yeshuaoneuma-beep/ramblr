package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the waterfall hard cap to the real-usage evidence it was tuned against (#137 follow-up).
 *
 * The cap was lowered 15s -> 8s using 34 days of Trevor's own dictation (318 successful cleanups
 * in benchmark_log.jsonl). These aren't testing arithmetic for its own sake -- they encode the
 * observed latency distribution so that anyone retuning the cap has to consciously accept killing
 * real cleanups that used to succeed, rather than discovering it in production.
 */
class CleanupCapTuningTest {

    /** Slowest cleanup that actually SUCCEEDED in 34 days of real use (GEMINI_DIRECT, 08-08). */
    private val slowestObservedSuccessMs = 7_859L

    /** p99 of those 318 successful cleanups. */
    private val observedP99Ms = 3_828L

    @Test
    fun `stage 2 cloud cleanup uses 120s stage budget and 40s per-provider cap`() {
        assertEquals(120_000L, CLOUD_CLEANUP_STAGE_BUDGET_MS)
        assertEquals(40_000L, CLOUD_CLEANUP_PER_PROVIDER_CAP_MS)
        assertEquals(3, MAX_CLOUD_CLEANUP_PROVIDERS)
        assertEquals(CLOUD_CLEANUP_STAGE_BUDGET_MS, CLOUD_CLEANUP_PER_PROVIDER_CAP_MS * MAX_CLOUD_CLEANUP_PROVIDERS)
    }

    @Test
    fun `the per-provider cap admits the slowest cleanup ever observed to succeed`() {
        assertTrue(
            "Per-provider cap ${CLOUD_CLEANUP_PER_PROVIDER_CAP_MS}ms would abort the slowest real success " +
                "(${slowestObservedSuccessMs}ms).",
            CLOUD_CLEANUP_PER_PROVIDER_CAP_MS > slowestObservedSuccessMs,
        )
    }

    @Test
    fun `the read timeout fits inside the per-provider cap`() {
        assertTrue(
            "readMs (${CleanupStepTimeouts.DEFAULT.readMs}ms) exceeds per-provider cap ${CLOUD_CLEANUP_PER_PROVIDER_CAP_MS}ms.",
            CleanupStepTimeouts.DEFAULT.readMs <= CLOUD_CLEANUP_PER_PROVIDER_CAP_MS,
        )
    }

    @Test
    fun `a step dispatched at the p99 mark still gets a full per-provider budget`() {
        val start = 1_000_000L
        val deadline = start + CLOUD_CLEANUP_STAGE_BUDGET_MS
        val t = cloudStepTimeouts(deadline, start + observedP99Ms)

        assertEquals(CLOUD_CLEANUP_PER_PROVIDER_CAP_MS, t.callMs)
        assertTrue("Leftover budget can't even fit a median cleanup.", t.callMs!! > 901L)
    }
}
