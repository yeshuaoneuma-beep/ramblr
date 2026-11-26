package com.trevornk.ramblr

/**
 * Capability lanes for Ramblr's cloud providers and models.
 *
 * Every provider picker, model picker, and vault screen is driven from [ProviderCapabilityRegistry]
 * and [ModelCapabilityClassifier] so adding a new provider later (e.g., Hugging Face) requires
 * zero layout changes.
 */
enum class CapabilityLane {
    BATCH_STT,
    CLEANUP,
    LIVE_STT,
}

/**
 * Metadata and capability declaration for a supported cloud provider.
 */
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val presetId: String? = null,
    val defaultBaseUrl: String? = null,
    val supportedLanes: Set<CapabilityLane>,
    val requiresCustomBaseUrl: Boolean = false,
    val allowsMultipleVaultKeys: Boolean = false,
    val keyHint: String = "",
    val keyPrefixHint: String? = null,
    val liveModelsSupported: Boolean = true,
    val isAvailable: () -> Boolean = { true },
) {
    fun supports(lane: CapabilityLane): Boolean = lane in supportedLanes
}

/**
 * Rich metadata for a model offered in a lane-filtered searchable picker.
 */
data class ModelDescriptor(
    val id: String,
    val providerId: String,
    val displayName: String,
    val description: String = "",
    val lanes: Set<CapabilityLane>,
    val tier: ModelTier = ModelTier.GOOD,
    val costPer1MInputUsd: Double? = null,
    val costPer1MOutputUsd: Double? = null,
    val contextLength: Int? = null,
    val inputModalities: Set<String> = emptySet(),
    val isNonThinking: Boolean = false,
    val supportsLiveModeToggle: Boolean = false,
    val badges: List<String> = emptyList(),
    val isLiveFetched: Boolean = false,
) {
    fun supports(lane: CapabilityLane): Boolean = lane in lanes

    fun priceSummary(): String? {
        val input = costPer1MInputUsd
        val output = costPer1MOutputUsd
        if (input == null && output == null) return null
        if ((input ?: 0.0) <= 0.0 && (output ?: 0.0) <= 0.0) return null
        fun usd(v: Double): String = if (v < 0.01 && v > 0.0) {
            "$" + String.format(java.util.Locale.US, "%.4f", v)
        } else {
            "$" + String.format(java.util.Locale.US, "%.2f", v)
        }
        return "${usd(input ?: 0.0)} in \u00b7 ${usd(output ?: 0.0)} out / 1M"
    }

    fun contextSummary(): String? {
        if (ModelCapabilityClassifier.isAsrOnlyModel(id)) return null
        val ctx = contextLength ?: return null
        if (ctx < 1_000) return null
        return when {
            ctx >= 1_000_000 -> "${ctx / 1_000_000}M ctx"
            else -> "${ctx / 1_000}K ctx"
        }
    }
}

/**
 * Single source of truth for all cloud providers supported by Ramblr.
 */
object ProviderCapabilityRegistry {
    const val ID_GEMINI = "gemini"
    const val ID_GROQ = "groq"
    const val ID_OPENROUTER = "openrouter"
    const val ID_OPENAI = "openai"
    const val ID_ANTHROPIC = "anthropic"
    const val ID_OMNIROUTE = "omniroute"
    const val ID_CUSTOM_OPENAI = "custom_openai"

