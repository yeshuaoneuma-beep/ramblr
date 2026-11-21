package com.trevornk.ramblr

/**
 * Pure branch decisions for the transcription provider fall-through walk (#H1). The service's
 * `transcribeApi` is callback- and IO-bound and can't be unit-tested directly, so the two
 * decisions that actually govern fall-through live here where a plain JVM test can pin them:
 *
 *  - [precheck]: before any network/inference call, is this candidate usable at all, or should the
 *    walk skip straight to the next one (a cloud provider with no configured credential, a LOCAL
 *    entry whose model hasn't loaded yet, or a not-yet-implemented transcription provider)?
 *  - [hasNextCandidate]: once a candidate has failed (unusable, HTTP error, timeout, or an empty
 *    transcript), is there another candidate to try, or is the chain exhausted?
 *
 * Before #H1 the service only advanced on a blank credential; a real failure from candidate N
 * reset the whole dictation even when candidate N+1 (Gemini, or the on-device LOCAL floor) was
 * configured and healthy. These functions make "failure on candidate N tries candidate N+1" the
 * pinned, tested contract.
 */
object TranscriptionChain {
    enum class Precheck {
        /** Candidate is usable -- make the transcription call. */
        CALL,
        /** Candidate can't run right now -- advance to the next candidate without a call. */
        SKIP,
    }

    fun precheck(kind: ProviderKind, hasCredential: Boolean, localModelLoaded: Boolean): Precheck =
        when (kind) {
            ProviderKind.OPENAI, ProviderKind.GEMINI -> if (hasCredential) Precheck.CALL else Precheck.SKIP
            ProviderKind.LOCAL -> if (localModelLoaded) Precheck.CALL else Precheck.SKIP
            // Not implemented for transcription; capability filtering should have removed these,
            // so this is defensive only -- always skip.
            ProviderKind.ANTHROPIC, ProviderKind.OMNIROUTE -> Precheck.SKIP
        }

    /** True when a candidate after [index] exists in a chain of [candidateCount] candidates. */
    fun hasNextCandidate(index: Int, candidateCount: Int): Boolean = index + 1 < candidateCount

    /**
     * Whether the PCM recording may be deleted after candidate [candidateIndex]'s outcome (M5
     * audit, 2026-08-26). The PCM is the *only* copy of the user's audio: once it's gone, no
     * later candidate can retry, so it must stay alive for exactly as long as the walk might
     * still need it --
     *
     *  - success: the transcript exists, nothing downstream needs audio -- delete.
     *  - failure with a candidate remaining: the next candidate needs the audio -- keep.
     *  - failure on the last candidate: the chain is exhausted, the dictation is over -- delete.
     *
     * Before M5, `transcribeLocal` deleted the PCM in its `finally` unconditionally, so a LOCAL
     * candidate that failed post-load took the audio down with it and a configured cloud
     * candidate after it in the chain had nothing left to transcribe -- the dictation died with
     * "Local error: ..." instead of falling through. This is the pinned contract that prevents
     * that class of bug for every candidate kind.
     */
    fun shouldDeletePcm(candidateIndex: Int, candidateCount: Int, success: Boolean): Boolean =
        success || !hasNextCandidate(candidateIndex, candidateCount)

    const val CLOUD_STT_STAGE_BUDGET_MS = 180_000L
    const val LOCAL_STT_BUDGET_MS = 180_000L
    const val CLOUD_STT_ATTEMPT_CAP_COMPRESSED_MS = 60_000L
    const val CLOUD_STT_ATTEMPT_CAP_WAV_MS = 75_000L
    const val CLOUD_STT_RETRY_BACKOFF_MS = 1_000L
    const val MAX_ATTEMPTS_PER_PROVIDER = 2
    const val SINGLE_CLOUD_MAX_ATTEMPTS = 2

    /**
     * Returns the per-attempt timeout in milliseconds clamped to [deadlineAtMs], or null when the
     * shared Stage 1 cloud STT budget is already spent.
     */
    fun cloudAttemptTimeoutMs(
        deadlineAtMs: Long,
        nowMs: Long,
        isCompressedUpload: Boolean,
    ): Long? {
        val remainingMs = deadlineAtMs - nowMs
        if (remainingMs <= 0L) return null
        val capMs = if (isCompressedUpload) {
            CLOUD_STT_ATTEMPT_CAP_COMPRESSED_MS
        } else {
            CLOUD_STT_ATTEMPT_CAP_WAV_MS
        }
        return minOf(capMs, remainingMs)
    }

