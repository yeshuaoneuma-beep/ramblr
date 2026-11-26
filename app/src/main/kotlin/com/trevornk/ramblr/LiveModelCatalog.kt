package com.trevornk.ramblr

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure per-provider parser for live `/models` API payloads.
 *
 * Resilient per-item parsing: malformed individual entries are skipped without rejecting the
 * entire catalog. Every parsed model is strictly filtered through [ModelCapabilityClassifier].
 */
object ProviderModelsParser {

    fun parse(providerId: String, rawJson: String): List<ModelDescriptor> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            val root = JSONObject(rawJson)
            when (providerId) {
                ProviderCapabilityRegistry.ID_OPENROUTER -> parseOpenRouter(root)
                ProviderCapabilityRegistry.ID_GEMINI -> parseGemini(root)
                ProviderCapabilityRegistry.ID_ANTHROPIC -> parseAnthropic(root)
                else -> parseOpenAiStyle(providerId, root)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseOpenRouter(root: JSONObject): List<ModelDescriptor> {
        val array = root.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<ModelDescriptor>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            val name = obj.optString("name", "").trim().ifEmpty { id }
            val description = obj.optString("description", "").trim()
            val contextLength = obj.optInt("context_length", 0).takeIf { it > 0 }

            val pricing = obj.optJSONObject("pricing")
            val promptPer1M = parsePerTokenToPer1M(pricing?.opt("prompt"))
            val completionPer1M = parsePerTokenToPer1M(pricing?.opt("completion"))

            val arch = obj.optJSONObject("architecture")
            val modalities = parseStringSet(arch?.optJSONArray("input_modalities"))
            // Only pass modalities when the field is actually present so models missing "audio"
            // in input_modalities are strictly blocked from BATCH_STT.
            val hasModalitiesField = arch != null && arch.has("input_modalities")
            val lanes = ModelCapabilityClassifier.classifyLanes(
                providerId = ProviderCapabilityRegistry.ID_OPENROUTER,
                modelId = id,
                inputModalities = if (hasModalitiesField) modalities else emptySet(),
            )
            if (lanes.isEmpty()) continue

            val curated = ModelCapabilityClassifier.curatedModelById(ProviderCapabilityRegistry.ID_OPENROUTER, id)
            val badges = buildList {
                if (CapabilityLane.BATCH_STT in lanes) add("Audio STT")
                if (curated != null) curated.badges.forEach { if (it !in this) add(it) }
            }
            result += ModelDescriptor(
                id = id,
                providerId = ProviderCapabilityRegistry.ID_OPENROUTER,
                displayName = curated?.displayName ?: name,
                description = curated?.description?.ifBlank { description } ?: description,
                lanes = lanes,
                tier = curated?.tier ?: ModelTier.GOOD,
                costPer1MInputUsd = promptPer1M ?: curated?.costPer1MInputUsd,
                costPer1MOutputUsd = completionPer1M ?: curated?.costPer1MOutputUsd,
                contextLength = contextLength ?: curated?.contextLength,
                inputModalities = modalities,
                isNonThinking = curated?.isNonThinking ?: false,
                supportsLiveModeToggle = curated?.supportsLiveModeToggle ?: false,
                badges = badges,
                isLiveFetched = true,
            )
        }
        return result
    }

