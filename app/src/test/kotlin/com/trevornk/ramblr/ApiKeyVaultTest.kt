package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyVaultTest {

    @Test fun `validator sanitizes invisible chars and quotes and never hard-blocks provider prefixes or length`() {
        assertEquals("AQ.Ab8RN6L-test123", ApiKeyVaultValidator.sanitize(" \u200B\"AQ.Ab8RN6L-test123\"\r\n\uFEFF "))
        assertEquals("gsk_abc123", ApiKeyVaultValidator.sanitize("'gsk_abc123'"))

        // Blank, internal whitespace, or bad Custom OpenAI base URL are the ONLY hard blocks.
        assertFalse(ApiKeyVaultValidator.validate("gemini", "   \u200B\n ").isValid)
        assertFalse(ApiKeyVaultValidator.validate("gemini", "AIzaSyA12345 678901234567890").isValid)
        assertFalse(ApiKeyVaultValidator.validate("custom_openai", "my-token", customBaseUrl = "").isValid)
        assertFalse(ApiKeyVaultValidator.validate("custom_openai", "my-token", customBaseUrl = "ftp://bad.host/v1").isValid)

        // All provider keys (including AQ. Google Cloud/Vertex Express, short keys, non-standard prefixes) pass!
        val aqCheck = ApiKeyVaultValidator.validate("gemini", "AQ.Ab8RN6L_VertexExpressKey123")
        assertTrue(aqCheck.isValid)
        assertEquals(null, aqCheck.softWarning)

        val unusualGemini = ApiKeyVaultValidator.validate("gemini", "custom-proxy-key")
        assertTrue(unusualGemini.isValid)
        assertNotNull(unusualGemini.softWarning)

        assertTrue(ApiKeyVaultValidator.validate("gemini", "AIzaSyA12345678901234567890").isValid)
        assertTrue(ApiKeyVaultValidator.validate("groq", "gsk_12345678901234567890").isValid)
        assertTrue(ApiKeyVaultValidator.validate("groq", "short-key").isValid)
        assertTrue(ApiKeyVaultValidator.validate("openrouter", "sk-or-v1-123456789012345678").isValid)
        assertTrue(ApiKeyVaultValidator.validate("openrouter", "or-key").isValid)
        assertTrue(ApiKeyVaultValidator.validate("openai", "sk-proj-123456789012345678").isValid)
        assertTrue(ApiKeyVaultValidator.validate("anthropic", "sk-ant-api03-1234567890123456").isValid)
        assertTrue(ApiKeyVaultValidator.validate("custom_openai", "my-token", customBaseUrl = "https://gateway.local/v1").isValid)
    }

    @Test fun `KeyGateNormalizer unlocks keyless batch cards and disables keyless live config`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(
                    kind = ProviderKind.GEMINI,
                    model = "gemini-3.5-flash-lite",
                    transcriptionModel = "gemini-3.5-transcribe",
                    id = "g1",
                    providerLocked = true,
                    sttLocked = true,
                    cleanupLocked = true,
                    sttAttempts = 2,
                    cleanupAttempts = 2,
                ),
                ProviderChainEntry(
                    kind = ProviderKind.OPENAI,
                    model = "openai/gpt-oss-20b",
                    transcriptionModel = "whisper-large-v3-turbo",
                    baseUrlOverride = ProviderPresets.GROQ.baseUrl,
                    presetId = ProviderPresets.GROQ.id,
                    id = "gr1",
                    providerLocked = true,
                    sttLocked = true,
                    cleanupLocked = true,
                ),
            )
        )
        val normalizedChain = KeyGateNormalizer.normalizeChain(chain) { it.id == "gr1" }
        val g1 = normalizedChain.entries[0]
        assertFalse(g1.providerLocked)
        assertFalse(g1.sttLocked)
        assertFalse(g1.cleanupLocked)
        assertFalse(g1.useForTranscription)
        assertFalse(g1.useForCleanup)
        assertEquals("gemini-3.5-transcribe", g1.transcriptionModel)
        assertEquals(2, g1.sttAttempts)

        val gr1 = normalizedChain.entries[1]
        assertTrue(gr1.providerLocked)
        assertTrue(gr1.isBatchSttActive())
        assertTrue(gr1.isBatchCleanupActive())

        val live = LiveTranscriptionConfig(
            enabled = true,
            providerLocked = true,
            modelLocked = true,
            postCleanupEnabled = true,
            postCleanupSlots = listOf(
                LivePostCleanupSlot("s1", "groq", "openai/gpt-oss-20b", locked = true),
                LivePostCleanupSlot("s2", "openai", "gpt-5.6-luna", locked = true),
            ),
        )
        val normalizedLive = KeyGateNormalizer.normalizeLiveConfig(
            config = live,
            hasGeminiKey = false,
            hasSlotKey = { it.id == "s1" },
        )
        assertFalse(normalizedLive.enabled)
        assertFalse(normalizedLive.providerLocked)
        assertFalse(normalizedLive.modelLocked)
        assertTrue(normalizedLive.postCleanupSlots[0].locked)
        assertFalse(normalizedLive.postCleanupSlots[1].locked)
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
        assertFalse(detachedLive.enabled)
        assertFalse(detachedLive.providerLocked)
        assertFalse(detachedLive.modelLocked)
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
