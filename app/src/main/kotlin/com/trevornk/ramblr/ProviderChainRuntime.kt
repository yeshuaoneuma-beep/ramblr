package com.trevornk.ramblr

/**
 * Runtime adapter from Phase 1's unified [ProviderChain] model into the legacy cleanup executor
 * shape. The executor continues to own fail-fast grouping, cursor resume, and local-deadline
 * behavior; provider capability handling remains inside its existing HTTP transport path.
 */
object ProviderChainRuntime {
    /** Transcription provider-chain kinds modeled as capable but not implemented by a live HTTP
     *  client yet. Empty as of #96 (Gemini's transcription transport is wired up, see
     *  [GeminiTranscriberClient]). Kept as an explicit set (rather than deleted outright) so a
     *  future provider kind added to [ProviderKind] ahead of its transport has the same "skip
     *  explicitly, don't crash or misroute" escape hatch this used for Gemini during Phase 1/2 --
     *  see its use in [transcriptionCandidates]. */
    val transcriptionKindsNotImplemented: Set<ProviderKind> = emptySet()

    /**
     * Converts cleanup-capable provider entries to the [CleanupWaterfall] shape expected by the
     * existing executor. Threads [ProviderChainEntry.id] onto each [CleanupStep] (#274) so the
     * executor's credential lookup resolves this entry's own per-entry credential rather than a
     * shared per-kind slot -- see [CleanupStep.entryId]'s kdoc.
     */
    fun cleanupWaterfallFor(chain: ProviderChain): CleanupWaterfall = CleanupWaterfall(
        chain.capableEntriesFor(needsTranscription = false).mapNotNull { entry ->
            val attempts = if (entry.kind == ProviderKind.LOCAL) {
                AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS
            } else {
                entry.cleanupAttempts.coerceIn(
                    AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
                    AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS,
                )
            }
            when (entry.kind) {
                ProviderKind.OPENAI -> CleanupStep(CleanupStepGroup.OPENAI_DIRECT, entry.model, entry.baseUrlOverride, entry.id, entry.presetId, attempts, entry.vaultKeyId)
                ProviderKind.ANTHROPIC -> CleanupStep(CleanupStepGroup.ANTHROPIC_DIRECT, entry.model, entry.baseUrlOverride, entry.id, entry.presetId, attempts, entry.vaultKeyId)
                ProviderKind.GEMINI -> CleanupStep(CleanupStepGroup.GEMINI_DIRECT, entry.model, entry.baseUrlOverride, entry.id, entry.presetId, attempts, entry.vaultKeyId)
                ProviderKind.OMNIROUTE -> CleanupStep(CleanupStepGroup.OMNIROUTE, entry.model, entry.baseUrlOverride, entry.id, entry.presetId, attempts, entry.vaultKeyId)
                ProviderKind.LOCAL -> CleanupStep(CleanupStepGroup.LOCAL_LLM, entry.model, entry.baseUrlOverride, entry.id, entry.presetId, attempts, null)
            }
        }
    )