    private fun parseGemini(root: JSONObject): List<ModelDescriptor> {
        val array = root.optJSONArray("models") ?: return emptyList()
        val result = mutableListOf<ModelDescriptor>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rawName = obj.optString("name", "").trim()
            val id = rawName.removePrefix("models/").trim()
            if (id.isEmpty()) continue
            val methods = parseStringSet(obj.optJSONArray("supportedGenerationMethods"))
            if (methods.isNotEmpty() &&
                "generatecontent" !in methods &&
                "bidigeneratecontent" !in methods &&
                id != ModelCapabilityClassifier.GEMINI_TRANSCRIBE_BATCH_ID &&
                id != ModelCapabilityClassifier.GEMINI_LIVE_DEFAULT_ID &&
                id != ModelCapabilityClassifier.GEMINI_LIVE_SECONDARY_ID
            ) {
                continue
            }
            val lanes = ModelCapabilityClassifier.classifyLanes(
                providerId = ProviderCapabilityRegistry.ID_GEMINI,
                modelId = id,
            )
            if (lanes.isEmpty()) continue

            val curated = ModelCapabilityClassifier.curatedModelById(ProviderCapabilityRegistry.ID_GEMINI, id)
            val displayName = curated?.displayName ?: obj.optString("displayName", "").trim().ifEmpty { id }
            val description = curated?.description?.ifBlank { null } ?: obj.optString("description", "").trim()
            val contextLength = obj.optInt("inputTokenLimit", 0).takeIf { it > 0 } ?: curated?.contextLength
            val isNonThinking = curated?.isNonThinking ?: (id == ModelCapabilityClassifier.GEMINI_TRANSCRIBE_BATCH_ID || id == ModelCapabilityClassifier.GEMINI_LIVE_DEFAULT_ID)
            val badges = buildList {
                curated?.badges?.forEach { add(it) }
                if (isNonThinking && "Non-thinking" !in this) add("Non-thinking")
                if (CapabilityLane.BATCH_STT in lanes && "Audio STT" !in this) add("Audio STT")
                if (CapabilityLane.LIVE_STT in lanes && "Live Audio" !in this) add("Live Audio")
            }
            result += ModelDescriptor(
                id = id,
                providerId = ProviderCapabilityRegistry.ID_GEMINI,
                displayName = displayName,
                description = description,
                lanes = lanes,
                tier = curated?.tier ?: ModelTier.GOOD,
                costPer1MInputUsd = curated?.costPer1MInputUsd,
                costPer1MOutputUsd = curated?.costPer1MOutputUsd,
                contextLength = contextLength,
                inputModalities = curated?.inputModalities ?: emptySet(),
                isNonThinking = isNonThinking,
                supportsLiveModeToggle = curated?.supportsLiveModeToggle ?: (id == ModelCapabilityClassifier.GEMINI_LIVE_DEFAULT_ID),
                badges = badges,
                isLiveFetched = true,
            )
        }
        return result
    }

    private fun parseAnthropic(root: JSONObject): List<ModelDescriptor> {
        val array = root.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<ModelDescriptor>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            val lanes = ModelCapabilityClassifier.classifyLanes(ProviderCapabilityRegistry.ID_ANTHROPIC, id)
            if (lanes.isEmpty()) continue
            val curated = ModelCapabilityClassifier.curatedModelById(ProviderCapabilityRegistry.ID_ANTHROPIC, id)
            val displayName = curated?.displayName ?: obj.optString("display_name", "").trim().ifEmpty { id }
            result += ModelDescriptor(
                id = id,
                providerId = ProviderCapabilityRegistry.ID_ANTHROPIC,
                displayName = displayName,
                description = curated?.description ?: "Anthropic text cleanup model.",
                lanes = lanes,
                tier = curated?.tier ?: ModelTier.GOOD,
                costPer1MInputUsd = curated?.costPer1MInputUsd,
                costPer1MOutputUsd = curated?.costPer1MOutputUsd,
                contextLength = curated?.contextLength,
                inputModalities = setOf("text"),
                badges = curated?.badges ?: listOf("Text only"),
                isLiveFetched = true,
            )
        }
        return result
    }

    private fun parseOpenAiStyle(providerId: String, root: JSONObject): List<ModelDescriptor> {
        val array = root.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<ModelDescriptor>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            val lanes = ModelCapabilityClassifier.classifyLanes(providerId, id)
            if (lanes.isEmpty()) continue
            val curated = ModelCapabilityClassifier.curatedModelById(providerId, id)
            val contextLength = obj.optInt("context_window", 0).takeIf { it > 0 }
                ?: obj.optInt("context_length", 0).takeIf { it > 0 }
                ?: curated?.contextLength
            val badges = buildList {
                curated?.badges?.forEach { add(it) }
                if (CapabilityLane.BATCH_STT in lanes && "Audio STT" !in this) add("Audio STT")
            }
            result += ModelDescriptor(
                id = id,
                providerId = providerId,
                displayName = curated?.displayName ?: id,
                description = curated?.description ?: "",
                lanes = lanes,
                tier = curated?.tier ?: ModelTier.GOOD,
                costPer1MInputUsd = curated?.costPer1MInputUsd,
                costPer1MOutputUsd = curated?.costPer1MOutputUsd,
                contextLength = contextLength,
                inputModalities = curated?.inputModalities ?: emptySet(),
                isNonThinking = curated?.isNonThinking ?: false,
                badges = badges,
                isLiveFetched = true,
            )
        }
        return result
    }

    internal fun parsePerTokenToPer1M(rawValue: Any?): Double? {
        if (rawValue == null || rawValue == JSONObject.NULL) return null
        val perToken = when (rawValue) {
            is Number -> rawValue.toDouble()
            is String -> rawValue.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!perToken.isFinite() || perToken < 0.0) return null
        return perToken * 1_000_000.0
    }

    private fun parseStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim().lowercase()
            if (value.isNotEmpty()) set += value
        }
        return set
    }
}

