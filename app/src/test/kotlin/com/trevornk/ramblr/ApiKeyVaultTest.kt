package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyVaultTest {

    @Test fun `validator checks provider prefixes whitespace and custom base URL`() {
        assertTrue(ApiKeyVaultValidator.validate("gemini", "AIzaSyA12345678901234567890").isValid)
        assertFalse(ApiKeyVaultValidator.validate("gemini", "sk-wrong-prefix-1234567890").isValid)
        assertFalse(ApiKeyVaultValidator.validate("gemini", "AIzaSyA12345 678901234567890").isValid)

        assertTrue(ApiKeyVaultValidator.validate("groq", "gsk_12345678901234567890").isValid)
        assertFalse(ApiKeyVaultValidator.validate("groq", "sk-12345678901234567890").isValid)

        assertTrue(ApiKeyVaultValidator.validate("openrouter", "sk-or-v1-123456789012345678").isValid)
        assertFalse(ApiKeyVaultValidator.validate("openrouter", "sk-12345678901234567890").isValid)

        assertTrue(ApiKeyVaultValidator.validate("openai", "sk-proj-123456789012345678").isValid)
        assertTrue(ApiKeyVaultValidator.validate("anthropic", "sk-ant-api03-1234567890123456").isValid)

        assertFalse(ApiKeyVaultValidator.validate("custom_openai", "my-token", customBaseUrl = "").isValid)
        assertFalse(ApiKeyVaultValidator.validate("custom_openai", "my-token", customBaseUrl = "ftp://bad.host/v1").isValid)
        assertTrue(ApiKeyVaultValidator.validate("custom_openai", "my-token", customBaseUrl = "https://gateway.local/v1").isValid)
    }

    @Test fun `usage scanner finds usages across Batch STT, Batch Cleanup, Live STT, and Live Post-Cleanup`() {
        val geminiVaultId = ProviderCapabilityRegistry.canonicalVaultKeyId("gemini")
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(
                    kind = ProviderKind.GEMINI,
                    model = "gemini-3.5-flash-lite",
                    transcriptionModel = "gemini-3.5-transcribe",
                    id = "e1",
                    vaultKeyId = geminiVaultId,
                    providerLocked = true,
                    sttLocked = true,
                    cleanupLocked = true,
                ),
            )
        )
        val liveConfig = LiveTranscriptionConfig(
            enabled = true,
            providerId = "gemini",
            modelId = LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID,
            vaultKeyId = geminiVaultId,
            providerLocked = true,
            modelLocked = true,
            postCleanupEnabled = true,
            postCleanupSlots = listOf(
                LivePostCleanupSlot("s1", "gemini", "gemini-3.5-flash-lite", vaultKeyId = geminiVaultId, attempts = 1, locked = true),
            ),
        )

        val usages = ApiKeyUsageScanner.usagesOf(geminiVaultId, chain, liveConfig)
        val areas = usages.map { it.area }.toSet()
        assertEquals(setOf("Batch STT", "Batch Cleanup", "Live STT", "Live Post-Cleanup"), areas)

        val detachedChain = ApiKeyUsageScanner.detachFromChain(geminiVaultId, chain)
        assertFalse(detachedChain.entries.single().isBatchSttActive())
        assertFalse(detachedChain.entries.single().isBatchCleanupActive())

        val detachedLive = ApiKeyUsageScanner.detachFromLiveConfig(geminiVaultId, liveConfig)
        assertFalse(detachedLive.providerLocked)
        assertFalse(detachedLive.postCleanupSlots.single().locked)
    }

    @Test fun `vault migration preserves distinct same-kind secrets and parks overflow cards unlocked`() {
        val entries = listOf(
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", id = "o1", transcriptionModel = "gpt-transcribe"),
            ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", id = "o2", transcriptionModel = "gpt-transcribe"),
            ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", id = "g1", transcriptionModel = "gemini-3.1-flash-lite"),
            ProviderChainEntry(ProviderKind.OPENAI, "whisper-large-v3-turbo", id = "gr1", baseUrlOverride = ProviderPresets.GROQ.baseUrl, presetId = "groq", transcriptionModel = "whisper-large-v3-turbo"),
            ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5-20251001", id = "a1"),
        )

        val res = ApiKeyVaultMigration.migratePure(
            chain = ProviderChain(entries),
            existingVault = emptyList(),
            existingVaultSecrets = emptyMap(),
            entrySecrets = mapOf(
                "o1" to "sk-openai-account-1-12345678",
                "o2" to "sk-openai-account-2-87654321",
                "g1" to "AIzaGeminiKey123456789012345",
                "gr1" to "gsk_GroqKey12345678901234567",
                "a1" to "sk-ant-Anthropic123456789012",
            ),
            legacyKindSecrets = emptyMap(),
            cloudLiveEnabled = true,
            hasPersistedLiveConfig = false,
        )

        // Same-kind distinct secrets preserved in two separate vault slots!
        val o1VaultId = res.chain.entries[0].vaultKeyId
        val o2VaultId = res.chain.entries[1].vaultKeyId
        assertNotNull(o1VaultId)
        assertNotNull(o2VaultId)
        assertTrue(o1VaultId != o2VaultId)
        assertEquals("sk-openai-account-1-12345678", res.secretWrites[o1VaultId])
        assertEquals("sk-openai-account-2-87654321", res.secretWrites[o2VaultId])

        // Overflow cards #4..#5 parked unlocked without deleting them!
        assertEquals(5, res.chain.entries.size)
        val activeStt = res.chain.entries.filter { it.isBatchSttActive() }
        val activeCleanup = res.chain.entries.filter { it.isBatchCleanupActive() }
        assertEquals(3, activeStt.size)
        assertEquals(3, activeCleanup.size)
        assertFalse(res.chain.entries[3].isBatchSttActive())
        assertFalse(res.chain.entries[3].isBatchCleanupActive())
        assertFalse(res.chain.entries[4].isBatchCleanupActive())

        // Live config seeded from CloudLiveToggle
        assertNotNull(res.seededLiveConfig)
        assertTrue(res.seededLiveConfig!!.enabled)
        assertEquals(LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID, res.seededLiveConfig!!.modelId)

        // Idempotent on second pass
        val second = ApiKeyVaultMigration.migratePure(
            chain = res.chain,
            existingVault = res.vaultEntries,
            existingVaultSecrets = res.secretWrites,
            entrySecrets = emptyMap(),
            legacyKindSecrets = emptyMap(),
            cloudLiveEnabled = true,
            hasPersistedLiveConfig = true,
        )
        assertEquals(res.chain, second.chain)
        assertEquals(res.vaultEntries, second.vaultEntries)
    }

    @Test fun `vault metadata round-trips through JSON serialization`() {
        val entries = listOf(
            ApiKeyVaultEntry("vault_gemini", "gemini", "Gemini", null, locked = true),
            ApiKeyVaultEntry("vault_custom_1", "custom_openai", "Local Proxy", "https://proxy.local/v1", locked = false),
        )
        assertEquals(entries, ApiKeyVaultStore.deserialize(ApiKeyVaultStore.serialize(entries)))
    }
}
