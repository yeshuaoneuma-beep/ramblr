package com.trevornk.ramblr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Live transcription mode supported by `gemini-3.5-transcribe-live`. */
enum class LiveTranscriptionMode {
    VERBATIM,
    SMART,
}

/** Policy applied when a Cloud Live stream disconnects mid-dictation and capture stops. */
enum class LiveDisconnectPolicy {
    AUTO_REPLACE_WITH_BATCH,
    ASK_ME,
    KEEP_LIVE_ONLY,
}

/**
 * Mutually exclusive Live session recovery mode:
 * - [PARALLEL_BATCH_FALLBACK]: keeps local PCM capture running alongside Live so disconnects or
 *   the 9:00 cutoff finish seamlessly over Batch STT according to [LiveDisconnectPolicy].
 * - [CONTINUE_LIVE_SESSION]: disables parallel Batch fallback; pauses capture on disconnect/cutoff
 *   so the user can explicitly Continue (new Live session appending below), Cleanup, Insert raw, or Discard.
 */
enum class LiveRecoveryMode {
    PARALLEL_BATCH_FALLBACK,
    CONTINUE_LIVE_SESSION,
}

/**
 * One post-cleanup cloud slot executed after a Live dictation stops (when Post-Cleanup is ON).
 * Shares the same 3-attempt pool rules ([AttemptBudgetCalculator]) as Batch Cleanup, with a hard
 * maximum of [LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS] (3) slots.
 */
data class LivePostCleanupSlot(
    val id: String,
    val providerId: String,
    val modelId: String,
    val vaultKeyId: String? = null,
    val baseUrlOverride: String? = null,
    val attempts: Int = AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
    val locked: Boolean = false,
)

/**
 * Persisted configuration contract for Live Cloud Transcription and Post-Cleanup After Live.
 */
data class LiveTranscriptionConfig(
    val enabled: Boolean = false,
    val providerId: String = ProviderCapabilityRegistry.ID_GEMINI,
    val modelId: String = DEFAULT_LIVE_MODEL_ID,
    val vaultKeyId: String? = ProviderCapabilityRegistry.canonicalVaultKeyId(ProviderCapabilityRegistry.ID_GEMINI),
    val providerLocked: Boolean = false,
    val modelLocked: Boolean = false,
    val mode: LiveTranscriptionMode = LiveTranscriptionMode.VERBATIM,
    val postCleanupEnabled: Boolean = false,
    val postCleanupSlots: List<LivePostCleanupSlot> = emptyList(),
    val smartDisconnectAlertEnabled: Boolean = true,
    val smartDisconnectHapticEnabled: Boolean = true,
    val liveWindowEnabled: Boolean = true,
    val disconnectPolicy: LiveDisconnectPolicy = LiveDisconnectPolicy.AUTO_REPLACE_WITH_BATCH,
    val recoveryMode: LiveRecoveryMode = LiveRecoveryMode.PARALLEL_BATCH_FALLBACK,
) {
    fun effectiveVaultKeyId(): String =
        vaultKeyId?.takeIf { it.isNotBlank() } ?: ProviderCapabilityRegistry.canonicalVaultKeyId(providerId)

    fun supportsModeToggle(): Boolean = modelId == DEFAULT_LIVE_MODEL_ID

    companion object {
        const val DEFAULT_LIVE_MODEL_ID = "gemini-3.5-transcribe-live"
        const val SECONDARY_LIVE_MODEL_ID = "gemini-3.8-live"
        const val LIVE_CUTOFF_GUARD_SEC = 540
        const val MAX_LIVE_POST_CLEANUP_SLOTS = 3
    }
}

/**
 * Event contract emitted when a live WebSocket drops mid-dictation while local PCM recording
 * continues uninterrupted for clean full-audio Batch STT handoff on Stop.
 */
data class LiveDisconnectEvent(
    val reason: String,
    val elapsedSec: Int,
    val continuingLocalRecording: Boolean = true,
    val errorCode: Int? = null,
)

fun interface LiveDisconnectListener {
    fun onLiveDisconnect(event: LiveDisconnectEvent)
}

/**
 * Persists [LiveTranscriptionConfig] in `"ramblr"` SharedPreferences under
 * `"live_transcription_config_v1"` and keeps `enabled` bidirectionally synced with [CloudLiveToggle].
 */
object LiveTranscriptionConfigStore {
    private const val PREFS_NAME = "ramblr"
    const val KEY_CONFIG = "live_transcription_config_v1"

