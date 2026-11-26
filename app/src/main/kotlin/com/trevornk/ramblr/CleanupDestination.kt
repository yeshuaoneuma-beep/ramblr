package com.trevornk.ramblr

/**
 * Derives the user-facing "where does cloud cleanup actually go" facts from the live provider chain,
 * so the off-device consent dialog and the Settings subtitles name the *real* destination instead of
 * the legacy `cleanup_base_url`/`cleanup_model` prefs that nothing writes (they always read
 * "api.openai.com" / "gpt-4o-mini" even when cleanup routes to Gemini/Anthropic/OmniRoute) (M9).
 * Pure -- takes the chain, no Context.
 */
object CleanupDestination {
    /** The entry that would actually serve cloud cleanup: the first non-LOCAL entry the cleanup
     *  resolver would use, or null when the chain is local-only / empty. Goes through
     *  [ProviderChain.capableEntriesFor] so a disabled entry, or one opted out of cleanup (#274),
     *  is never named as the destination in the consent dialog or Settings subtitle. */
    fun firstCloudEntry(chain: ProviderChain): ProviderChainEntry? =
        chain.capableEntriesFor(needsTranscription = false).firstOrNull { it.kind != ProviderKind.LOCAL }

    /** The entry that would actually serve cloud transcription: the first non-LOCAL entry the
     *  transcription resolver would use (M9; #274 enabled/opt-in gates included), or null when
     *  none is configured. */
    fun firstCloudTranscription(chain: ProviderChain): ProviderChainEntry? =
        chain.capableEntriesFor(needsTranscription = true).firstOrNull { it.kind != ProviderKind.LOCAL }

    /** Network host cloud cleanup would contact for [entry], e.g. "api.openai.com". */
    fun hostFor(entry: ProviderChainEntry): String = when (entry.kind) {
        ProviderKind.OPENAI -> PostProcessor.destinationHost(entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL)
        ProviderKind.GEMINI -> PostProcessor.destinationHost(entry.baseUrlOverride ?: GeminiCleanupProvider.BASE_URL)
        ProviderKind.ANTHROPIC -> PostProcessor.destinationHost(entry.baseUrlOverride ?: AnthropicCleanupProvider.BASE_URL)
        ProviderKind.OMNIROUTE -> PostProcessor.destinationHost(entry.baseUrlOverride ?: OmniRoute.BASE_URL)
        ProviderKind.LOCAL -> "your device"
    }

    /** Display label for [entry], using [ProviderCapabilityRegistry.displayNameFor] so Groq,
     *  OpenRouter, and Custom OpenAI-Compatible entries are never mislabeled as `"OpenAI"`. */
    fun label(entry: ProviderChainEntry): String = ProviderCapabilityRegistry.displayNameFor(entry)

    /** Display label for [kind], e.g. "OpenAI". */
    fun label(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI -> "OpenAI"
        ProviderKind.GEMINI -> "Gemini"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.OMNIROUTE -> "OmniRoute"
        ProviderKind.LOCAL -> "on-device"
    }

    /** Host to name in the "cleanup sends text off-device" consent for [chain]; falls back to the
     *  OpenAI default the Cloud switch would seed when no cloud entry exists yet. */
    fun consentHost(chain: ProviderChain): String =
        firstCloudEntry(chain)?.let { hostFor(it) } ?: PostProcessor.destinationHost(PostProcessor.DEFAULT_BASE_URL)

    /** The "<provider> · <model>" detail for the Cleanup row's "Cloud · …" subtitle. */
    fun cloudSubtitleDetail(chain: ProviderChain): String {
        val entry = firstCloudEntry(chain)
            ?: return "${label(ProviderKind.OPENAI)} · ${PostProcessor.DEFAULT_MODEL}"
        val model = entry.model.ifBlank { defaultCleanupModelFor(entry.kind) }
        return "${ProviderCapabilityRegistry.displayNameFor(entry)} · $model"
    }

    /** The "<provider> · <model>" detail for the Transcription row's "Cloud · …" subtitle
     *  (#101 follow-up to #93/M9). */
    fun cloudTranscriptionSubtitleDetail(chain: ProviderChain): String {
        val entry = firstCloudTranscription(chain)
            ?: return "${label(ProviderKind.OPENAI)} · ${TranscriberClient.DEFAULT_MODEL}"
        val model = entry.transcriptionModel?.ifBlank { null } ?: defaultTranscriptionModelFor(entry.kind)
        return "${ProviderCapabilityRegistry.displayNameFor(entry)} · $model"
    }

    private fun defaultCleanupModelFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.GEMINI -> GeminiCleanupProvider.DEFAULT_MODEL
        else -> PostProcessor.DEFAULT_MODEL
    }

    private fun defaultTranscriptionModelFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.GEMINI -> GeminiTranscriberClient.DEFAULT_MODEL
        else -> TranscriberClient.DEFAULT_MODEL
    }
}
