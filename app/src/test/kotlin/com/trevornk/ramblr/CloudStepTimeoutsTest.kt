package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #137: the waterfall's documented hard cap was only enforced *between* steps, so a step
 * dispatched just under the cap still got its full per-phase connect+read allowance and could
 * overrun the cap by ~8.5s. These pin the clamp that makes the cap actually hold.
 */
class CloudStepTimeoutsTest {

    private val start = 1_000_000L
    private val deadline = start + CLOUD_CLEANUP_STAGE_BUDGET_MS

    @Test
    fun `a step starting with the full budget keeps the normal per-phase timeouts and 40s per-provider cap`() {
        val t = cloudStepTimeouts(deadline, start)

        assertEquals(CleanupStepTimeouts.DEFAULT.connectMs, t.connectMs)
        assertEquals(CleanupStepTimeouts.DEFAULT.readMs, t.readMs)
        assertEquals(CLOUD_CLEANUP_PER_PROVIDER_CAP_MS, t.callMs)
    }

    @Test
    fun `the whole-call bound never exceeds the budget the waterfall has left`() {
        val remainingMs = 2_900L
        val nowMs = deadline - remainingMs
        val t = cloudStepTimeouts(deadline, nowMs)

        assertEquals(remainingMs, t.callMs)
        assertTrue(
            "call budget must not outlive the waterfall deadline",
            nowMs + t.callMs!! <= deadline,
        )
    }

    @Test
    fun `phase timeouts are clamped down too so neither phase can outlive the deadline`() {
        val t = cloudStepTimeouts(deadline, deadline - 1_200L)

        assertEquals(1_200L, t.connectMs)
        assertEquals(1_200L, t.readMs)
        assertEquals(1_200L, t.callMs)
    }

    @Test
    fun `a spent budget floors instead of collapsing to zero`() {
        val t = cloudStepTimeouts(deadline, deadline + 5_000L)

        assertEquals(MIN_STEP_CALL_BUDGET_MS, t.callMs)
        assertTrue("a spent budget must never yield an infinite timeout", t.callMs!! > 0L)
    }

    @Test
    fun `the worst case is bounded by the cap rather than cap plus a full step allowance`() {
        val latestLegalStart = deadline - 1L
        val t = cloudStepTimeouts(deadline, latestLegalStart)

        val oldWorstCase = 1L + CleanupStepTimeouts.DEFAULT.connectMs + CleanupStepTimeouts.DEFAULT.readMs
        assertNotNull(t.callMs)
        assertTrue(
            "clamped budget must be far below the old connect+read worst case",
            t.callMs!! < oldWorstCase,
        )
        assertEquals(MIN_STEP_CALL_BUDGET_MS, t.callMs)
    }

    @Test
    fun `an explicit base is respected but still clamped`() {
        val base = CleanupStepTimeouts(connectMs = 500L, readMs = 900L)
        val roomy = cloudStepTimeouts(deadline, start, base)

        // A tighter-than-default base is preserved rather than widened back to the default.
        assertEquals(500L, roomy.connectMs)
        assertEquals(900L, roomy.readMs)

        val tight = cloudStepTimeouts(deadline, deadline - 300L, base)
        assertEquals(300L, tight.connectMs)
        assertEquals(300L, tight.readMs)
        assertEquals(300L, tight.callMs)
    }

    @Test
    fun `default timeouts carry no call bound so unaware callers are unaffected`() {
        // Test fakes and any caller that doesn't know the deadline must keep the old behavior
        // (leave the shared client's own callTimeout alone) rather than get a surprise bound.
        assertEquals(null, CleanupStepTimeouts.DEFAULT.callMs)
    }
}
