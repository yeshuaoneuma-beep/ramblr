package com.trevornk.ramblr

/**
 * Pure shared 3-attempt pool math used identically across:
 *  - Batch STT (cloud provider cards)
 *  - Batch Cleanup (cloud provider cards)
 *  - Live Post-Cleanup (up to 3 slots)
 *
 * Always uses the terminology "attempts" (`1x`, `2x`, `3x`) where `1x` unambiguously means 1 total
 * call to that slot. Active locked cloud slots in a single lane must satisfy `sum(attempts) <= 3`.
 */
object AttemptBudgetCalculator {
    const val MAX_CLOUD_ATTEMPTS_PER_LANE = 3
    const val MIN_SLOT_ATTEMPTS = 1
    const val MAX_SLOT_ATTEMPTS = 3
    const val MAX_SAVED_CARDS = 10
    const val MAX_LIVE_POST_CLEANUP_SLOTS = 3

    data class BudgetSlot(
        val id: String,
        val label: String,
        val locked: Boolean,
        val attempts: Int = MIN_SLOT_ATTEMPTS,
    )

    data class PoolValidation(
        val isValid: Boolean,
        val usedAttempts: Int,
        val remainingAttempts: Int,
        val reason: String? = null,
    )

    sealed class LockResult {
        data class Allowed(
            val assignedAttempts: Int,
            val wasClamped: Boolean,
        ) : LockResult()

        data class Blocked(
            val reason: String,
        ) : LockResult()
    }

    /** Total cloud attempts currently allocated across all locked slots in [slots]. */
    fun usedAttempts(slots: List<BudgetSlot>): Int =
        slots.filter { it.locked }.sumOf { it.attempts.coerceIn(MIN_SLOT_ATTEMPTS, MAX_SLOT_ATTEMPTS) }

    /** Remaining cloud attempts available in the 3-attempt pool (0..3). */
    fun remainingBudget(slots: List<BudgetSlot>): Int =
        (MAX_CLOUD_ATTEMPTS_PER_LANE - usedAttempts(slots)).coerceAtLeast(0)

    /**
     * Returns the valid attempt counts (`1`, `2`, `3`) selectable for [slotId] given the other
     * currently locked slots in [slots]. Returns empty if 3/3 attempts are already locked elsewhere.
     */
    fun allowedAttemptsForSlot(slots: List<BudgetSlot>, slotId: String): List<Int> {
        val usedByOthers = slots
            .filter { it.locked && it.id != slotId }
            .sumOf { it.attempts.coerceIn(MIN_SLOT_ATTEMPTS, MAX_SLOT_ATTEMPTS) }
        val maxAllowed = (MAX_CLOUD_ATTEMPTS_PER_LANE - usedByOthers).coerceIn(0, MAX_SLOT_ATTEMPTS)
        if (maxAllowed < MIN_SLOT_ATTEMPTS) return emptyList()
        return (MIN_SLOT_ATTEMPTS..maxAllowed).toList()
    }

    /**
     * Checks whether [slotId] can be locked with [requestedAttempts]. If remaining pool budget is
     * at least 1 but less than [requestedAttempts], clamps down to the available budget and sets
     * [LockResult.Allowed.wasClamped] = true. If 0 attempts remain, returns [LockResult.Blocked].
     */
    fun canLockSlot(
        slots: List<BudgetSlot>,
        slotId: String,
        requestedAttempts: Int = MIN_SLOT_ATTEMPTS,
    ): LockResult {
        val usedByOthers = slots
            .filter { it.locked && it.id != slotId }
            .sumOf { it.attempts.coerceIn(MIN_SLOT_ATTEMPTS, MAX_SLOT_ATTEMPTS) }
        val available = MAX_CLOUD_ATTEMPTS_PER_LANE - usedByOthers
        if (available < MIN_SLOT_ATTEMPTS) {
            return LockResult.Blocked(
                "3/$MAX_CLOUD_ATTEMPTS_PER_LANE attempts are already locked. Reduce attempts or unlock another provider first."
            )
        }
        val normalizedRequest = requestedAttempts.coerceIn(MIN_SLOT_ATTEMPTS, MAX_SLOT_ATTEMPTS)
        val assigned = minOf(normalizedRequest, available)
        return LockResult.Allowed(
            assignedAttempts = assigned,
            wasClamped = assigned != normalizedRequest,
        )
    }

