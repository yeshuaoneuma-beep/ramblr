package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetedTranscriptionWalkTest {

    private val gemini1 = ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", id = "g1", transcriptionModel = "gemini-3.5-transcribe", vaultKeyId = "vault_gemini")
    private val gemini2 = ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", id = "g2", transcriptionModel = "gemini-3.1-flash-lite", vaultKeyId = "vault_gemini")
    private val groq = ProviderChainEntry(ProviderKind.OPENAI, "openai/gpt-oss-20b", id = "gr1", transcriptionModel = "whisper-large-v3-turbo", presetId = "groq", vaultKeyId = "vault_groq")
    private val local = ProviderChainEntry(ProviderKind.LOCAL, "local-stt", id = "local")

    @Test fun `single cloud slot with 3x cap retries up to 3 attempts even when local fallback exists`() {
        val walk = TranscriptionChain.Walk(
            cloudCandidates = listOf(gemini1),
            localCandidate = local,
            startedAtMs = 0L,
            isCompressedUpload = true,
            slotAttemptCaps = listOf(3),
            slotAccountKeys = listOf("vault_gemini"),
        )

        val s1 = walk.nextStep(0L) as TranscriptionChain.Step.RunCloud
        assertEquals(1, s1.attemptNumber)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503", httpCode = 503)

        val s2 = walk.nextStep(1_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(2, s2.attemptNumber)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503", httpCode = 503)

        val s3 = walk.nextStep(2_000L) as TranscriptionChain.Step.RunCloud
        assertEquals(3, s3.attemptNumber)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503", httpCode = 503)

        val s4 = walk.nextStep(3_000L)
        assertTrue("Expected RunLocal after 3 attempts, got $s4", s4 is TranscriptionChain.Step.RunLocal)
    }

    @Test fun `2x + 1x split honors exact per-slot attempts and quick 429`() {
        val walk = TranscriptionChain.Walk(
            cloudCandidates = listOf(gemini1, groq),
            localCandidate = local,
            startedAtMs = 0L,
            isCompressedUpload = true,
            slotAttemptCaps = listOf(2, 1),
            slotAccountKeys = listOf("vault_gemini", "vault_groq"),
        )

        val s1 = walk.nextStep(0L) as TranscriptionChain.Step.RunCloud
        assertEquals("g1", s1.entry.id)
        assertEquals(1, s1.attemptNumber)
        walk.recordCloudFailure(CloudErrorKind.PERMANENT_OR_QUOTA, "429", httpCode = 429, retryAfterMs = 1_500L)

        val s2 = walk.nextStep(1_000L) as TranscriptionChain.Step.RunCloud
        assertEquals("g1", s2.entry.id)
        assertEquals(2, s2.attemptNumber)
        assertEquals(1_500L, s2.backoffMs)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503", httpCode = 503)

        val s3 = walk.nextStep(3_000L) as TranscriptionChain.Step.RunCloud
        assertEquals("gr1", s3.entry.id)
        assertEquals(1, s3.attemptNumber)
        walk.recordCloudFailure(CloudErrorKind.TEMPORARY_BUSY, "503", httpCode = 503)

        val s4 = walk.nextStep(4_000L)
        assertTrue(s4 is TranscriptionChain.Step.RunLocal)
    }

    @Test fun `401 402 403 immediately disqualifies all remaining slots sharing the same account key`() {
        for (code in listOf(401, 402, 403)) {
            assertTrue(CloudErrorClassifier.isAccountDeadError(code))
            val walk = TranscriptionChain.Walk(
                cloudCandidates = listOf(gemini1, gemini2, groq),
                localCandidate = local,
                startedAtMs = 0L,
                isCompressedUpload = true,
                slotAttemptCaps = listOf(1, 1, 1),
                slotAccountKeys = listOf("vault_gemini", "vault_gemini", "vault_groq"),
            )

            val s1 = walk.nextStep(0L) as TranscriptionChain.Step.RunCloud
            assertEquals("g1", s1.entry.id)
            walk.recordCloudFailure(CloudErrorKind.PERMANENT_OR_QUOTA, "HTTP $code", httpCode = code)

            // g2 shares "vault_gemini" -> skipped immediately, advancing directly to groq!
            val s2 = walk.nextStep(1_000L) as TranscriptionChain.Step.RunCloud
            assertEquals("gr1", s2.entry.id)
        }
        assertFalse(CloudErrorClassifier.isAccountDeadError(429))
        assertFalse(CloudErrorClassifier.isAccountDeadError(503))
        assertFalse(CloudErrorClassifier.isAccountDeadError(null))
    }

    @Test fun `DNS fast-fail does not consume extra same-slot attempts`() {
        val walk = TranscriptionChain.Walk(
            cloudCandidates = listOf(gemini1, groq),
            localCandidate = local,
            startedAtMs = 0L,
            isCompressedUpload = true,
            slotAttemptCaps = listOf(2, 1),
            slotAccountKeys = listOf("vault_gemini", "vault_groq"),
        )

        val s1 = walk.nextStep(0L) as TranscriptionChain.Step.RunCloud
        assertEquals("g1", s1.entry.id)
        walk.recordCloudFailure(
            CloudErrorClassifier.classify(httpCode = null, errorText = "Unable to resolve host", isTransientIo = false),
            "Unable to resolve host",
            httpCode = null,
        )

        val s2 = walk.nextStep(500L) as TranscriptionChain.Step.RunCloud
        assertEquals("gr1", s2.entry.id)
    }

    @Test fun `ProviderChainMigration never seeds transcriptionModel when useForTranscription is false or baseUrlOverride is set`() {
        val cleanupOnly = ProviderChainEntry(
            kind = ProviderKind.OPENAI,
            model = "gpt-5.6-luna",
            transcriptionModel = null,
            useForTranscription = false,
            useForCleanup = true,
        )
        val customUrl = ProviderChainEntry(
            kind = ProviderKind.OPENAI,
            model = "custom-chat",
            baseUrlOverride = "https://proxy.example/v1",
            transcriptionModel = null,
            useForTranscription = true,
            useForCleanup = true,
        )
        val migrated = ProviderChainMigration.migrate(ProviderChain(listOf(cleanupOnly, customUrl)))
        assertEquals(null, migrated.entries[0].transcriptionModel)
        assertEquals(null, migrated.entries[1].transcriptionModel)
    }
}
