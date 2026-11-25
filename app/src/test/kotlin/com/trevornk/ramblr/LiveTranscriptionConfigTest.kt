package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptionConfigTest {

    @Test fun `defaults match Phase 3A-1 contract`() {
        val cfg = LiveTranscriptionConfig()
        assertEquals("gemini-3.5-transcribe-live", LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID)
        assertEquals("gemini-3.8-live", LiveTranscriptionConfig.SECONDARY_LIVE_MODEL_ID)
        assertEquals(540, LiveTranscriptionConfig.LIVE_CUTOFF_GUARD_SEC)
        assertEquals(3, LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS)
        assertEquals(LiveTranscriptionMode.VERBATIM, cfg.mode)
        assertTrue(cfg.supportsModeToggle())
        assertFalse(cfg.postCleanupEnabled)
        assertTrue(cfg.smartDisconnectAlertEnabled)
        assertTrue(cfg.smartDisconnectHapticEnabled)
    }

    @Test fun `serialization round-trips and enforces 3-slot cap and translate-preview exclusion`() {
        val cfg = LiveTranscriptionConfig(
            enabled = true,
            providerId = "gemini",
            modelId = LiveTranscriptionConfig.SECONDARY_LIVE_MODEL_ID,
            vaultKeyId = "vault_gemini",
            providerLocked = true,
            modelLocked = true,
            mode = LiveTranscriptionMode.SMART,
            postCleanupEnabled = true,
            postCleanupSlots = listOf(
                LivePostCleanupSlot("s1", "gemini", "gemini-3.5-flash-lite", "vault_gemini", null, attempts = 2, locked = true),
                LivePostCleanupSlot("s2", "groq", "openai/gpt-oss-20b", "vault_groq", null, attempts = 1, locked = true),
                LivePostCleanupSlot("s3", "openai", "gpt-5.6-luna", "vault_openai", null, attempts = 1, locked = false),
                LivePostCleanupSlot("s4", "anthropic", "claude-haiku-4-5-20251001", "vault_anthropic", null, attempts = 1, locked = true),
            ),
            smartDisconnectAlertEnabled = true,
            smartDisconnectHapticEnabled = false,
        )

        val parsed = LiveTranscriptionConfigStore.deserialize(LiveTranscriptionConfigStore.serialize(cfg))!!
        assertEquals(true, parsed.enabled)
        assertEquals(LiveTranscriptionConfig.SECONDARY_LIVE_MODEL_ID, parsed.modelId)
        assertFalse(parsed.supportsModeToggle())
        assertEquals(LiveTranscriptionMode.SMART, parsed.mode)
        assertEquals(3, parsed.postCleanupSlots.size)
        assertFalse(parsed.smartDisconnectHapticEnabled)

        // Translate preview model is strictly sanitized back to DEFAULT_LIVE_MODEL_ID.
        val forbiddenRaw = """{"enabled":true,"providerId":"gemini","modelId":"gemini-3.5-live-translate-preview"}"""
        val sanitized = LiveTranscriptionConfigStore.deserialize(forbiddenRaw)!!
        assertEquals(LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID, sanitized.modelId)
    }

    @Test fun `livePostCleanupWaterfallFor and execution order strip respect toggle and 3-attempt budget`() {
        val configOff = LiveTranscriptionConfig(
            postCleanupEnabled = false,
            postCleanupSlots = listOf(
                LivePostCleanupSlot("s1", "gemini", "gemini-3.5-flash-lite", "vault_gemini", attempts = 2, locked = true),
            ),
        )
        assertTrue(ProviderChainRuntime.livePostCleanupWaterfallFor(configOff).steps.isEmpty())
        assertEquals("Direct live text (Post-Cleanup off)", EffectiveRouting.livePostCleanupExecutionOrder(configOff))

        val configOn = configOff.copy(
            postCleanupEnabled = true,
            postCleanupSlots = listOf(
                LivePostCleanupSlot("s1", "gemini", "gemini-3.5-flash-lite", "vault_gemini", attempts = 2, locked = true),
                LivePostCleanupSlot("s2", "groq", "openai/gpt-oss-20b", "vault_groq", attempts = 1, locked = true),
            ),
        )
        val waterfall = ProviderChainRuntime.livePostCleanupWaterfallFor(configOn)
        assertEquals(2, waterfall.steps.size)
        assertEquals(CleanupStepGroup.GEMINI_DIRECT, waterfall.steps[0].group)
        assertEquals(2, waterfall.steps[0].maxAttempts)
        assertEquals("vault_gemini", waterfall.steps[0].vaultKeyId)
        assertEquals(CleanupStepGroup.OPENAI_DIRECT, waterfall.steps[1].group)
        assertEquals(1, waterfall.steps[1].maxAttempts)
        assertEquals(ProviderPresets.GROQ.baseUrl, waterfall.steps[1].baseUrlOverride)

        assertEquals(
            "Gemini (2x) \u2192 Groq (1x) \u2192 Raw live text",
            EffectiveRouting.livePostCleanupExecutionOrder(configOn),
        )
    }
}
