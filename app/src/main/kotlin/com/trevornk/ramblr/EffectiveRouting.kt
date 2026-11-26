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
        val cloudEntries = chain.capableEntriesFor(needsTranscription = true).filter { it.kind != ProviderKind.LOCAL }
        val configuredEntry = cloudEntries.firstOrNull { isConfigured(it) }
        val cloudConfigured = configuredEntry != null
        val cloudLabel = configuredEntry?.let { ProviderCapabilityRegistry.displayNameFor(it) }
        val cloudDescription = if (cloudConfigured && cloudLabel != null) "Cloud ($cloudLabel)" else "Cloud (not configured)"

        return if (useLocalTranscription) {
            val localHead = if (localAvailable) "On-device" else "On-device (not installed)"
            if (allowCloudFallback) {
                "$localHead \u2192 cloud fallback (${if (cloudConfigured && cloudLabel != null) cloudLabel else "not configured"})"
            } else {
                if (localAvailable) "On-device only" else "On-device (not installed)"
            }
        } else {
            when {
                allowLocalFallback && localAvailable -> "$cloudDescription \u2192 on-device fallback"
                allowLocalFallback && !localAvailable -> "$cloudDescription \u2192 on-device not installed"
                else -> "$cloudDescription only"
            }
        }
    }

    /**
     * "Cleanup: ..." body (caller prefixes the label).
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
        if (!cloudCleanupEnabled) return if (localAvailable) "On-device only" else "On-device (not installed)"

        val cloudEntries = chain.capableEntriesFor(needsTranscription = false).filter { it.kind != ProviderKind.LOCAL }
        val configuredEntry = cloudEntries.firstOrNull { isConfigured(it) }
        val cloudConfigured = configuredEntry != null
        val cloudLabel = configuredEntry?.let { ProviderCapabilityRegistry.displayNameFor(it) }
        val cloudDescription = if (cloudConfigured && cloudLabel != null) "Cloud ($cloudLabel)" else "Cloud (not configured)"

        return when {
            allowLocalFallback && localAvailable -> "$cloudDescription \u2192 on-device fallback"
            allowLocalFallback && !localAvailable -> "$cloudDescription \u2192 on-device not installed"
            else -> "$cloudDescription only"
        }
    }

    /**
     * Phase 3A.1/3A.3 execution-order strip for Batch STT using [AttemptBudgetCalculator].
     * Keyless entries never appear when [isConfigured] returns false.
     */
    fun batchSttExecutionOrder(
        chain: ProviderChain,
        allowLocalFallback: Boolean,
        localAvailable: Boolean,
        useLocalTranscription: Boolean = false,
        isConfigured: (ProviderChainEntry) -> Boolean = { true },
    ): String {
        if (useLocalTranscription) return if (localAvailable) "On-device" else "On-device (not installed)"
        val slots = chain.entries
            .filter { it.kind != ProviderKind.LOCAL }
            .mapIndexed { idx, entry ->
                AttemptBudgetCalculator.BudgetSlot(
                    id = entry.id.ifBlank { "stt_$idx" },
                    label = ProviderCapabilityRegistry.displayNameFor(entry),
                    locked = entry.isBatchSttActive() && isConfigured(entry),
                    attempts = entry.sttAttempts,
                )
            }
        val tail = when {
            !allowLocalFallback -> null
            localAvailable -> "On-device"
            else -> "On-device (not installed)"
        }
        return AttemptBudgetCalculator.formatExecutionOrderStrip(
            slots = slots,
            fallbackTail = tail,
            emptyLabel = if (localAvailable) "On-device" else "No cloud STT active",
        )
    }

    /**
     * Phase 3A.1/3A.3 execution-order strip for Batch Cleanup using [AttemptBudgetCalculator].
     * Keyless entries never appear when [isConfigured] returns false.
     */
    fun batchCleanupExecutionOrder(
        chain: ProviderChain,
        postProcessingEnabled: Boolean,
        cloudCleanupEnabled: Boolean,
        allowLocalFallback: Boolean,
        localAvailable: Boolean,
        isConfigured: (ProviderChainEntry) -> Boolean = { true },
    ): String {
        if (!postProcessingEnabled) return "Off"
        if (!cloudCleanupEnabled) return if (localAvailable) "On-device" else "On-device (not installed)"
        val slots = chain.entries
            .filter { it.kind != ProviderKind.LOCAL }
            .mapIndexed { idx, entry ->
                AttemptBudgetCalculator.BudgetSlot(
                    id = entry.id.ifBlank { "cleanup_$idx" },
                    label = ProviderCapabilityRegistry.displayNameFor(entry),
                    locked = entry.isBatchCleanupActive() && isConfigured(entry),
                    attempts = entry.cleanupAttempts,
                )
            }
        val tail = when {
            !allowLocalFallback -> null
            localAvailable -> "On-device"
            else -> "On-device (not installed)"
        }
        return AttemptBudgetCalculator.formatExecutionOrderStrip(
            slots = slots,
            fallbackTail = tail,
            emptyLabel = if (localAvailable) "On-device" else "No cloud cleanup active",
        )
    }

    /**
     * Phase 3A.1/3A.3 execution-order strip for Live Post-Cleanup using [AttemptBudgetCalculator].
     * Keyless slots never appear when [isSlotConfigured] returns false.
     */
    fun livePostCleanupExecutionOrder(
        config: LiveTranscriptionConfig,
        isSlotConfigured: (LivePostCleanupSlot) -> Boolean = { true },
    ): String {
        if (!config.postCleanupEnabled) return "Direct live text (Post-Cleanup off)"
        val slots = config.postCleanupSlots
            .take(LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS)
            .map { slot ->
                val label = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId
                AttemptBudgetCalculator.BudgetSlot(
                    id = slot.id,
                    label = label,
                    locked = slot.locked &&
                        slot.modelId.isNotBlank() &&
                        slot.modelId != ProviderChainEntry.SKIP_MODEL_ID &&
                        isSlotConfigured(slot),
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