    fun serialize(config: LiveTranscriptionConfig): String = JSONObject().apply {
        put("enabled", config.enabled)
        put("providerId", config.providerId)
        put("modelId", sanitizeLiveModelId(config.modelId))
        put("vaultKeyId", config.vaultKeyId ?: JSONObject.NULL)
        put("providerLocked", config.providerLocked)
        put("modelLocked", config.modelLocked)
        put("mode", config.mode.name)
        put("postCleanupEnabled", config.postCleanupEnabled)
        put("smartDisconnectAlertEnabled", config.smartDisconnectAlertEnabled)
        put("smartDisconnectHapticEnabled", config.smartDisconnectHapticEnabled)
        put("liveWindowEnabled", config.liveWindowEnabled)
        put("disconnectPolicy", config.disconnectPolicy.name)
        put("recoveryMode", config.recoveryMode.name)
        val slotsArray = JSONArray()
        config.postCleanupSlots.take(LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS).forEach { slot ->
            slotsArray.put(JSONObject().apply {
                put("id", slot.id)
                put("providerId", slot.providerId)
                put("modelId", slot.modelId)
                put("vaultKeyId", slot.vaultKeyId ?: JSONObject.NULL)
                put("baseUrlOverride", slot.baseUrlOverride ?: JSONObject.NULL)
                put("attempts", slot.attempts.coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS))
                put("locked", slot.locked)
            })
        }
        put("postCleanupSlots", slotsArray)
    }.toString()

    fun deserialize(raw: String?): LiveTranscriptionConfig? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val providerId = obj.optString("providerId", ProviderCapabilityRegistry.ID_GEMINI)
                .takeIf { ProviderCapabilityRegistry.forId(it)?.supports(CapabilityLane.LIVE_STT) == true }
                ?: ProviderCapabilityRegistry.ID_GEMINI
            val modelId = sanitizeLiveModelId(obj.optString("modelId", LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID))
            val mode = try {
                LiveTranscriptionMode.valueOf(obj.optString("mode", LiveTranscriptionMode.VERBATIM.name))
            } catch (_: IllegalArgumentException) {
                LiveTranscriptionMode.VERBATIM
            }
            val disconnectPolicy = try {
                LiveDisconnectPolicy.valueOf(
                    obj.optString("disconnectPolicy", LiveDisconnectPolicy.AUTO_REPLACE_WITH_BATCH.name)
                )
            } catch (_: IllegalArgumentException) {
                LiveDisconnectPolicy.AUTO_REPLACE_WITH_BATCH
            }
            val recoveryMode = try {
                LiveRecoveryMode.valueOf(
                    obj.optString("recoveryMode", LiveRecoveryMode.PARALLEL_BATCH_FALLBACK.name)
                )
            } catch (_: IllegalArgumentException) {
                LiveRecoveryMode.PARALLEL_BATCH_FALLBACK
            }
            val rawSlots = mutableListOf<LivePostCleanupSlot>()
            val slotsArray = obj.optJSONArray("postCleanupSlots")
            if (slotsArray != null) {
                for (i in 0 until minOf(slotsArray.length(), LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS)) {
                    val slotObj = slotsArray.optJSONObject(i) ?: continue
                    val id = slotObj.optString("id", "").ifBlank { "live_cleanup_${i + 1}" }
                    val slotProvider = slotObj.optString("providerId", "").trim()
                    if (slotProvider.isEmpty()) continue
                    rawSlots += LivePostCleanupSlot(
                        id = id,
                        providerId = slotProvider,
                        modelId = slotObj.optString("modelId", ""),
                        vaultKeyId = if (!slotObj.has("vaultKeyId") || slotObj.isNull("vaultKeyId")) null else slotObj.optString("vaultKeyId").takeIf { it.isNotBlank() },
                        baseUrlOverride = if (!slotObj.has("baseUrlOverride") || slotObj.isNull("baseUrlOverride")) null else slotObj.optString("baseUrlOverride").takeIf { it.isNotBlank() },
                        attempts = slotObj.optInt("attempts", AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS)
                            .coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS),
                        locked = slotObj.optBoolean("locked", false),
                    )
                }
            }
            val normalizedBudget = AttemptBudgetCalculator.normalizeOnUpgrade(
                rawSlots.map {
                    AttemptBudgetCalculator.BudgetSlot(it.id, it.providerId, it.locked, it.attempts)
                }
            ).associateBy { it.id }
            val slots = rawSlots.map { slot ->
                val b = normalizedBudget[slot.id]
                slot.copy(
                    locked = b?.locked ?: false,
                    attempts = b?.attempts ?: AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
                )
            }
            LiveTranscriptionConfig(
                enabled = obj.optBoolean("enabled", false),
                providerId = providerId,
                modelId = modelId,
                vaultKeyId = if (!obj.has("vaultKeyId") || obj.isNull("vaultKeyId")) null else obj.optString("vaultKeyId").takeIf { it.isNotBlank() },
                providerLocked = obj.optBoolean("providerLocked", false),
                modelLocked = obj.optBoolean("modelLocked", false),
                mode = mode,
                postCleanupEnabled = obj.optBoolean("postCleanupEnabled", false),
                postCleanupSlots = slots,
                smartDisconnectAlertEnabled = obj.optBoolean("smartDisconnectAlertEnabled", true),
                smartDisconnectHapticEnabled = obj.optBoolean("smartDisconnectHapticEnabled", true),
                liveWindowEnabled = obj.optBoolean("liveWindowEnabled", true),
                disconnectPolicy = disconnectPolicy,
                recoveryMode = recoveryMode,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun sanitizeLiveModelId(modelId: String?): String {
        val trimmed = modelId?.trim().orEmpty()
        return if (GeminiCloudLiveTranscriptionClient.isSupportedLiveModel(trimmed)) {
            trimmed
        } else {
            LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID
        }
    }

    fun hasPersistedConfig(context: Context): Boolean =
        !prefs(context).getString(KEY_CONFIG, null).isNullOrBlank()

    fun load(context: Context): LiveTranscriptionConfig {
        val p = prefs(context)
        val parsed = deserialize(p.getString(KEY_CONFIG, null))
        val toggleEnabled = CloudLiveToggle.isEnabled(p)
        return if (parsed != null) {
            if (p.contains(CloudLiveToggle.KEY)) parsed.copy(enabled = toggleEnabled) else parsed
        } else {
            LiveTranscriptionConfig(enabled = toggleEnabled)
        }
    }

    fun save(context: Context, config: LiveTranscriptionConfig) {
        val sanitized = config.copy(
            modelId = sanitizeLiveModelId(config.modelId),
            postCleanupSlots = config.postCleanupSlots.take(LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS),
        )
        prefs(context).edit()
            .putString(KEY_CONFIG, serialize(sanitized))
            .putBoolean(CloudLiveToggle.KEY, sanitized.enabled)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
