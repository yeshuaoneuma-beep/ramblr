package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsParserTest {

    @Test fun `parses OpenRouter pricing per 1M, context length, and audio modality filtering`() {
        val raw = """
            {
              "data": [
                {
                  "id": "google/gemini-3.5-flash",
                  "name": "Gemini 3.5 Flash",
                  "context_length": 1000000,
                  "pricing": { "prompt": "0.0000015", "completion": "0.000009" },
                  "architecture": { "input_modalities": ["text", "audio"] }
                },
                {
                  "id": "anthropic/claude-haiku-4.5",
                  "name": "Claude Haiku 4.5",
                  "context_length": 200000,
                  "pricing": { "prompt": "0.000001", "completion": "0.000005" },
                  "architecture": { "input_modalities": ["text"] }
                },
                {
                  "id": "",
                  "name": "Malformed empty id"
                },
                "not-an-object"
              ]
            }
        """.trimIndent()

        val parsed = ProviderModelsParser.parse(ProviderCapabilityRegistry.ID_OPENROUTER, raw)
        assertEquals(2, parsed.size)

        val gemini = parsed.first { it.id == "google/gemini-3.5-flash" }
        assertTrue(gemini.supports(CapabilityLane.BATCH_STT))
        assertTrue(gemini.supports(CapabilityLane.CLEANUP))
        assertEquals(1.5, gemini.costPer1MInputUsd!!, 0.0001)
        assertEquals(9.0, gemini.costPer1MOutputUsd!!, 0.0001)
        assertEquals(1_000_000, gemini.contextLength)

        val claude = parsed.first { it.id == "anthropic/claude-haiku-4.5" }
        assertFalse(claude.supports(CapabilityLane.BATCH_STT))
        assertTrue(claude.supports(CapabilityLane.CLEANUP))
        assertEquals(1.0, claude.costPer1MInputUsd!!, 0.0001)
        assertEquals(5.0, claude.costPer1MOutputUsd!!, 0.0001)
    }

    @Test fun `parses Gemini models stripping models prefix and filtering translate-preview and embeddings`() {
        val raw = """
            {
              "models": [
                { "name": "models/gemini-3.5-transcribe", "displayName": "Gemini 3.5 Transcribe", "inputTokenLimit": 128000, "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/gemini-3.5-transcribe-live", "displayName": "Gemini 3.5 Transcribe Live", "supportedGenerationMethods": ["bidiGenerateContent"] },
                { "name": "models/gemini-3.5-live-translate-preview", "displayName": "Translate Preview", "supportedGenerationMethods": ["bidiGenerateContent"] },
                { "name": "models/gemma-4-31b-it", "displayName": "Gemma 4 31B", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/text-embedding-004", "displayName": "Embedding", "supportedGenerationMethods": ["embedContent"] }
              ]
            }
        """.trimIndent()

        val parsed = ProviderModelsParser.parse(ProviderCapabilityRegistry.ID_GEMINI, raw)
        val ids = parsed.map { it.id }
        assertTrue("gemini-3.5-transcribe" in ids)
        assertTrue("gemini-3.5-transcribe-live" in ids)
        assertTrue("gemma-4-31b-it" in ids)
        assertFalse("gemini-3.5-live-translate-preview" in ids)
        assertFalse("text-embedding-004" in ids)

        val transcribe = parsed.first { it.id == "gemini-3.5-transcribe" }
        assertTrue(transcribe.supports(CapabilityLane.BATCH_STT))
        assertFalse(transcribe.supports(CapabilityLane.CLEANUP))
        assertTrue(transcribe.isNonThinking)

        val gemma = parsed.first { it.id == "gemma-4-31b-it" }
        assertFalse(gemma.supports(CapabilityLane.BATCH_STT))
        assertTrue(gemma.supports(CapabilityLane.CLEANUP))
    }

    @Test fun `parses Groq OpenAI Anthropic and merges live with curated metadata`() {
        val groqRaw = """
            {
              "data": [
                { "id": "whisper-large-v3-turbo", "context_window": 448 },
                { "id": "llama-3.3-70b-versatile", "context_window": 131072 }
              ]
            }
        """.trimIndent()
        val groqParsed = ProviderModelsParser.parse("groq", groqRaw)
        assertEquals(2, groqParsed.size)
        assertTrue(groqParsed.first { it.id == "whisper-large-v3-turbo" }.supports(CapabilityLane.BATCH_STT))
        assertFalse(groqParsed.first { it.id == "whisper-large-v3-turbo" }.supports(CapabilityLane.CLEANUP))
        assertFalse(groqParsed.first { it.id == "llama-3.3-70b-versatile" }.supports(CapabilityLane.BATCH_STT))
        assertTrue(groqParsed.first { it.id == "llama-3.3-70b-versatile" }.supports(CapabilityLane.CLEANUP))

        val anthropicRaw = """{"data":[{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"}]}"""
        val anthropicParsed = ProviderModelsParser.parse("anthropic", anthropicRaw)
        assertEquals(1, anthropicParsed.size)
        assertEquals(setOf(CapabilityLane.CLEANUP), anthropicParsed.single().lanes)

        // Merge enriches curated models and keeps non-thinking badge offline/online.
        val merged = LiveModelCatalogStore.mergeModels("gemini", emptyList(), CapabilityLane.BATCH_STT)
        val transcribe = merged.first { it.id == "gemini-3.5-transcribe" }
        assertTrue(transcribe.isNonThinking)
        assertTrue("Non-thinking" in transcribe.badges)
    }

    @Test fun `buildRequest puts API keys in headers only and never in query string`() {
        val geminiReq = LiveModelCatalogStore.buildRequest("gemini", "AIzaTestKey1234567890123")!!
        assertEquals("AIzaTestKey1234567890123", geminiReq.header("x-goog-api-key"))
        assertFalse(geminiReq.url.toString().contains("AIzaTestKey1234567890123"))

        val anthropicReq = LiveModelCatalogStore.buildRequest("anthropic", "sk-ant-test123456789012345")!!
        assertEquals("sk-ant-test123456789012345", anthropicReq.header("x-api-key"))
        assertFalse(anthropicReq.url.toString().contains("sk-ant"))

        val openaiReq = LiveModelCatalogStore.buildRequest("openai", "sk-test123456789012345678")!!
        assertEquals("Bearer sk-test123456789012345678", openaiReq.header("Authorization"))
        assertFalse(openaiReq.url.toString().contains("sk-test"))
    }

    @Test fun `parseGemini and mergeModels purge non-speech models and require bidiGenerateContent for non-curated live models`() {
        val raw = """
            {
              "models": [
                { "name": "models/gemini-nano-banana-2.1", "displayName": "Nano Banana", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/gemini-2.5-flash-image", "displayName": "Flash Image", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/lyria-002", "displayName": "Lyria", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/veo-3.0", "displayName": "Veo", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/imagen-4.0", "displayName": "Imagen", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/gemini-tts", "displayName": "Gemini TTS", "supportedGenerationMethods": ["generateContent"] },
                { "name": "models/gemini-2.5-flash-native-audio-preview", "displayName": "Native Audio Live", "supportedGenerationMethods": ["bidiGenerateContent"] },
                { "name": "models/gemini-2.5-live-no-bidi", "displayName": "No Bidi", "supportedGenerationMethods": ["generateContent"] }
              ]
            }
        """.trimIndent()

        val parsed = ProviderModelsParser.parse(ProviderCapabilityRegistry.ID_GEMINI, raw)
        val ids = parsed.map { it.id }
        assertFalse("gemini-nano-banana-2.1" in ids)
        assertFalse("gemini-2.5-flash-image" in ids)
        assertFalse("lyria-002" in ids)
        assertFalse("veo-3.0" in ids)
        assertFalse("imagen-4.0" in ids)
        assertFalse("gemini-tts" in ids)
        assertFalse("gemini-2.5-live-no-bidi" in ids)
        assertTrue("gemini-2.5-flash-native-audio-preview" in ids)
        assertTrue(parsed.first { it.id == "gemini-2.5-flash-native-audio-preview" }.supports(CapabilityLane.LIVE_STT))

        // Stale cache entry (already persisted before denylist) is immediately purged by mergeModels.
        val staleCache = listOf(
            ModelDescriptor(
                id = "gemini-nano-banana-2.1",
                providerId = ProviderCapabilityRegistry.ID_GEMINI,
                displayName = "Nano Banana",
                lanes = setOf(CapabilityLane.BATCH_STT, CapabilityLane.CLEANUP),
                isLiveFetched = true,
            ),
            ModelDescriptor(
                id = "gemini-2.5-flash-native-audio-preview",
                providerId = ProviderCapabilityRegistry.ID_GEMINI,
                displayName = "Native Audio Live",
                lanes = setOf(CapabilityLane.LIVE_STT),
                isLiveFetched = true,
            ),
        )
        assertTrue(
            LiveModelCatalogStore.mergeModels("gemini", staleCache, CapabilityLane.BATCH_STT)
                .none { it.id == "gemini-nano-banana-2.1" }
        )
        assertTrue(
            LiveModelCatalogStore.mergeModels("gemini", staleCache, CapabilityLane.CLEANUP)
                .none { it.id == "gemini-nano-banana-2.1" }
        )
        assertTrue(
            LiveModelCatalogStore.mergeModels("gemini", staleCache, CapabilityLane.LIVE_STT)
                .any { it.id == "gemini-2.5-flash-native-audio-preview" }
        )
    }
}