    val GEMINI = ProviderDescriptor(
        id = ID_GEMINI,
        displayName = "Gemini",
        kind = ProviderKind.GEMINI,
        defaultBaseUrl = GeminiCleanupProvider.BASE_URL,
        supportedLanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP, CapabilityLane.LIVE_STT),
        keyHint = "AIza...",
        keyPrefixHint = "AIza",
    )

    val GROQ = ProviderDescriptor(
        id = ID_GROQ,
        displayName = "Groq",
        kind = ProviderKind.OPENAI,
        presetId = ProviderPresets.GROQ.id,
        defaultBaseUrl = ProviderPresets.GROQ.baseUrl,
        supportedLanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
        keyHint = "gsk_...",
        keyPrefixHint = "gsk_",
    )

    val OPENROUTER = ProviderDescriptor(
        id = ID_OPENROUTER,
        displayName = "OpenRouter",
        kind = ProviderKind.OPENAI,
        presetId = ProviderPresets.OPENROUTER.id,
        defaultBaseUrl = ProviderPresets.OPENROUTER.baseUrl,
        supportedLanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
        keyHint = "sk-or-...",
        keyPrefixHint = "sk-or-",
    )

    val OPENAI = ProviderDescriptor(
        id = ID_OPENAI,
        displayName = "OpenAI",
        kind = ProviderKind.OPENAI,
        defaultBaseUrl = PostProcessor.DEFAULT_BASE_URL,
        supportedLanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
        keyHint = "sk-...",
        keyPrefixHint = "sk-",
    )

    val ANTHROPIC = ProviderDescriptor(
        id = ID_ANTHROPIC,
        displayName = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        defaultBaseUrl = AnthropicCleanupProvider.BASE_URL,
        supportedLanes = setOf(CapabilityLane.CLEANUP),
        keyHint = "sk-ant-...",
        keyPrefixHint = "sk-ant-",
    )

    val OMNIROUTE = ProviderDescriptor(
        id = ID_OMNIROUTE,
        displayName = "OmniRoute",
        kind = ProviderKind.OMNIROUTE,
        defaultBaseUrl = OmniRoute.BASE_URL,
        supportedLanes = setOf(CapabilityLane.CLEANUP),
        keyHint = "OmniRoute key",
        isAvailable = { OmniRoute.isConfigured },
    )

    val CUSTOM_OPENAI = ProviderDescriptor(
        id = ID_CUSTOM_OPENAI,
        displayName = "Custom OpenAI-Compatible",
        kind = ProviderKind.OPENAI,
        defaultBaseUrl = null,
        supportedLanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
        requiresCustomBaseUrl = true,
        allowsMultipleVaultKeys = true,
        keyHint = "API key",
    )

    private val registered: List<ProviderDescriptor> = listOf(
        GEMINI,
        GROQ,
        OPENROUTER,
        OPENAI,
        ANTHROPIC,
        OMNIROUTE,
        CUSTOM_OPENAI,
    )

    /** All registered providers, regardless of runtime availability (e.g. OmniRoute unconfigured). */
    fun allRegistered(): List<ProviderDescriptor> = registered

    /** All currently available providers in canonical display order. */
    fun all(): List<ProviderDescriptor> = registered.filter { it.isAvailable() }

    /** Single-key providers shown as primary cards in the API Keys Vault. */
    fun vaultProviders(): List<ProviderDescriptor> = all().filterNot { it.allowsMultipleVaultKeys }

    /** Available providers supporting [lane]. */
    fun providersForLane(lane: CapabilityLane): List<ProviderDescriptor> =
        all().filter { it.supports(lane) }

    /** Available providers supporting at least one batch lane (STT or Cleanup). */
    fun batchProviders(): List<ProviderDescriptor> =
        all().filter { it.supports(CapabilityLane.BATCH_STT) || it.supports(CapabilityLane.CLEANUP) }

    fun forId(id: String?): ProviderDescriptor? =
        id?.let { target -> registered.firstOrNull { it.id == target } }

    fun canonicalVaultKeyId(providerId: String): String = "vault_$providerId"

    fun providerIdFor(
        kind: ProviderKind,
        presetId: String? = null,
        baseUrlOverride: String? = null,
    ): String = when (kind) {
        ProviderKind.GEMINI -> ID_GEMINI
        ProviderKind.ANTHROPIC -> ID_ANTHROPIC
        ProviderKind.OMNIROUTE -> ID_OMNIROUTE
        ProviderKind.LOCAL -> "local"
        ProviderKind.OPENAI -> {
            val url = baseUrlOverride?.trim().orEmpty()
            when {
                presetId == ID_GROQ || url.contains("api.groq.com", ignoreCase = true) -> ID_GROQ
                presetId == ID_OPENROUTER || url.contains("openrouter.ai", ignoreCase = true) -> ID_OPENROUTER
                url.isNotEmpty() && !url.startsWith(PostProcessor.DEFAULT_BASE_URL, ignoreCase = true) -> ID_CUSTOM_OPENAI
                else -> ID_OPENAI
            }
        }
    }

    fun providerIdFor(entry: ProviderChainEntry): String =
        providerIdFor(entry.kind, entry.presetId, entry.baseUrlOverride)

    fun displayNameFor(entry: ProviderChainEntry): String {
        entry.customLabel?.takeIf { it.isNotBlank() }?.let { return it }
        val providerId = providerIdFor(entry)
        return forId(providerId)?.displayName ?: when (entry.kind) {
            ProviderKind.OPENAI -> "OpenAI"
            ProviderKind.GEMINI -> "Gemini"
            ProviderKind.ANTHROPIC -> "Anthropic"
            ProviderKind.OMNIROUTE -> "OmniRoute"
            ProviderKind.LOCAL -> "On-device"
        }
    }
}

