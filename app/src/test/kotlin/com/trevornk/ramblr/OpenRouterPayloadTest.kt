package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterPayloadTest {

    @Test fun `OpenRouter presetId includes provider allow_fallbacks false`() {
        val body = PostProcessor.buildRequestBody(
            text = "raw text",
            prompt = "clean it up",
            model = "openai/gpt-5.4-mini",
            baseUrl = "https://custom-proxy.example/v1",
            presetId = ProviderPresets.OPENROUTER.id,
        )
        assertTrue(body.has("provider"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
    }

    @Test fun `openrouter ai host includes provider allow_fallbacks false even without presetId`() {
        val body = PostProcessor.buildRequestBody(
            text = "raw text",
            prompt = "clean it up",
            model = "openai/gpt-5.4-mini",
            baseUrl = "https://openrouter.ai/api/v1",
            presetId = null,
        )
        assertTrue(body.has("provider"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
    }

    @Test fun `non-OpenRouter providers omit provider object`() {
        val cases = listOf(
            null to PostProcessor.DEFAULT_BASE_URL,
            ProviderPresets.GROQ.id to ProviderPresets.GROQ.baseUrl,
            null to OmniRoute.BASE_URL,
            null to "https://not-openrouter.ai/v1",
        )
        for ((presetId, baseUrl) in cases) {
            val body = PostProcessor.buildRequestBody(
                text = "raw text",
                prompt = "clean it up",
                model = "gpt-5.4-mini",
                baseUrl = baseUrl,
                presetId = presetId,
            )
            assertFalse("Expected no provider object for preset=$presetId url=$baseUrl", body.has("provider"))
        }
    }

    @Test fun `ProviderChainRuntime through CleanupWaterfallExecutor sends allow_fallbacks false to OpenRouter`() {
        val sentBodies = mutableListOf<JSONObject>()
        val transport = CleanupHttpTransport { _, _, jsonBody, _, _, callback ->
            sentBodies += JSONObject(jsonBody)
            callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
        }

        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(
                    kind = ProviderKind.OPENAI,
                    model = "openai/gpt-5.4-mini",
                    baseUrlOverride = ProviderPresets.OPENROUTER.baseUrl,
                    id = "or-entry",
                    presetId = ProviderPresets.OPENROUTER.id,
                ),
            ),
        )

        var result: PostProcessor.Result? = null
        PostProcessor.processProviderChain(
            text = "raw text",
            prompt = "clean it up",
            chain = chain,
            cursor = CleanupWaterfallCursor(),
            cancelHolder = InFlightCall(),
            credentialLookup = { "" },
            entryCredentialLookup = { "or-key" },
            transport = transport,
            callback = { result = it },
        )

        assertEquals("cleaned", result?.text)
        val sent = sentBodies.single()
        assertTrue(sent.has("provider"))
        assertFalse(sent.getJSONObject("provider").getBoolean("allow_fallbacks"))
    }
}