/**
 * Per-provider offline cache and live `/models` fetcher for searchable model pickers.
 *
 * Security invariants:
 *  - API keys are passed via HTTP headers ONLY (`Authorization: Bearer`, `x-goog-api-key`,
 *    `x-api-key`), never query strings.
 *  - Uses `NetworkClients.shared.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()` with
 *    redirects disabled so auth headers never leak cross-host.
 *  - Enforces [MAX_LIVE_MODELS_BYTES] (2 MiB) response cap, 6h TTL, 5m retry backoff, and
 *    single-flight [AtomicBoolean] per provider.
 */
object LiveModelCatalogStore {
    const val PREFS_NAME = "ramblr_live_model_catalog_cache"
    val CACHE_TTL_MS: Long = TimeUnit.HOURS.toMillis(6)
    val RETRY_BACKOFF_MS: Long = TimeUnit.MINUTES.toMillis(5)
    const val MAX_LIVE_MODELS_BYTES: Long = 2L * 1024 * 1024

    private val inFlightByProvider = ConcurrentHashMap<String, AtomicBoolean>()
    private val lastAttemptByProvider = ConcurrentHashMap<String, Long>()

    internal val client by lazy {
        NetworkClients.shared.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class RefreshResult(
        val success: Boolean,
        val models: List<ModelDescriptor>,
        val errorMessage: String? = null,
    )

    internal fun cacheKeyFor(providerId: String, customBaseUrl: String? = null): String {
        val suffix = customBaseUrl?.trim()?.takeIf { it.isNotEmpty() }?.hashCode()?.toString()
        return if (suffix != null) "${providerId}_$suffix" else providerId
    }

    /**
     * Merges live/cached models with bundled curated models for [providerId] and [lane].
     * Curated metadata (non-thinking badges, pricing, descriptions) always enriches matching live
     * models and remains available offline.
     */
    fun mergeModels(
        providerId: String,
        liveOrCached: List<ModelDescriptor>,
        lane: CapabilityLane,
    ): List<ModelDescriptor> {
        val curated = ModelCapabilityClassifier.curatedModelsFor(providerId, lane)
        val liveFiltered = liveOrCached.filter { descriptor ->
            descriptor.supports(lane) &&
                (lane == CapabilityLane.LIVE_STT || !ModelCapabilityClassifier.isLiveStreamingModel(descriptor.id))
        }
        val liveById = liveFiltered.associateBy { ModelCapabilityClassifier.normalizeId(it.id) }

        val merged = mutableListOf<ModelDescriptor>()
        val seen = mutableSetOf<String>()

        // 1. Curated entries first (preserving recommended order), enriched with live pricing/context if present.
        for (c in curated) {
            val key = ModelCapabilityClassifier.normalizeId(c.id)
            seen += key
            val live = liveById[key]
            merged += if (live != null) {
                c.copy(
                    costPer1MInputUsd = live.costPer1MInputUsd ?: c.costPer1MInputUsd,
                    costPer1MOutputUsd = live.costPer1MOutputUsd ?: c.costPer1MOutputUsd,
                    contextLength = live.contextLength ?: c.contextLength,
                    inputModalities = live.inputModalities.ifEmpty { c.inputModalities },
                    badges = (c.badges + live.badges).distinct(),
                    isLiveFetched = true,
                )
            } else {
                c
            }
        }

        // 2. Additional live-discovered models not in the curated list.
        val extraLive = liveFiltered
            .filter { ModelCapabilityClassifier.normalizeId(it.id) !in seen }
            .sortedBy { it.displayName.lowercase() }
        merged += extraLive
        return merged
    }

    fun modelsFor(
        context: Context,
        providerId: String,
        lane: CapabilityLane,
        customBaseUrl: String? = null,
    ): List<ModelDescriptor> {
        val cached = loadCached(context, providerId, customBaseUrl)
        return mergeModels(providerId, cached, lane)
    }

    fun loadCached(
        context: Context,
        providerId: String,
        customBaseUrl: String? = null,
    ): List<ModelDescriptor> {
        val key = cacheKeyFor(providerId, customBaseUrl)
        val raw = prefs(context).getString("json_$key", null) ?: return emptyList()
        return deserializeCache(raw)
    }

    fun isCacheStale(
        context: Context,
        providerId: String,
        customBaseUrl: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = cacheKeyFor(providerId, customBaseUrl)
        val cachedAt = prefs(context).getLong("at_$key", 0L)
        return cachedAt <= 0L || (nowMs - cachedAt >= CACHE_TTL_MS)
    }

    internal fun buildRequest(
        providerId: String,
        apiKey: String,
        customBaseUrl: String? = null,
    ): Request? {
        val trimmedKey = apiKey.trim()
        val url: String
        val builder = Request.Builder().get()
        when (providerId) {
            ProviderCapabilityRegistry.ID_GEMINI -> {
                if (trimmedKey.isEmpty()) return null
                url = "${GeminiCleanupProvider.BASE_URL}/models?pageSize=200"
                builder.header("x-goog-api-key", trimmedKey)
            }
            ProviderCapabilityRegistry.ID_ANTHROPIC -> {
                if (trimmedKey.isEmpty()) return null
                url = "${AnthropicCleanupProvider.BASE_URL}/models"
                builder.header("x-api-key", trimmedKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            ProviderCapabilityRegistry.ID_OPENROUTER -> {
                url = "${ProviderPresets.OPENROUTER.baseUrl.trimEnd('/')}/models"
                if (trimmedKey.isNotEmpty()) builder.header("Authorization", "Bearer $trimmedKey")
            }
            ProviderCapabilityRegistry.ID_GROQ -> {
                if (trimmedKey.isEmpty()) return null
                url = "${ProviderPresets.GROQ.baseUrl.trimEnd('/')}/models"
                builder.header("Authorization", "Bearer $trimmedKey")
            }
            ProviderCapabilityRegistry.ID_OPENAI -> {
                if (trimmedKey.isEmpty()) return null
                url = "${PostProcessor.DEFAULT_BASE_URL.trimEnd('/')}/models"
                builder.header("Authorization", "Bearer $trimmedKey")
            }
            ProviderCapabilityRegistry.ID_OMNIROUTE -> {
                if (!OmniRoute.isConfigured || trimmedKey.isEmpty()) return null
                url = "${OmniRoute.BASE_URL.trimEnd('/')}/models"
                builder.header("Authorization", "Bearer $trimmedKey")
            }
            ProviderCapabilityRegistry.ID_CUSTOM_OPENAI -> {
                val base = PostProcessor.normalizeBaseUrl(customBaseUrl ?: "") ?: return null
                url = "${base.trimEnd('/')}/models"
                if (trimmedKey.isNotEmpty()) builder.header("Authorization", "Bearer $trimmedKey")
            }
            else -> return null
        }
        return try {
            builder.url(url).build()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun refreshSync(
        context: Context,
        providerId: String,
        apiKey: String,
        customBaseUrl: String? = null,
        force: Boolean = false,
    ): RefreshResult {
        val key = cacheKeyFor(providerId, customBaseUrl)
        val now = System.currentTimeMillis()
        if (!force && !isCacheStale(context, providerId, customBaseUrl, now)) {
            return RefreshResult(true, loadCached(context, providerId, customBaseUrl))
        }
        val lastAttempt = lastAttemptByProvider[key] ?: 0L
        if (!force && now - lastAttempt < RETRY_BACKOFF_MS) {
            return RefreshResult(false, loadCached(context, providerId, customBaseUrl), "Recently attempted; try again shortly.")
        }
        val flag = inFlightByProvider.computeIfAbsent(key) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) {
            return RefreshResult(false, loadCached(context, providerId, customBaseUrl), "Refresh already in progress.")
        }
        lastAttemptByProvider[key] = now
        return try {
            val request = buildRequest(providerId, apiKey, customBaseUrl)
                ?: return RefreshResult(false, loadCached(context, providerId, customBaseUrl), "Add a valid API key to fetch live models.")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RefreshResult(false, loadCached(context, providerId, customBaseUrl), "HTTP ${response.code}")
                }
                val declaredLength = response.body.contentLength()
                if (declaredLength > MAX_LIVE_MODELS_BYTES) {
                    return RefreshResult(false, loadCached(context, providerId, customBaseUrl), "Model list response exceeded 2 MB cap.")
                }
                val body = response.peekBody(MAX_LIVE_MODELS_BYTES).string()
                val parsed = ProviderModelsParser.parse(providerId, body)
                if (parsed.isNotEmpty()) {
                    saveCache(context, key, parsed, System.currentTimeMillis())
                }
                RefreshResult(true, parsed)
            }
        } catch (e: IOException) {
            RefreshResult(false, loadCached(context, providerId, customBaseUrl), e.message ?: "Network error")
        } catch (e: Exception) {
            RefreshResult(false, loadCached(context, providerId, customBaseUrl), e.message ?: "Refresh failed")
        } finally {
            flag.set(false)
        }
    }

    fun refreshAsync(
        context: Context,
        providerId: String,
        apiKey: String,
        customBaseUrl: String? = null,
        force: Boolean = false,
        onComplete: (RefreshResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        Thread {
            val res = refreshSync(appContext, providerId, apiKey, customBaseUrl, force)
            onComplete(res)
        }.apply { isDaemon = true }.start()
    }

    private fun saveCache(context: Context, cacheKey: String, models: List<ModelDescriptor>, nowMs: Long) {
        prefs(context).edit()
            .putString("json_$cacheKey", serializeCache(models))
            .putLong("at_$cacheKey", nowMs)
            .apply()
    }

    internal fun serializeCache(models: List<ModelDescriptor>): String {
        val array = JSONArray()
        models.forEach { m ->
            array.put(JSONObject().apply {
                put("id", m.id)
                put("providerId", m.providerId)
                put("displayName", m.displayName)
                put("description", m.description)
                put("lanes", JSONArray(m.lanes.map { it.name }))
                put("tier", m.tier.name)
                put("costPer1MInputUsd", m.costPer1MInputUsd ?: JSONObject.NULL)
                put("costPer1MOutputUsd", m.costPer1MOutputUsd ?: JSONObject.NULL)
                put("contextLength", m.contextLength ?: JSONObject.NULL)
                put("inputModalities", JSONArray(m.inputModalities.toList()))
                put("isNonThinking", m.isNonThinking)
                put("supportsLiveModeToggle", m.supportsLiveModeToggle)
                put("badges", JSONArray(m.badges))
            })
        }
        return array.toString()
    }

    internal fun deserializeCache(raw: String): List<ModelDescriptor> = try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id", "").trim()
            val providerId = obj.optString("providerId", "").trim()
            if (id.isEmpty() || providerId.isEmpty()) return@mapNotNull null
            val lanesArr = obj.optJSONArray("lanes") ?: JSONArray()
            val lanes = (0 until lanesArr.length()).mapNotNull { j ->
                try {
                    CapabilityLane.valueOf(lanesArr.optString(j, ""))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.toSet()
            if (lanes.isEmpty()) return@mapNotNull null
            val tier = try {
                ModelTier.valueOf(obj.optString("tier", ModelTier.GOOD.name))
            } catch (_: IllegalArgumentException) {
                ModelTier.GOOD
            }
            val modsArr = obj.optJSONArray("inputModalities") ?: JSONArray()
            val modalities = (0 until modsArr.length()).mapNotNull { j ->
                modsArr.optString(j, "").trim().takeIf { it.isNotEmpty() }
            }.toSet()
            val badgesArr = obj.optJSONArray("badges") ?: JSONArray()
            val badges = (0 until badgesArr.length()).mapNotNull { j ->
                badgesArr.optString(j, "").trim().takeIf { it.isNotEmpty() }
            }
            ModelDescriptor(
                id = id,
                providerId = providerId,
                displayName = obj.optString("displayName", id),
                description = obj.optString("description", ""),
                lanes = lanes,
                tier = tier,
                costPer1MInputUsd = if (!obj.has("costPer1MInputUsd") || obj.isNull("costPer1MInputUsd")) null else obj.optDouble("costPer1MInputUsd"),
                costPer1MOutputUsd = if (!obj.has("costPer1MOutputUsd") || obj.isNull("costPer1MOutputUsd")) null else obj.optDouble("costPer1MOutputUsd"),
                contextLength = if (!obj.has("contextLength") || obj.isNull("contextLength")) null else obj.optInt("contextLength"),
                inputModalities = modalities,
                isNonThinking = obj.optBoolean("isNonThinking", false),
                supportsLiveModeToggle = obj.optBoolean("supportsLiveModeToggle", false),
                badges = badges,
                isLiveFetched = true,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