/**
 * Curated models and strict lane classification for batch STT, cleanup, and live STT.
 *
 * Prevents cross-lane mismatches such as text-only models (Gemma, Claude, Llama, Qwen, GPT-OSS)
 * appearing in Batch STT, ASR-only models (Whisper, GPT-Transcribe, Gemini 3.5 Transcribe) appearing
 * in Cleanup, or translation-preview / non-live models appearing in Live STT.
 */
object ModelCapabilityClassifier {

    const val GEMINI_TRANSCRIBE_BATCH_ID = "gemini-3.5-transcribe"
    const val GEMINI_LIVE_DEFAULT_ID = "gemini-3.5-transcribe-live"
    const val GEMINI_LIVE_SECONDARY_ID = "gemini-3.8-live"

    private val BLOCKED_LIVE_MODELS = setOf(
        "gemini-3.5-live-translate-preview",
    )

    private val TEXT_ONLY_PREFIXES = listOf(
        "gemma",
        "claude",
        "gpt-oss",
        "llama",
        "meta-llama",
        "qwen",
        "mixtral",
        "mistral",
        "deepseek",
        "command",
        "cohere",
        "phi-",
        "o1",
        "o3",
        "o4",
    )

    private val ASR_ONLY_PREFIXES = listOf(
        "whisper",
        "distil-whisper",
        "gpt-transcribe",
        "gpt-4o-transcribe",
        "gpt-4o-mini-transcribe",
        "gemini-3.5-transcribe",
    )

    private val NON_TEXT_NON_ASR_MARKERS = listOf(
        "embedding",
        "embed-",
        "imagen",
        "veo",
        "dall-e",
        "moderation",
        "sora",
        "-tts",
        "tts-",
        "aqa",
        "bisheng",
        "babbage",
        "davinci",
        "rerank",
        "guard",
        "safeguard",
        "computer-use",
        "robotics",
    )

