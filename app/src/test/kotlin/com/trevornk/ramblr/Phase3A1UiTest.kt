package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3A1UiTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        clearVaultAndPrefs()
    }

    @After fun tearDown() {
        clearVaultAndPrefs()
    }

    private fun clearVaultAndPrefs() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        ProviderCapabilityRegistry.allRegistered().forEach {
            ProviderCredentialStore.clearVaultSecret(app, ProviderCapabilityRegistry.canonicalVaultKeyId(it.id))
        }
        ProviderCredentialStore.clearVaultSecret(app, "vault_custom_test")
    }

    private fun View.allTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }

    @Test fun `ApiKeysVaultActivity renders registry providers without retries wording or dead engines`() {
        val activity = Robolectric.buildActivity(ApiKeysVaultActivity::class.java).setup().get()
        val texts = activity.window.decorView.allTexts()
        assertTrue(texts.any { it == "API keys" })
        assertTrue(texts.any { it == "Gemini" })
        assertTrue(texts.any { it == "Groq" })
        assertTrue(texts.any { it == "OpenRouter" })
        assertTrue(texts.any { it == "OpenAI" })
        assertTrue(texts.any { it == "Anthropic" })
        assertTrue(texts.any { it.contains("Add Custom OpenAI-Compatible") })
        assertFalse(texts.any { "hugging" in it.lowercase() || "deepgram" in it.lowercase() || "retries" in it.lowercase() })
    }

    @Test fun `LiveTranscriptionActivity renders 9-00 guard, smart disconnect, post-cleanup and zero dead streaming buttons`() {
        val activity = Robolectric.buildActivity(LiveTranscriptionActivity::class.java).setup().get()
        val texts = activity.window.decorView.allTexts()
        assertTrue(texts.any { it == "Live transcription" })
        assertTrue(texts.any { it.contains("9:00 live cutoff guard") })
        assertTrue(texts.any { it.contains("Smart Live Disconnect Alert") })
        assertTrue(texts.any { it.contains("Post-Cleanup After Live") })
        assertTrue(texts.any { it.contains("Gemini 3.5 Transcribe Live") })
        assertFalse(texts.any { "deepgram" in it.lowercase() })
        assertFalse(texts.any { "sherpa" in it.lowercase() })
        assertFalse(texts.any { "translate-preview" in it.lowercase() })
        assertFalse(texts.any { "retries" in it.lowercase() })
    }

    @Test fun `CloudProviderActivity renders vault, execution order, unified cards and never mentions retries`() {
        val activity = Robolectric.buildActivity(CloudProviderActivity::class.java).setup().get()
        val texts = activity.window.decorView.allTexts()
        assertTrue(texts.any { it == "Cloud providers" })
        assertTrue(texts.any { it == "API keys" })
        assertTrue(texts.any { it == "Execution order" })
        assertTrue(texts.any { it == "+ Add provider" })
        assertFalse(texts.any { "retries" in it.lowercase() })
    }

    @Test fun `ProviderCredentialStore transparently resolves shared vault keys via entry, entryId, and getForKind`() {
        val geminiVaultId = ProviderCapabilityRegistry.canonicalVaultKeyId("gemini")
        ApiKeyVaultStore.upsert(
            app,
            ApiKeyVaultEntry(geminiVaultId, "gemini", "Gemini", locked = true),
            secret = "AIzaSharedGeminiVaultSecret12345",
        )

        // getForKind finds shared Gemini vault key even when batch chain has NO Gemini card!
        val openaiOnlyChain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", id = "o1")))
        assertEquals(
            "AIzaSharedGeminiVaultSecret12345",
            ProviderCredentialStore.getForKind(app, openaiOnlyChain, ProviderKind.GEMINI),
        )

        // entryId lookup resolves linked vaultKeyId from ProviderChainStore!
        val geminiEntry = ProviderChainEntry(
            kind = ProviderKind.GEMINI,
            model = "gemini-3.5-flash-lite",
            id = "gem-card-1",
            vaultKeyId = geminiVaultId,
        )
        ProviderChainStore.save(app, ProviderChain(listOf(geminiEntry)))
        assertEquals("AIzaSharedGeminiVaultSecret12345", ProviderCredentialStore.get(app, geminiEntry))
        assertEquals("AIzaSharedGeminiVaultSecret12345", ProviderCredentialStore.get(app, "gem-card-1"))
        assertTrue(ProviderCredentialStore.isConfigured(app, "gem-card-1"))
    }
}