    /** Validates the entire pool invariant (`sum(locked.attempts) <= 3`, each `1..3`). */
    fun validatePool(slots: List<BudgetSlot>): PoolValidation {
        val locked = slots.filter { it.locked }
        val invalidSlot = locked.firstOrNull { it.attempts !in MIN_SLOT_ATTEMPTS..MAX_SLOT_ATTEMPTS }
        if (invalidSlot != null) {
            val used = locked.sumOf { it.attempts.coerceAtLeast(0) }
            return PoolValidation(
                isValid = false,
                usedAttempts = used,
                remainingAttempts = (MAX_CLOUD_ATTEMPTS_PER_LANE - used).coerceAtLeast(0),
                reason = "${invalidSlot.label} attempts (${invalidSlot.attempts}x) must be between ${MIN_SLOT_ATTEMPTS}x and ${MAX_SLOT_ATTEMPTS}x.",
            )
        }
        val used = locked.sumOf { it.attempts }
        if (used > MAX_CLOUD_ATTEMPTS_PER_LANE) {
            return PoolValidation(
                isValid = false,
                usedAttempts = used,
                remainingAttempts = 0,
                reason = "Locked cloud attempts ($used/$MAX_CLOUD_ATTEMPTS_PER_LANE) exceed the $MAX_CLOUD_ATTEMPTS_PER_LANE-attempt pool.",
            )
        }
        return PoolValidation(
            isValid = true,
            usedAttempts = used,
            remainingAttempts = MAX_CLOUD_ATTEMPTS_PER_LANE - used,
            reason = null,
        )
    }

    /**
     * Normalizes an upgraded or imported list of [slots] to satisfy `sum <= 3` without deleting
     * any saved cards. Leading locked slots consume available attempts in priority order; once 3
     * attempts are allocated, any remaining slots (#4..#10) are parked unlocked (`locked = false`,
     * `attempts = 1`).
     */
    fun normalizeOnUpgrade(slots: List<BudgetSlot>): List<BudgetSlot> {
        var used = 0
        return slots.map { slot ->
            if (!slot.locked) {
                slot.copy(attempts = slot.attempts.coerceIn(MIN_SLOT_ATTEMPTS, MAX_SLOT_ATTEMPTS))
            } else {
                val remaining = MAX_CLOUD_ATTEMPTS_PER_LANE - used
                if (remaining < MIN_SLOT_ATTEMPTS) {
                    slot.copy(locked = false, attempts = MIN_SLOT_ATTEMPTS)
                } else {
                    val assigned = slot.attempts.coerceIn(MIN_SLOT_ATTEMPTS, remaining)
                    used += assigned
                    slot.copy(locked = true, attempts = assigned)
                }
            }
        }
    }

    /**
     * Formats the live execution order strip shown on Cloud Providers and Live Transcription,
     * e.g. `"Gemini (1x) \u2192 Groq (2x) \u2192 On-device"`.
     */
    fun formatExecutionOrderStrip(
        slots: List<BudgetSlot>,
        fallbackTail: String? = null,
        emptyLabel: String = "None active",
    ): String {
        val activeParts = slots
            .filter { it.locked }
            .map { "${it.label} (${it.attempts.coerceIn(MIN_SLOT_ATTEMPTS, MAX_SLOT_ATTEMPTS)}x)" }
        if (activeParts.isEmpty()) {
            return fallbackTail?.takeIf { it.isNotBlank() } ?: emptyLabel
        }
        val allParts = if (!fallbackTail.isNullOrBlank()) activeParts + fallbackTail else activeParts
        return allParts.joinToString(" \u2192 ")
    }
}
