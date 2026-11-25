package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptBudgetCalculatorTest {

    private fun slot(id: String, label: String, locked: Boolean, attempts: Int = 1) =
        AttemptBudgetCalculator.BudgetSlot(id, label, locked, attempts)

    @Test fun `single active slot supports 1x, 2x, 3x and blocks second lock at 3x`() {
        val oneAt3x = listOf(
            slot("s1", "Gemini", locked = true, attempts = 3),
            slot("s2", "Groq", locked = false, attempts = 1),
        )
        assertEquals(3, AttemptBudgetCalculator.usedAttempts(oneAt3x))
        assertEquals(0, AttemptBudgetCalculator.remainingBudget(oneAt3x))
        assertEquals(listOf(1, 2, 3), AttemptBudgetCalculator.allowedAttemptsForSlot(oneAt3x, "s1"))
        assertEquals(emptyList<Int>(), AttemptBudgetCalculator.allowedAttemptsForSlot(oneAt3x, "s2"))

        val lockSecond = AttemptBudgetCalculator.canLockSlot(oneAt3x, "s2", requestedAttempts = 1)
        assertTrue(lockSecond is AttemptBudgetCalculator.LockResult.Blocked)
        assertFalse((lockSecond as AttemptBudgetCalculator.LockResult.Blocked).reason.contains("retr", ignoreCase = true))
    }

    @Test fun `two active slots enforce 2+1, 1+2, and 1+1 combinations`() {
        val twoSlots = listOf(
            slot("s1", "Gemini", locked = true, attempts = 2),
            slot("s2", "Groq", locked = true, attempts = 1),
            slot("s3", "OpenAI", locked = false, attempts = 1),
        )
        assertEquals(3, AttemptBudgetCalculator.usedAttempts(twoSlots))
        assertEquals(0, AttemptBudgetCalculator.remainingBudget(twoSlots))
        assertEquals(listOf(1, 2), AttemptBudgetCalculator.allowedAttemptsForSlot(twoSlots, "s1"))
        assertEquals(listOf(1), AttemptBudgetCalculator.allowedAttemptsForSlot(twoSlots, "s2"))
        assertTrue(AttemptBudgetCalculator.validatePool(twoSlots).isValid)

        val onePlusOne = listOf(
            slot("s1", "Gemini", locked = true, attempts = 1),
            slot("s2", "Groq", locked = true, attempts = 1),
        )
        assertEquals(2, AttemptBudgetCalculator.usedAttempts(onePlusOne))
        assertEquals(1, AttemptBudgetCalculator.remainingBudget(onePlusOne))
        assertEquals(listOf(1, 2), AttemptBudgetCalculator.allowedAttemptsForSlot(onePlusOne, "s1"))
        assertEquals(listOf(1, 2), AttemptBudgetCalculator.allowedAttemptsForSlot(onePlusOne, "s2"))

        val lockThird = AttemptBudgetCalculator.canLockSlot(onePlusOne, "s3", requestedAttempts = 2)
        assertEquals(AttemptBudgetCalculator.LockResult.Allowed(assignedAttempts = 1, wasClamped = true), lockThird)
    }

    @Test fun `three active slots lock at 1x+1x+1x and block a fourth`() {
        val threeSlots = listOf(
            slot("s1", "Gemini", locked = true, attempts = 1),
            slot("s2", "Groq", locked = true, attempts = 1),
            slot("s3", "OpenAI", locked = true, attempts = 1),
            slot("s4", "OpenRouter", locked = false, attempts = 1),
        )
        assertEquals(3, AttemptBudgetCalculator.usedAttempts(threeSlots))
        assertEquals(0, AttemptBudgetCalculator.remainingBudget(threeSlots))
        assertEquals(listOf(1), AttemptBudgetCalculator.allowedAttemptsForSlot(threeSlots, "s1"))
        assertTrue(AttemptBudgetCalculator.canLockSlot(threeSlots, "s4", 1) is AttemptBudgetCalculator.LockResult.Blocked)
    }

    @Test fun `validatePool rejects sum over 3 or out-of-range attempts`() {
        val overPool = listOf(
            slot("s1", "Gemini", locked = true, attempts = 2),
            slot("s2", "Groq", locked = true, attempts = 2),
        )
        val validation = AttemptBudgetCalculator.validatePool(overPool)
        assertFalse(validation.isValid)
        assertEquals(4, validation.usedAttempts)
        assertEquals(0, validation.remainingAttempts)

        val zeroAttempts = listOf(slot("s1", "Gemini", locked = true, attempts = 0))
        assertFalse(AttemptBudgetCalculator.validatePool(zeroAttempts).isValid)
    }

    @Test fun `normalizeOnUpgrade parks overflow cards 4 through 10 unlocked without deleting them`() {
        val slots = (1..10).map { i -> slot("c$i", "Provider $i", locked = true, attempts = 1) }
        val normalized = AttemptBudgetCalculator.normalizeOnUpgrade(slots)
        assertEquals(10, normalized.size)
        assertEquals(3, normalized.count { it.locked })
        assertTrue(normalized.take(3).all { it.locked && it.attempts == 1 })
        assertTrue(normalized.drop(3).all { !it.locked && it.attempts == 1 })
        assertTrue(AttemptBudgetCalculator.validatePool(normalized).isValid)
    }

    @Test fun `normalizeOnUpgrade clamps 2x+2x to 2x+1x`() {
        val slots = listOf(
            slot("s1", "Gemini", locked = true, attempts = 2),
            slot("s2", "Groq", locked = true, attempts = 2),
            slot("s3", "OpenAI", locked = true, attempts = 1),
        )
        val normalized = AttemptBudgetCalculator.normalizeOnUpgrade(slots)
        assertEquals(listOf(true, true, false), normalized.map { it.locked })
        assertEquals(listOf(2, 1, 1), normalized.map { it.attempts })
    }

    @Test fun `formatExecutionOrderStrip renders arrows and fallback tail`() {
        val slots = listOf(
            slot("s1", "Gemini", locked = true, attempts = 1),
            slot("s2", "Groq", locked = true, attempts = 2),
            slot("s3", "OpenAI", locked = false, attempts = 1),
        )
        assertEquals(
            "Gemini (1x) \u2192 Groq (2x) \u2192 On-device",
            AttemptBudgetCalculator.formatExecutionOrderStrip(slots, fallbackTail = "On-device"),
        )
        assertEquals(
            "On-device",
            AttemptBudgetCalculator.formatExecutionOrderStrip(emptyList(), fallbackTail = "On-device"),
        )
    }
}