    private val CURATED_MODELS: List<ModelDescriptor> = listOf(
        // --- Gemini: BATCH_STT ---
        ModelDescriptor(
            id = GEMINI_TRANSCRIBE_BATCH_ID,
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.5 Transcribe",
            description = "Dedicated non-thinking audio transcription model — fast, clean verbatim STT.",
            lanes = setOf(CapabilityLane.BATCH_STT),
            tier = ModelTier.RECOMMENDED,
            inputModalities = setOf("audio"),
            isNonThinking = true,
            badges = listOf("Non-thinking", "Audio STT"),
        ),
        ModelDescriptor(
            id = "gemini-3.1-flash-lite",
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.1 Flash-Lite",
            description = "Fast multimodal audio transcription and inexpensive cleanup.",
            lanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
            tier = ModelTier.RECOMMENDED,
            costPer1MInputUsd = 0.25,
            costPer1MOutputUsd = 1.50,
            contextLength = 1_000_000,
            inputModalities = setOf("text", "audio"),
            badges = listOf("Audio STT", "Fast"),
        ),
        ModelDescriptor(
            id = "gemini-3.5-flash-lite",
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.5 Flash-Lite",
            description = "Current-generation lite tier — recommended cleanup pick; also supports audio STT.",
            lanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
            tier = ModelTier.RECOMMENDED,
            costPer1MInputUsd = 0.30,
            costPer1MOutputUsd = 2.50,
            contextLength = 1_000_000,
            inputModalities = setOf("text", "audio"),
            badges = listOf("Recommended"),
        ),
        ModelDescriptor(
            id = "gemini-3.8-flash",
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.8 Flash",
            description = "High-capability Flash tier supporting both multimodal audio STT and cleanup.",
            lanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            contextLength = 1_000_000,
            inputModalities = setOf("text", "audio"),
            badges = listOf("Audio STT"),
        ),
        ModelDescriptor(
            id = "gemini-3.5-flash",
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.5 Flash",
            description = "Higher-tier Flash model for cleanup and multimodal tasks.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            costPer1MInputUsd = 1.50,
            costPer1MOutputUsd = 9.00,
            contextLength = 1_000_000,
            inputModalities = setOf("text", "audio"),
        ),
        ModelDescriptor(
            id = "gemma-4-31b-it",
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemma 4 31B IT",
            description = "Open-weight text model on Gemini API — cleanup only (no audio STT).",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            inputModalities = setOf("text"),
            badges = listOf("Text only"),
        ),
        ModelDescriptor(
            id = "gemma-4-26b-a4b-it",
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemma 4 26B A4B IT",
            description = "Fast MoE Gemma 4 text model on Gemini API — cleanup only (no audio STT).",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            inputModalities = setOf("text"),
            badges = listOf("Text only"),
        ),

        // --- Gemini: LIVE_STT (strictly excludes gemini-3.5-live-translate-preview) ---
        ModelDescriptor(
            id = GEMINI_LIVE_DEFAULT_ID,
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.5 Transcribe Live",
            description = "Default streaming live transcription with Verbatim and Smart mode support.",
            lanes = setOf(CapabilityLane.LIVE_STT),
            tier = ModelTier.RECOMMENDED,
            inputModalities = setOf("audio"),
            isNonThinking = true,
            supportsLiveModeToggle = true,
            badges = listOf("Default", "Non-thinking", "Live Audio"),
        ),
        ModelDescriptor(
            id = GEMINI_LIVE_SECONDARY_ID,
            providerId = ProviderCapabilityRegistry.ID_GEMINI,
            displayName = "Gemini 3.8 Live",
            description = "Secondary live streaming model for real-time audio transcription.",
            lanes = setOf(CapabilityLane.LIVE_STT),
            tier = ModelTier.GOOD,
            inputModalities = setOf("audio"),
            supportsLiveModeToggle = false,
            badges = listOf("Live Audio"),
        ),

        // --- Groq ---
        ModelDescriptor(
            id = "whisper-large-v3-turbo",
            providerId = ProviderCapabilityRegistry.ID_GROQ,
            displayName = "Whisper Large v3 Turbo",
            description = "Fast Groq ASR model with vocabulary prompting.",
            lanes = setOf(CapabilityLane.BATCH_STT),
            tier = ModelTier.RECOMMENDED,
            inputModalities = setOf("audio"),
            badges = listOf("Audio STT", "Fast"),
        ),
        ModelDescriptor(
            id = "whisper-large-v3",
            providerId = ProviderCapabilityRegistry.ID_GROQ,
            displayName = "Whisper Large v3",
            description = "Full Whisper Large v3 on Groq.",
            lanes = setOf(CapabilityLane.BATCH_STT),
            tier = ModelTier.GOOD,
            inputModalities = setOf("audio"),
            badges = listOf("Audio STT"),
        ),
        ModelDescriptor(
            id = "openai/gpt-oss-20b",
            providerId = ProviderCapabilityRegistry.ID_GROQ,
            displayName = "GPT-OSS 20B",
            description = "Fast, inexpensive Groq chat model for cleanup.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.RECOMMENDED,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "openai/gpt-oss-120b",
            providerId = ProviderCapabilityRegistry.ID_GROQ,
            displayName = "GPT-OSS 120B",
            description = "Larger Groq chat model for higher-quality cleanup.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            inputModalities = setOf("text"),
        ),

        // --- OpenRouter ---
        ModelDescriptor(
            id = "openai/whisper-1",
            providerId = ProviderCapabilityRegistry.ID_OPENROUTER,
            displayName = "OpenRouter: Whisper-1",
            description = "OpenRouter audio transcription route.",
            lanes = setOf(CapabilityLane.BATCH_STT),
            tier = ModelTier.RECOMMENDED,
            inputModalities = setOf("audio"),
            badges = listOf("Audio STT"),
        ),
        ModelDescriptor(
            id = "openai/gpt-oss-120b",
            providerId = ProviderCapabilityRegistry.ID_OPENROUTER,
            displayName = "OpenRouter: GPT-OSS 120B",
            description = "Inexpensive multi-model cleanup default via OpenRouter.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.RECOMMENDED,
            costPer1MInputUsd = 0.05,
            costPer1MOutputUsd = 0.25,
            inputModalities = setOf("text"),
        ),

        // --- OpenAI ---
        ModelDescriptor(
            id = "gpt-transcribe",
            providerId = ProviderCapabilityRegistry.ID_OPENAI,
            displayName = "GPT-Transcribe",
            description = "OpenAI's dedicated ASR model — high accuracy and lower cost per minute.",
            lanes = setOf(CapabilityLane.BATCH_STT),
            tier = ModelTier.RECOMMENDED,
            inputModalities = setOf("audio"),
            badges = listOf("Audio STT"),
        ),
        ModelDescriptor(
            id = "gpt-5.6-luna",
            providerId = ProviderCapabilityRegistry.ID_OPENAI,
            displayName = "GPT-5.6 Luna",
            description = "OpenAI's cheapest cleanup tier — fast and accurate.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.RECOMMENDED,
            costPer1MInputUsd = 0.20,
            costPer1MOutputUsd = 1.20,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "gpt-5.4-mini",
            providerId = ProviderCapabilityRegistry.ID_OPENAI,
            displayName = "GPT-5.4 Mini",
            description = "Previous-generation OpenAI mid tier for cleanup.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            costPer1MInputUsd = 0.75,
            costPer1MOutputUsd = 4.50,
            inputModalities = setOf("text"),
        ),

        // --- Anthropic ---
        ModelDescriptor(
            id = "claude-haiku-4-5-20251001",
            providerId = ProviderCapabilityRegistry.ID_ANTHROPIC,
            displayName = "Claude Haiku 4.5",
            description = "Fast Anthropic cleanup model (cleanup only — no audio STT).",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            costPer1MInputUsd = 1.00,
            costPer1MOutputUsd = 5.00,
            inputModalities = setOf("text"),
            badges = listOf("Text only"),
        ),

        // --- OmniRoute ---
        ModelDescriptor(
            id = "gemini/gemini-flash-lite-latest",
            providerId = ProviderCapabilityRegistry.ID_OMNIROUTE,
            displayName = "OmniRoute: Gemini Flash-Lite (auto-upgrading)",
            description = "Cheapest auto-upgrading OmniRoute cleanup alias.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.RECOMMENDED,
            costPer1MInputUsd = 0.30,
            costPer1MOutputUsd = 2.50,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "cx/gpt-5.4-mini",
            providerId = ProviderCapabilityRegistry.ID_OMNIROUTE,
            displayName = "OmniRoute: GPT-5.4 Mini",
            description = "OpenAI-family cleanup route on OmniRoute.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            costPer1MInputUsd = 0.75,
            costPer1MOutputUsd = 4.50,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "cc/claude-haiku-4-5-20251001",
            providerId = ProviderCapabilityRegistry.ID_OMNIROUTE,
            displayName = "OmniRoute: Claude Haiku 4.5",
            description = "Claude Haiku cleanup route on OmniRoute.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.GOOD,
            costPer1MInputUsd = 1.00,
            costPer1MOutputUsd = 5.00,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "gemini/gemini-pro-latest",
            providerId = ProviderCapabilityRegistry.ID_OMNIROUTE,
            displayName = "OmniRoute: Gemini Pro (auto-upgrading)",
            description = "Premium auto-upgrading Gemini Pro cleanup alias.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.ADVANCED,
            costPer1MInputUsd = 2.00,
            costPer1MOutputUsd = 12.00,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "auto/claude-sonnet",
            providerId = ProviderCapabilityRegistry.ID_OMNIROUTE,
            displayName = "OmniRoute: Claude Sonnet (auto-upgrading)",
            description = "Premium auto-upgrading Claude Sonnet cleanup alias.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.ADVANCED,
            costPer1MInputUsd = 2.00,
            costPer1MOutputUsd = 10.00,
            inputModalities = setOf("text"),
        ),
        ModelDescriptor(
            id = "auto/claude-opus",
            providerId = ProviderCapabilityRegistry.ID_OMNIROUTE,
            displayName = "OmniRoute: Claude Opus (auto-upgrading)",
            description = "Highest-tier auto-upgrading Claude Opus cleanup alias.",
            lanes = setOf(CapabilityLane.CLEANUP),
            tier = ModelTier.ADVANCED,
            costPer1MInputUsd = 5.00,
            costPer1MOutputUsd = 25.00,
            inputModalities = setOf("text"),
        ),
    )