    /**
     * Converts [LiveTranscriptionConfig.postCleanupSlots] into an executable [CleanupWaterfall]
     * for Phase 3A.2 Live Post-Cleanup. Returns an empty waterfall when post-cleanup is disabled
     * or no locked slots are configured.
     */
    fun livePostCleanupWaterfallFor(config: LiveTranscriptionConfig): CleanupWaterfall {
        if (!config.postCleanupEnabled) return CleanupWaterfall(emptyList())
        val activeSlots = config.postCleanupSlots
            .take(LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS)
            .filter { it.locked && it.modelId.isNotBlank() && it.modelId != ProviderChainEntry.SKIP_MODEL_ID }
        var remainingBudget = AttemptBudgetCalculator.MAX_CLOUD_ATTEMPTS_PER_LANE
        val steps = mutableListOf<CleanupStep>()
        for (slot in activeSlots) {
            if (remainingBudget <= 0) break
            val descriptor = ProviderCapabilityRegistry.forId(slot.providerId) ?: continue
            val group = when (descriptor.kind) {
                ProviderKind.OPENAI -> CleanupStepGroup.OPENAI_DIRECT
                ProviderKind.ANTHROPIC -> CleanupStepGroup.ANTHROPIC_DIRECT
                ProviderKind.GEMINI -> CleanupStepGroup.GEMINI_DIRECT
                ProviderKind.OMNIROUTE -> CleanupStepGroup.OMNIROUTE
                ProviderKind.LOCAL -> continue
            }
            val attempts = slot.attempts.coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, remainingBudget)
            remainingBudget -= attempts
            val baseUrl = slot.baseUrlOverride?.takeIf { it.isNotBlank() }
                ?: descriptor.defaultBaseUrl?.takeIf { descriptor.presetId != null || descriptor.requiresCustomBaseUrl }
            val vaultKeyId = slot.vaultKeyId?.takeIf { it.isNotBlank() }
                ?: ProviderCapabilityRegistry.canonicalVaultKeyId(slot.providerId)
            steps += CleanupStep(
                group = group,
                model = slot.modelId,
                baseUrlOverride = baseUrl,
                entryId = slot.id,
                presetId = descriptor.presetId,
                maxAttempts = attempts,
                vaultKeyId = vaultKeyId,
            )
        }
        return CleanupWaterfall(steps)
    }

    /** True when [chain] is the unified equivalent of the old single LEGACY OpenAI cleanup path. */
    fun isSingleOpenAiCleanup(chain: ProviderChain): Boolean {
        val cleanupEntries = chain.capableEntriesFor(needsTranscription = false)
        return cleanupEntries.size == 1 && cleanupEntries[0].kind == ProviderKind.OPENAI
    }

    /** True unless [chain] is exactly one OPENAI entry. Since #105 this no longer selects between
     *  execution paths -- every chain runs through [CleanupWaterfallExecutor] -- it only marks the
     *  single-credential chain that [WhisperAccessibilityService] pre-flights for a missing key,
     *  because it has no second step to fall through to. */
    fun shouldUseCleanupExecutor(chain: ProviderChain): Boolean = !isSingleOpenAiCleanup(chain)

    /** Maps executor credential slots to unified provider credential kinds. */
    fun providerKindForCleanupSlot(slot: CleanupCredentialSlot): ProviderKind = when (slot) {
        CleanupCredentialSlot.OMNIROUTE -> ProviderKind.OMNIROUTE
        CleanupCredentialSlot.OPENAI_DIRECT -> ProviderKind.OPENAI
        CleanupCredentialSlot.ANTHROPIC_DIRECT -> ProviderKind.ANTHROPIC
        CleanupCredentialSlot.GEMINI_DIRECT -> ProviderKind.GEMINI
    }

    /**
     * Ordered transcription candidates. When [allowLocalFallback] is true (the default,
     * preserving today's behavior), LOCAL is left in the result — and appended implicitly if
     * [localModelAvailable] is true and the chain has no explicit LOCAL entry yet — so cloud-mode
     * callers can fall through to the on-device floor. When false (#100: explicit "fall back to
     * on-device if cloud fails" toggle turned off), LOCAL is stripped from the cloud candidate list
     * entirely -- every cloud candidate failing means the caller reports an error instead of
     * silently retrying on-device.
     */
    fun transcriptionCandidates(
        chain: ProviderChain,
        allowLocalFallback: Boolean = true,
        localModelAvailable: Boolean = false,
    ): List<ProviderChainEntry> {
        val candidates = chain.capableEntriesFor(needsTranscription = true)
            .filterNot { it.kind in transcriptionKindsNotImplemented }
        if (!allowLocalFallback) return candidates.filterNot { it.kind == ProviderKind.LOCAL }
        return if (localModelAvailable && candidates.none { it.kind == ProviderKind.LOCAL }) {
            candidates + ProviderChainEntry(ProviderKind.LOCAL, "")
        } else {
            candidates
        }
    }

    /**
     * Applies the Phase 3 "Use cloud for Cleanup" toggle ([CloudFeatureToggle.cleanupEnabled|
     * setCleanupEnabled]) ahead of cleanup resolution: when [cloudEnabled] is false, every
     * non-LOCAL entry is dropped from the chain before it's handed to [cleanupWaterfallFor],
     * so cleanup falls back to on-device (via the chain's LOCAL entry, or an implicit LOCAL
     * floor when [localModelAvailable] is true and [chain] is non-empty) or is skipped entirely.
     *
     * When [cloudEnabled] is true (the default) and [allowLocalFallback] is true, an installed
     * local model ([localModelAvailable]) also ensures an implicit [ProviderKind.LOCAL] floor even
     * if the user hasn't manually tapped Local in [CleanupActivity]. When [allowLocalFallback] is
     * false, any LOCAL entry is stripped so failing cloud steps report an error/raw-inject
     * instead of silently completing on-device.
     */
    fun effectiveChainForCleanup(
        chain: ProviderChain,
        cloudEnabled: Boolean,
        allowLocalFallback: Boolean = true,
        localModelAvailable: Boolean = false,
        localModelArchive: String = "",
    ): ProviderChain = if (cloudEnabled) {
        if (!allowLocalFallback) {
            ProviderChain(chain.entries.filterNot { it.kind == ProviderKind.LOCAL })
        } else if (
            localModelAvailable &&
            chain.entries.isNotEmpty() &&
            chain.capableEntriesFor(needsTranscription = false).none { it.kind == ProviderKind.LOCAL }
        ) {
            ProviderChain(chain.entries + ProviderChainEntry(ProviderKind.LOCAL, localModelArchive))
        } else {
            chain
        }
    } else {
        val localEntries = chain.entries.filter { it.kind == ProviderKind.LOCAL }
        when {
            localEntries.isNotEmpty() -> ProviderChain(localEntries)
            localModelAvailable && chain.entries.isNotEmpty() ->
                ProviderChain(listOf(ProviderChainEntry(ProviderKind.LOCAL, localModelArchive)))
            else -> ProviderChain(emptyList())
        }
    }
}
