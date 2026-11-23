package com.trevornk.ramblr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native client for Gemini's `generateContent` REST API, used for the GEMINI cleanup provider
 * (#96). Gemini's wire format is not OpenAI-compatible (no `/chat/completions` endpoint, a
 * `contents`/`parts` request shape instead of `messages`, the API key passed as a query
 * parameter rather than an Authorization header, and a `candidates[].content.parts[].text`
 * response shape), so it can't reuse [PostProcessor]'s chat-completions request/response code --
 * mirrors [AnthropicCleanupProvider]'s reasoning for the same kind of divergence.
 *
 * Deliberately covers only a single-turn systemInstruction+user completion, which is all the
 * cleanup waterfall needs -- no streaming (per the capability facts already documented in
 * [ProviderChain]'s kdoc), no tool use, no multi-turn history, no thinking-config tuning.
 */
object GeminiCleanupProvider {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    /** [model] is bare (e.g. "gemini-2.5-flash"), though any leading "models/" prefix is stripped so
     *  resource names like "models/gemma-4-31b-it" never double up into `/models/models/...`. No
     *  `?key=` query string: the key travels in the [headers] below instead, so it can't leak
     *  through a malformed-URL exception message or a logged URL (M-3). */
    fun endpointUrl(model: String, baseUrl: String = BASE_URL): String {
        val normalizedModel = model.trim()
            .replace(MODELS_PREFIX_REGEX, "")
            .ifBlank { DEFAULT_MODEL }
        return "${PostProcessor.normalizeBaseUrl(baseUrl) ?: BASE_URL}/models/$normalizedModel:generateContent"
    }

    private val MODELS_PREFIX_REGEX = Regex("^(?:models/)+", RegexOption.IGNORE_CASE)

    /** Gemini accepts the API key in an `x-goog-api-key` header as an alternative to the `?key=`
     *  query parameter (https://ai.google.dev/gemini-api/docs/api-key). Using the header keeps the
     *  key out of the URL entirely, mirroring [AnthropicCleanupProvider.headers]'s `x-api-key`. */
    fun headers(key: String): Map<String, String> = mapOf("x-goog-api-key" to key)

    // gemini-3.1-flash-lite -> gemini-3.5-flash-lite (2026-08-25): 3.5-flash-lite (GA July 2026)
    // is the current cheapest Gemini tier and the catalog's RECOMMENDED cleanup pick. CLEANUP
    // ONLY: [GeminiTranscriberClient.DEFAULT_MODEL] deliberately stays gemini-3.1-flash-lite --
    // 3.5-flash-lite measures ~3x slower on audio input, so the audio path keeps the faster
    // model on purpose (see ModelCatalogEntry's Gemini section).
    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    private fun normalizedModelId(model: String): String =
        model.trim().removePrefix("models/").lowercase()

    fun isGemma4(model: String): Boolean = normalizedModelId(model).startsWith("gemma-4-")

    fun isGemini38(model: String): Boolean = normalizedModelId(model).startsWith("gemini-3.8-")

    fun thinkingLevelFor(model: String): String? = when {
        isGemma4(model) -> "MINIMAL"
        isGemini38(model) -> "LOW"
        else -> null
    }

    fun rejectsTemperature(model: String): Boolean = isGemini38(model)

    /**
     * Builds the generateContent request body: [prompt] as `systemInstruction`, [text] as the
     * single user `contents` entry. `temperature: 0.0` mirrors [PostProcessor.buildRequestBody]/
     * [AnthropicCleanupProvider.buildRequestBody]'s deterministic-cleanup intent.
     */
    fun buildRequestBody(text: String, prompt: String, model: String = DEFAULT_MODEL): JSONObject {
        val effectiveModel = model.ifBlank { DEFAULT_MODEL }
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
        }
        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().apply { put("text", text) }))
        })
        val generationConfig = JSONObject().apply {
            if (!rejectsTemperature(effectiveModel)) {
                put("temperature", 0.0)
            }
            thinkingLevelFor(effectiveModel)?.let { level ->
                put("thinkingConfig", JSONObject().put("thinkingLevel", level))
            }
        }
        return JSONObject().apply {
            put("systemInstruction", systemInstruction)
            put("contents", contents)
            put("generationConfig", generationConfig)
        }
    }

    /**
     * Parses a `generateContent` response: `candidates[0].content.parts[].text`, concatenated in
     * case the model splits its answer across multiple parts. Gemini error responses come back
     * as a top-level `{"error": {"message": ...}}` envelope, same shape [PostProcessor] and
     * [AnthropicCleanupProvider] already handle.
     */
    fun parseResponse(json: String): PostProcessor.Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("candidates")) {
                val candidates = obj.getJSONArray("candidates")
                if (candidates.length() == 0) {
                    return PostProcessor.Result(null, "No candidates in response")
                }
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.let { arr ->
                    (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it) }
                        .filter { !it.optBoolean("thought", false) && !it.isNull("text") }
                        .joinToString("") { it.optString("text", "") }
                }
                if (!text.isNullOrBlank()) {
                    PostProcessor.Result(text.trim(), null)
                } else {
                    PostProcessor.Result(null, "No text content in response")
                }
            } else if (obj.has("error")) {
                PostProcessor.Result(null, obj.getJSONObject("error").optString("message", "Unknown Gemini error"))
            } else {
                PostProcessor.Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            PostProcessor.Result(null, e.message ?: "Parse error")
        }
    }
}