    fun curatedModelsFor(providerId: String, lane: CapabilityLane): List<ModelDescriptor> {
        val list = CURATED_MODELS.filter { it.providerId == providerId && it.supports(lane) }
        if (providerId == ProviderCapabilityRegistry.ID_GEMINI && lane == CapabilityLane.CLEANUP) {
            // Preserve recommended cleanup order: 3.5-flash-lite, 3.1-flash-lite, 3.8-flash, 3.5-flash, gemma-4-*
            val order = listOf(
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-3.8-flash",
                "gemini-3.5-flash",
                "gemma-4-31b-it",
                "gemma-4-26b-a4b-it",
            )
            return list.sortedBy { m -> order.indexOf(m.id).let { if (it < 0) Int.MAX_VALUE else it } }
        }
        return list
    }

    fun curatedModelById(providerId: String, modelId: String): ModelDescriptor? {
        val normalized = normalizeId(modelId)
        return CURATED_MODELS.firstOrNull {
            it.providerId == providerId && normalizeId(it.id) == normalized
        }
    }

    fun normalizeId(modelId: String): String =
        modelId.trim().removePrefix("models/").lowercase()

    fun isBlockedTranslateModel(modelId: String): Boolean {
        val id = normalizeId(modelId)
        return id in BLOCKED_LIVE_MODELS || "translate" in id
    }

