package com.trevornk.ramblr

/**
 * Pure "effective routing" summary formatters for #276: a concise, read-only line naming what
 * will actually happen for a dictation right now -- e.g. `"Cloud (OpenAI) \u2192 on-device
 * fallback"` or `"On-device only"` -- computed from the same prefs/chain/toggles the resolver
 * itself reads ([ProviderChainRuntime], [DictationModeToggle], [CloudFeatureToggle]), so the row
 * can never claim a routing the runtime wouldn't actually take.
 *
 * Reuses the existing [CleanupDestination]/[hasConfiguredCloudTranscription] helpers other
 * subtitles already use (see [CloudProviderActivity.cloudTranscriptionSubtitle]) rather than
 * re-deriving "which provider, is it configured" -- read-only [ProviderChain] use throughout, no
 * mutation.
 */
object EffectiveRouting {

    /**
     * "Transcription: ..." body (caller prefixes the label), e.g.:
     *  - "Cloud (OpenAI) \u2192 on-device fallback" -- cloud active, configured, local fallback on
     *  - "Cloud (not configured) \u2192 on-device fallback" -- cloud active, no key set yet
     *  - "Cloud (OpenAI) only" -- cloud active, local fallback off (a cloud failure is an error)
     *  - "On-device \u2192 cloud fallback (OpenAI)" -- local active, cloud fallback on and configured
     *  - "On-device only" -- local active, cloud fallback off
     */
    fun transcription(
        chain: ProviderChain,
        useLocalTranscription: Boolean,
        allowLocalFallback: Boolean,
        allowCloudFallback: Boolean,
        isConfigured: (ProviderChainEntry) -> Boolean,
        localAvailable: Boolean = true,
    ): String {
        val cloudEntry = CleanupDestination.firstCloudTranscription(chain)
        val cloudConfigured = hasConfiguredCloudTranscription(chain, isConfigured)
        val cloudLabel = cloudEntry?.let { CleanupDestination.label(it.kind) }
        val cloudDescription = if (cloudConfigured && cloudLabel != null) "Cloud ($cloudLabel)" else "Cloud (not configured)"

        return if (useLocalTranscription) {
            if (allowCloudFallback) {
                "On-device \u2192 cloud fallback (${if (cloudConfigured && cloudLabel != null) cloudLabel else "not configured"})"
            } else {
                "On-device only"
            }
        } else {
            if (allowLocalFallback && localAvailable) "$cloudDescription \u2192 on-device fallback" else "$cloudDescription only"
        }
    }

    /**
     * "Cleanup: ..." body (caller prefixes the label). Cleanup has only one fallback direction
     * today ([ProviderChainRuntime.effectiveChainForCleanup]'s [allowLocalFallback] gate) -- a
     * local-only cleanup config never automatically retries in the cloud, so unlike
     * [transcription] there is no "on-device \u2192 cloud fallback" case here.
     */
    fun cleanup(
        chain: ProviderChain,
        postProcessingEnabled: Boolean,
        cloudCleanupEnabled: Boolean,
        allowLocalFallback: Boolean,
        isConfigured: (ProviderChainEntry) -> Boolean,
        localAvailable: Boolean = true,
    ): String {
        if (!postProcessingEnabled) return "Off"
        if (!cloudCleanupEnabled) return "On-device only"

        val cloudEntries = chain.capableEntriesFor(needsTranscription = false).filter { it.kind != ProviderKind.LOCAL }
        val cloudConfigured = cloudEntries.any { isConfigured(it) }
        val cloudLabel = cloudEntries.firstOrNull()?.let { CleanupDestination.label(it.kind) }
        val cloudDescription = if (cloudConfigured && cloudLabel != null) "Cloud ($cloudLabel)" else "Cloud (not configured)"

        return if (allowLocalFallback && localAvailable) "$cloudDescription \u2192 on-device fallback" else "$cloudDescription only"
    }

    /**
     * Phase 3A.1 execution-order strip for Batch STT using [AttemptBudgetCalculator], e.g.
     * `"Gemini (1x) \u2192 Groq (2x) \u2192 On-device"`.
     */
    fun batchSttExecutionOrder(
        chain: ProviderChain,
        allowLocalFallback: Boolean,
        localAvailable: Boolean,
        useLocalTranscription: Boolean = false,
    ): String {
        if (useLocalTranscription) return "On-device"
        val slots = chain.entries
            .filter { it.kind != ProviderKind.LOCAL }
            .mapIndexed { idx, entry ->
                AttemptBudgetCalculator.BudgetSlot(
                    id = entry.id.ifBlank { "stt_$idx" },
                    label = ProviderCapabilityRegistry.displayNameFor(entry),
                    locked = entry.isBatchSttActive(),
                    attempts = entry.sttAttempts,
                )
            }
        val tail = if (allowLocalFallback && localAvailable) "On-device" else null
        return AttemptBudgetCalculator.formatExecutionOrderStrip(
            slots = slots,
            fallbackTail = tail,
            emptyLabel = if (localAvailable) "On-device" else "No cloud STT locked",
        )
    }

    /**
     * Phase 3A.1 execution-order strip for Batch Cleanup using [AttemptBudgetCalculator].
     */
    fun batchCleanupExecutionOrder(
        chain: ProviderChain,
        postProcessingEnabled: Boolean,
        cloudCleanupEnabled: Boolean,
        allowLocalFallback: Boolean,
        localAvailable: Boolean,
    ): String {
        if (!postProcessingEnabled) return "Off"
        if (!cloudCleanupEnabled) return if (localAvailable) "On-device" else "On-device (not installed)"
        val slots = chain.entries
            .filter { it.kind != ProviderKind.LOCAL }
            .mapIndexed { idx, entry ->
                AttemptBudgetCalculator.BudgetSlot(
                    id = entry.id.ifBlank { "cleanup_$idx" },
                    label = ProviderCapabilityRegistry.displayNameFor(entry),
                    locked = entry.isBatchCleanupActive(),
                    attempts = entry.cleanupAttempts,
                )
            }
        val tail = if (allowLocalFallback && localAvailable) "On-device" else null
        return AttemptBudgetCalculator.formatExecutionOrderStrip(
            slots = slots,
            fallbackTail = tail,
            emptyLabel = if (localAvailable) "On-device" else "No cloud cleanup locked",
        )
    }

    /**
     * Phase 3A.1 execution-order strip for Live Post-Cleanup using [AttemptBudgetCalculator].
     */
    fun livePostCleanupExecutionOrder(config: LiveTranscriptionConfig): String {
        if (!config.postCleanupEnabled) return "Direct live text (Post-Cleanup off)"
        val slots = config.postCleanupSlots
            .take(LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS)
            .map { slot ->
                val label = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId
                AttemptBudgetCalculator.BudgetSlot(
                    id = slot.id,
                    label = label,
                    locked = slot.locked && slot.modelId.isNotBlank() && slot.modelId != ProviderChainEntry.SKIP_MODEL_ID,
                    attempts = slot.attempts,
                )
            }
        return AttemptBudgetCalculator.formatExecutionOrderStrip(
            slots = slots,
            fallbackTail = "Raw live text",
            emptyLabel = "Raw live text (no slots locked)",
        )
    }
}
