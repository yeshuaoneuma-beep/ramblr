package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilityRegistryTest {

    @Test fun `registry exposes expected providers and excludes Hugging Face and unimplemented streaming engines`() {
        val ids = ProviderCapabilityRegistry.allRegistered().map { it.id }
        assertTrue(ProviderCapabilityRegistry.ID_GEMINI in ids)
        assertTrue(ProviderCapabilityRegistry.ID_GROQ in ids)
        assertTrue(ProviderCapabilityRegistry.ID_OPENROUTER in ids)
        assertTrue(ProviderCapabilityRegistry.ID_OPENAI in ids)
        assertTrue(ProviderCapabilityRegistry.ID_ANTHROPIC in ids)
        assertTrue(ProviderCapabilityRegistry.ID_OMNIROUTE in ids)
        assertTrue(ProviderCapabilityRegistry.ID_CUSTOM_OPENAI in ids)
        assertFalse(ids.any { "hugging" in it || "deepgram" in it || "sherpa" in it })

        val custom = ProviderCapabilityRegistry.forId(ProviderCapabilityRegistry.ID_CUSTOM_OPENAI)!!
        assertTrue(custom.requiresCustomBaseUrl)
        assertTrue(custom.allowsMultipleVaultKeys)
    }

    @Test fun `LIVE_STT lane exposes only Gemini and strictly excludes gemini-3-5-live-translate-preview`() {
        val liveProviders = ProviderCapabilityRegistry.providersForLane(CapabilityLane.LIVE_STT)
        assertEquals(listOf(ProviderCapabilityRegistry.ID_GEMINI), liveProviders.map { it.id })

        val liveModels = ModelCapabilityClassifier.curatedModelsFor(ProviderCapabilityRegistry.ID_GEMINI, CapabilityLane.LIVE_STT)
        assertEquals(listOf("gemini-3.5-transcribe-live", "gemini-3.8-live"), liveModels.map { it.id })
        assertTrue(liveModels.first().supportsLiveModeToggle)
        assertFalse(liveModels[1].supportsLiveModeToggle)

        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-live-translate-preview", CapabilityLane.LIVE_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-live-translate-preview", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-live-translate-preview", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.1-flash-lite", CapabilityLane.LIVE_STT))
    }

    @Test fun `curated Gemini BATCH_STT includes gemini-3-5-transcribe with Non-thinking badge and curated CLEANUP includes Gemma 4`() {
        val sttModels = ModelCapabilityClassifier.curatedModelsFor("gemini", CapabilityLane.BATCH_STT)
        val sttIds = sttModels.map { it.id }
        assertTrue("gemini-3.5-transcribe" in sttIds)
        assertTrue("gemini-3.1-flash-lite" in sttIds)
        assertTrue("gemini-3.5-flash-lite" in sttIds)
        assertTrue("gemini-3.8-flash" in sttIds)
        val transcribe = sttModels.first { it.id == "gemini-3.5-transcribe" }
        assertTrue(transcribe.isNonThinking)
        assertTrue("Non-thinking" in transcribe.badges)
        assertTrue("Audio STT" in transcribe.badges)

        val cleanupModels = ModelCapabilityClassifier.curatedModelsFor("gemini", CapabilityLane.CLEANUP).map { it.id }
        assertTrue("gemini-3.5-flash-lite" in cleanupModels)
        assertTrue("gemini-3.1-flash-lite" in cleanupModels)
        assertTrue("gemini-3.8-flash" in cleanupModels)
        assertTrue("gemini-3.5-flash" in cleanupModels)
        assertTrue("gemma-4-31b-it" in cleanupModels)
        assertTrue("gemma-4-26b-a4b-it" in cleanupModels)
    }

    @Test fun `strict lane filtering blocks text-only models from BATCH_STT and ASR-only models from CLEANUP`() {
        // Blocked from BATCH_STT
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemma-4-31b-it", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemma-4-26b-a4b-it", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("anthropic", "claude-haiku-4-5-20251001", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("groq", "openai/gpt-oss-20b", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("groq", "llama-3.3-70b-versatile", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("groq", "qwen-2.5-32b", CapabilityLane.BATCH_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("groq", "mixtral-8x7b-32768", CapabilityLane.BATCH_STT))
        assertFalse(
            ModelCapabilityClassifier.supportsLane(
                "openrouter",
                "google/gemini-3.1-flash-lite",
                CapabilityLane.BATCH_STT,
                inputModalities = setOf("text", "image"),
            )
        )
        assertTrue(
            ModelCapabilityClassifier.supportsLane(
                "openrouter",
                "google/gemini-3.1-flash-lite",
                CapabilityLane.BATCH_STT,
                inputModalities = setOf("text", "audio"),
            )
        )

        // Blocked from CLEANUP
        assertFalse(ModelCapabilityClassifier.supportsLane("groq", "whisper-large-v3-turbo", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("groq", "distil-whisper-large-v3-en", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("openai", "gpt-transcribe", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("openai", "gpt-4o-transcribe", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("openai", "gpt-4o-mini-transcribe", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-transcribe", CapabilityLane.CLEANUP))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-transcribe-live", CapabilityLane.CLEANUP))

        // Warnings for manual escape hatch
        assertTrue(ModelCapabilityClassifier.wrongLaneWarning("gemini", "gemma-4-31b-it", CapabilityLane.BATCH_STT) != null)
        assertTrue(ModelCapabilityClassifier.wrongLaneWarning("openai", "gpt-transcribe", CapabilityLane.CLEANUP) != null)
        assertNull(ModelCapabilityClassifier.wrongLaneWarning("gemini", "gemini-3.5-transcribe", CapabilityLane.BATCH_STT))
    }

    @Test fun `providerIdFor resolves presets and custom URLs accurately`() {
        assertEquals("gemini", ProviderCapabilityRegistry.providerIdFor(ProviderKind.GEMINI))
        assertEquals("anthropic", ProviderCapabilityRegistry.providerIdFor(ProviderKind.ANTHROPIC))
        assertEquals("omniroute", ProviderCapabilityRegistry.providerIdFor(ProviderKind.OMNIROUTE))
        assertEquals("openai", ProviderCapabilityRegistry.providerIdFor(ProviderKind.OPENAI))
        assertEquals("groq", ProviderCapabilityRegistry.providerIdFor(ProviderKind.OPENAI, presetId = "groq"))
        assertEquals("openrouter", ProviderCapabilityRegistry.providerIdFor(ProviderKind.OPENAI, baseUrlOverride = "https://openrouter.ai/api/v1"))
        assertEquals("custom_openai", ProviderCapabilityRegistry.providerIdFor(ProviderKind.OPENAI, baseUrlOverride = "https://my-gateway.local/v1"))
    }

    @Test fun `non-speech and utility models are blocked across BATCH_STT, CLEANUP, and LIVE_STT`() {
        val blocked = listOf(
            "gemini-nano-banana-2.1",
            "gemini-2.5-flash-image",
            "lyria-002",
            "veo-3.0-generate",
            "imagen-4.0-fast",
            "gemini-tts",
            "gemini-2.5-flash-preview-tts",
            "text-bison-001",
            "embedding-gecko-001",
        )
        for (modelId in blocked) {
            assertTrue("$modelId must match isNonSpeechOrUtilityModel", ModelCapabilityClassifier.isNonSpeechOrUtilityModel(modelId))
            for (lane in CapabilityLane.entries) {
                assertFalse(
                    "$modelId must be blocked from $lane",
                    ModelCapabilityClassifier.supportsLane(ProviderCapabilityRegistry.ID_GEMINI, modelId, lane),
                )
            }
        }
        // Live streaming models like native-audio and *-live* pass LIVE_STT on Gemini.
        assertTrue(ModelCapabilityClassifier.supportsLane("gemini", "gemini-2.5-flash-native-audio-preview", CapabilityLane.LIVE_STT))
        assertTrue(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-transcribe-live", CapabilityLane.LIVE_STT))
        assertTrue(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.8-live", CapabilityLane.LIVE_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-2.5-flash", CapabilityLane.LIVE_STT))
        assertFalse(ModelCapabilityClassifier.supportsLane("gemini", "gemini-3.5-live-translate-preview", CapabilityLane.LIVE_STT))
    }
}