    fun isAsrOnlyModel(modelId: String): Boolean {
        val id = normalizeId(modelId)
        val leaf = id.substringAfterLast('/')
        if (ASR_ONLY_PREFIXES.any { id.startsWith(it) || leaf.startsWith(it) }) return true
        return "transcribe" in leaf || leaf.startsWith("whisper") || leaf.contains("-asr")
    }

    fun isTextOnlyFamily(modelId: String): Boolean {
        val id = normalizeId(modelId)
        val leaf = id.substringAfterLast('/')
        return TEXT_ONLY_PREFIXES.any { id.startsWith(it) || leaf.startsWith(it) }
    }

    private fun isNonTextNonAsrUtility(modelId: String): Boolean {
        val id = normalizeId(modelId)
        return NON_TEXT_NON_ASR_MARKERS.any { it in id }
    }

    private val LIVE_TOKEN_REGEX = Regex("(^|[-_/.])live($|[-_/.])")

    fun isLiveStreamingModel(modelId: String): Boolean {
        val id = normalizeId(modelId)
        val leaf = id.substringAfterLast('/')
        if (id == GEMINI_LIVE_DEFAULT_ID || id == GEMINI_LIVE_SECONDARY_ID) return true
        if (leaf == GEMINI_LIVE_DEFAULT_ID || leaf == GEMINI_LIVE_SECONDARY_ID) return true
        return LIVE_TOKEN_REGEX.containsMatchIn(id) ||
            LIVE_TOKEN_REGEX.containsMatchIn(leaf) ||
            "native-audio" in id || "native-audio" in leaf ||
            "realtime" in id || "realtime" in leaf ||
            "bidi" in id || "bidi" in leaf
    }

