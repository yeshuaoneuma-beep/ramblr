package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioJobRunnerTest {

    private lateinit var app: Application
    private lateinit var store: AudioJobStore
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_custom_1", "vault_custom_2")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
        AudioJobFiles.dir(app.filesDir).deleteRecursively()
        DictationHistoryStore.forContext(app).clear()
        store = AudioJobStore(File(app.filesDir, "audio_job_runner_test.jsonl").apply { delete() })
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.close()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        listOf("vault_gemini", "vault_openai", "vault_groq", "vault_custom_1", "vault_custom_2")
            .forEach { ProviderCredentialStore.clearVaultSecret(app, it) }
    }

    @Test fun `shouldRetryCloudChunk honors maxAttempts cap and never retries account-dead errors`() {
        assertTrue(AudioJobRunner.shouldRetryCloudChunk(0, CloudErrorKind.TEMPORARY_BUSY, hasNextRoute = false, maxAttempts = 3))
        assertTrue(AudioJobRunner.shouldRetryCloudChunk(1, CloudErrorKind.TEMPORARY_BUSY, hasNextRoute = false, maxAttempts = 3))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(2, CloudErrorKind.TEMPORARY_BUSY, hasNextRoute = false, maxAttempts = 3))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(0, CloudErrorKind.PERMANENT_OR_QUOTA, httpCode = 401, hasNextRoute = false, maxAttempts = 3))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(0, CloudErrorKind.PERMANENT_OR_QUOTA, httpCode = 402, hasNextRoute = false, maxAttempts = 3))
        assertFalse(AudioJobRunner.shouldRetryCloudChunk(0, CloudErrorKind.PERMANENT_OR_QUOTA, httpCode = 403, hasNextRoute = false, maxAttempts = 3))
    }

    @Test fun `vault credentials and account-dead 401 fast-skip work across Stage 1 STT routes and Stage 2 cleanup`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("use_local", false)
            .commit()
        DictationModeToggle.setAllowLocalFallback(app, false)
        PostProcessingToggle.setEnabled(app, true)
        CloudFeatureToggle.setCleanupEnabled(app, true)

        ProviderCredentialStore.setVaultSecret(app, "vault_gemini", "AIzaSyDeadVaultKey1234567890")
        ProviderCredentialStore.setVaultSecret(app, "vault_openai", "sk-live-openai-vault-key-12345")

        val base = server.url("/v1").toString().trimEnd('/')
        val gem1 = ProviderChainEntry(
            kind = ProviderKind.GEMINI,
            model = "gemini-3.5-flash-lite",
            baseUrlOverride = base,
            transcriptionModel = "gemini-3.5-transcribe",
            id = "e-gem-1",
            vaultKeyId = "vault_gemini",
            sttAttempts = 1,
            cleanupAttempts = 1,
        )
        val gem2 = ProviderChainEntry(
            kind = ProviderKind.GEMINI,
            model = "gemini-3.1-flash-lite",
            baseUrlOverride = base,
            transcriptionModel = "gemini-3.1-flash-lite",
            id = "e-gem-2",
            vaultKeyId = "vault_gemini",
            sttAttempts = 1,
            cleanupAttempts = 1,
        )
        val oai = ProviderChainEntry(
            kind = ProviderKind.OPENAI,
            model = "gpt-5.4-mini",
            baseUrlOverride = base,
            transcriptionModel = "gpt-transcribe",
            id = "e-oai-1",
            vaultKeyId = "vault_openai",
            sttAttempts = 1,
            cleanupAttempts = 1,
        )
        ProviderChainStore.save(app, ProviderChain(listOf(gem1, gem2, oai)))

        val hitTargets = Collections.synchronizedList(mutableListOf<String>())
        val hitAuths = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hitTargets += request.target
                hitAuths += (request.headers["x-goog-api-key"] ?: request.headers["Authorization"] ?: "")
                return when {
                    request.target.contains("gemini-") ->
                        MockResponse.Builder().code(401).body("""{"error":{"message":"bad gemini key"}}""").build()
                    request.target.contains("/audio/transcriptions") ->
                        MockResponse.Builder().body("""{"text":"raw vault audio transcript"}""").build()
                    request.target.contains("/chat/completions") ->
                        MockResponse.Builder().body("""{"choices":[{"message":{"content":"Cleaned vault audio transcript."}}]}""").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val jobId = "job-vault-401-skip"
        AudioJobFiles.sourceFile(app.filesDir, jobId).apply { parentFile?.mkdirs(); writeBytes(ByteArray(64) { 1 }) }
        val job = AudioJob(jobId, "vault.m4a", JobKind.IMPORTED, JobStatus.QUEUED, 1L, hasLocalAudio = true)
        store.upsert(job)

        val runner = AudioJobRunner(
            context = app,
            store = store,
            isCancelled = { false },
            onStatus = {},
            decodeAudio = { _, pcm, _, _ ->
                pcm.writeBytes(ByteArray(32_000))
                AudioFileDecoder.Result(pcmBytes = 32_000L, durationMs = 1_000L)
            },
        )
        runner.run(job)

        assertEquals(JobStatus.DONE, store.get(jobId)?.status)
        val history = DictationHistoryStore.forContext(app).all().single()
        assertEquals("raw vault audio transcript", history.rawText)
        assertEquals("Cleaned vault audio transcript.", history.cleanedText)
        // STT: gem1 (401) -> gem2 skipped! -> oai (200)
        // Cleanup: gem1 (401) -> gem2 skipped! -> oai (200)
        assertEquals("gem2 must be fast-skipped in both STT and Cleanup", 0, hitTargets.count { it.contains("gemini-3.1-flash-lite") })
        assertEquals(4, hitTargets.size)
        assertTrue(hitAuths.contains("AIzaSyDeadVaultKey1234567890"))
        assertTrue(hitAuths.contains("Bearer sk-live-openai-vault-key-12345"))
    }
}