    sealed class Step {
        data class RunCloud(
            val entry: ProviderChainEntry,
            val attemptNumber: Int,
            val isRevisit: Boolean,
            val backoffMs: Long,
            val timeoutMs: Long,
        ) : Step()

        data class RunLocal(
            val entry: ProviderChainEntry,
            val budgetMs: Long = LOCAL_STT_BUDGET_MS,
        ) : Step()

        data class Exhausted(val lastError: String?) : Step()
    }

    /**
     * Deterministic Stage 1 state machine:
     *  - 3+ cloud providers: P1 -> P2 -> P3 (-> ...) -> Local -> Error (no P1 revisit).
     *  - 2 cloud providers: P1 -> P2 -> revisit P1 once (if P1 failed TEMPORARY_BUSY and has < 2 attempts) -> Local -> Error.
     *  - 1 cloud provider: P1 -> P1 (TEMPORARY_BUSY or quick-429 only, max 2 attempts) -> Local -> Error.
     *  - Every provider is hard-capped at [MAX_ATTEMPTS_PER_PROVIDER] (2) total attempts within the
     *    stage, so a quick HTTP 429 retry on P1 consumes P1's revisit slot (never 3 calls).
     */
    class Walk(
        private val cloudCandidates: List<ProviderChainEntry>,
        private val localCandidate: ProviderChainEntry?,
        startedAtMs: Long,
        private val isCompressedUpload: Boolean,
        cloudBudgetMs: Long = CLOUD_STT_STAGE_BUDGET_MS,
        private val backoffMs: Long = CLOUD_STT_RETRY_BACKOFF_MS,
        private val singleCloudMaxAttempts: Int = SINGLE_CLOUD_MAX_ATTEMPTS,
        private val localBudgetMs: Long = LOCAL_STT_BUDGET_MS,
    ) {
        val cloudDeadlineAtMs: Long = startedAtMs + cloudBudgetMs

        private val attemptsByIndex = IntArray(cloudCandidates.size)
        private var cloudIndex = 0
        private var pendingQuickRetryIndex: Int? = null
        private var pendingQuickRetryBackoffMs: Long = 0L
        private var p1FailedTemporaryBusy = false
        private var p1Revisited = false
        private var cloudExhausted = cloudCandidates.isEmpty()
        private var localAttempted = false
        private var pendingStep: Step? = null
        private var pendingCloudIndex: Int = -1
        private var lastError: String? = null

        fun nextStep(nowMs: Long): Step {
            if (!cloudExhausted) {
                val step = nextCloudStep(nowMs)
                if (step != null) {
                    pendingStep = step
                    return step
                }
                cloudExhausted = true
            }
            if (localCandidate != null && !localAttempted) {
                localAttempted = true
                return Step.RunLocal(localCandidate, localBudgetMs).also { pendingStep = it }
            }
            return Step.Exhausted(lastError)
        }

        private fun nextCloudStep(nowMs: Long): Step.RunCloud? {
            val quickIdx = pendingQuickRetryIndex
            if (quickIdx != null) {
                pendingQuickRetryIndex = null
                val maxForProvider = if (cloudCandidates.size == 1) {
                    minOf(singleCloudMaxAttempts, MAX_ATTEMPTS_PER_PROVIDER)
                } else {
                    MAX_ATTEMPTS_PER_PROVIDER
                }
                if (quickIdx in attemptsByIndex.indices && attemptsByIndex[quickIdx] < maxForProvider) {
                    val delayMs = pendingQuickRetryBackoffMs
                    val timeoutMs = cloudAttemptTimeoutMs(cloudDeadlineAtMs, nowMs + delayMs, isCompressedUpload)
                    if (timeoutMs != null) {
                        pendingCloudIndex = quickIdx
                        return Step.RunCloud(
                            entry = cloudCandidates[quickIdx],
                            attemptNumber = attemptsByIndex[quickIdx] + 1,
                            isRevisit = false,
                            backoffMs = delayMs,
                            timeoutMs = timeoutMs,
                        )
                    }
                    // Not enough stage budget left to wait for the quick retry backoff.
                    if (cloudCandidates.size == 1) return null
                    cloudIndex = maxOf(cloudIndex, quickIdx + 1)
                }
            }

            if (cloudCandidates.size == 1) {
                if (attemptsByIndex[0] > 0) return null
                val timeoutMs = cloudAttemptTimeoutMs(cloudDeadlineAtMs, nowMs, isCompressedUpload)
                    ?: return null
                pendingCloudIndex = 0
                return Step.RunCloud(
                    entry = cloudCandidates[0],
                    attemptNumber = 1,
                    isRevisit = false,
                    backoffMs = 0L,
                    timeoutMs = timeoutMs,
                )
            }

            if (cloudIndex < cloudCandidates.size) {
                val idx = cloudIndex
                val timeoutMs = cloudAttemptTimeoutMs(cloudDeadlineAtMs, nowMs, isCompressedUpload) ?: return null
                pendingCloudIndex = idx
                return Step.RunCloud(
                    entry = cloudCandidates[idx],
                    attemptNumber = attemptsByIndex[idx] + 1,
                    isRevisit = false,
                    backoffMs = 0L,
                    timeoutMs = timeoutMs,
                )
            }

            if (
                cloudCandidates.size == 2 &&
                p1FailedTemporaryBusy &&
                !p1Revisited &&
                attemptsByIndex[0] < MAX_ATTEMPTS_PER_PROVIDER
            ) {
                val timeoutMs = cloudAttemptTimeoutMs(cloudDeadlineAtMs, nowMs + backoffMs, isCompressedUpload)
                    ?: return null
                pendingCloudIndex = 0
                return Step.RunCloud(
                    entry = cloudCandidates[0],
                    attemptNumber = attemptsByIndex[0] + 1,
                    isRevisit = true,
                    backoffMs = backoffMs,
                    timeoutMs = timeoutMs,
                )
            }

            return null
        }

        fun recordCloudFailure(
            errorKind: CloudErrorKind,
            errorMessage: String?,
            httpCode: Int? = null,
            retryAfterMs: Long? = null,
        ) {
            val step = pendingStep as? Step.RunCloud ?: return
            val idx = pendingCloudIndex
            pendingStep = null
            pendingCloudIndex = -1
            if (!errorMessage.isNullOrBlank()) lastError = errorMessage
            if (idx !in attemptsByIndex.indices) return
            attemptsByIndex[idx]++

            val quick429 = CloudErrorClassifier.isQuickRetryable429(httpCode, retryAfterMs)

            if (cloudCandidates.size == 1) {
                val maxAttempts = minOf(singleCloudMaxAttempts, MAX_ATTEMPTS_PER_PROVIDER)
                if (attemptsByIndex[0] >= maxAttempts) {
                    cloudExhausted = true
                    return
                }
                when {
                    quick429 -> {
                        pendingQuickRetryIndex = 0
                        pendingQuickRetryBackoffMs = CloudErrorClassifier.quick429BackoffMs(retryAfterMs)
                    }
                    errorKind == CloudErrorKind.TEMPORARY_BUSY -> {
                        pendingQuickRetryIndex = 0
                        pendingQuickRetryBackoffMs = backoffMs
                    }
                    else -> cloudExhausted = true
                }
                return
            }

            if (!step.isRevisit) {
                val canAttemptAgain = attemptsByIndex[idx] < MAX_ATTEMPTS_PER_PROVIDER
                if (quick429 && canAttemptAgain) {
                    // Quick 429 retry stays on this provider and consumes its 2nd attempt / revisit slot.
                    pendingQuickRetryIndex = idx
                    pendingQuickRetryBackoffMs = CloudErrorClassifier.quick429BackoffMs(retryAfterMs)
                    return
                }
                if (idx == 0 && errorKind == CloudErrorKind.TEMPORARY_BUSY && canAttemptAgain) {
                    p1FailedTemporaryBusy = true
                }
                cloudIndex = idx + 1
            } else {
                p1Revisited = true
                cloudExhausted = true
            }
        }

        fun recordLocalFailure(errorMessage: String?) {
            pendingStep = null
            pendingCloudIndex = -1
            if (!errorMessage.isNullOrBlank()) lastError = errorMessage
        }
    }
}