    /**
     * Strict lane eligibility check for a model on [providerId].
     *
     * When [inputModalities] is non-null (e.g. OpenRouter live `/models`), OpenRouter `BATCH_STT`
     * strictly requires `"audio"` in [inputModalities].
     */
    fun supportsLane(
        providerId: String,
        modelId: String,
        lane: CapabilityLane,
        inputModalities: Set<String>? = null,
    ): Boolean {
        val descriptor = ProviderCapabilityRegistry.forId(providerId) ?: return false
        if (!descriptor.supports(lane)) return false
        val id = normalizeId(modelId)
        if (id.isEmpty()) return false
        if (isNonTextNonAsrUtility(id)) return false

        val normalizedModalities = inputModalities?.map { it.lowercase() }?.toSet()

        return when (lane) {
            CapabilityLane.LIVE_STT -> {
                if (providerId != ProviderCapabilityRegistry.ID_GEMINI) return false
                if (isBlockedTranslateModel(id)) return false
                id == GEMINI_LIVE_DEFAULT_ID || id == GEMINI_LIVE_SECONDARY_ID
            }

            CapabilityLane.BATCH_STT -> {
                if (isBlockedTranslateModel(id) || isLiveStreamingModel(id)) return false
                if (isTextOnlyFamily(id)) return false
                when (providerId) {
                    ProviderCapabilityRegistry.ID_OPENROUTER -> {
                        if (normalizedModalities != null) {
                            "audio" in normalizedModalities
                        } else {
                            isAsrOnlyModel(id) || "audio" in id
                        }
                    }

                    ProviderCapabilityRegistry.ID_GEMINI -> {
                        if (!id.startsWith("gemini-")) return false
                        if ("image" in id || "vision" in id || "exp-image" in id) return false
                        true
                    }

                    ProviderCapabilityRegistry.ID_GROQ,
                    ProviderCapabilityRegistry.ID_OPENAI,
                    ProviderCapabilityRegistry.ID_CUSTOM_OPENAI -> {
                        if (normalizedModalities != null && "audio" in normalizedModalities) return true
                        isAsrOnlyModel(id) || "audio" in id || "stt" in id
                    }

                    else -> false
                }
            }

            CapabilityLane.CLEANUP -> {
                if (isAsrOnlyModel(id)) return false
                if (isLiveStreamingModel(id) || isBlockedTranslateModel(id)) return false
                if ("realtime" in id || "audio-preview" in id) return false
                if (providerId == ProviderCapabilityRegistry.ID_GEMINI && ("image" in id && "flash-image" in id)) {
                    return false
                }
                true
            }
        }
    }

    fun classifyLanes(
        providerId: String,
        modelId: String,
        inputModalities: Set<String>? = null,
    ): Set<CapabilityLane> = CapabilityLane.entries
        .filter { supportsLane(providerId, modelId, it, inputModalities) }
        .toSet()

    /**
     * Returns a user-facing warning if a manually typed custom model ID looks incompatible with [lane].
     */
    fun wrongLaneWarning(providerId: String, modelId: String, lane: CapabilityLane): String? {
        val id = normalizeId(modelId)
        if (id.isEmpty()) return null
        return when (lane) {
            CapabilityLane.BATCH_STT -> when {
                isTextOnlyFamily(id) -> "\"$modelId\" looks like a text-only model and may reject audio input."
                isLiveStreamingModel(id) || isBlockedTranslateModel(id) ->
                    "\"$modelId\" is a live/translation model and is not valid for Batch STT."
                else -> null
            }
            CapabilityLane.CLEANUP -> when {
                isAsrOnlyModel(id) -> "\"$modelId\" looks like an ASR-only model and may fail text cleanup."
                isLiveStreamingModel(id) -> "\"$modelId\" is a live streaming model and is not valid for text cleanup."
                else -> null
            }
            CapabilityLane.LIVE_STT -> when {
                isBlockedTranslateModel(id) -> "\"$modelId\" is a translation preview model and is not supported for Live STT."
                !supportsLane(providerId, id, CapabilityLane.LIVE_STT) ->
                    "\"$modelId\" is not a supported Gemini Live STT model."
                else -> null
            }
        }
    }
}
