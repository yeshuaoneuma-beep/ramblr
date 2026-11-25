package com.trevornk.ramblr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the unified [ProviderChain] as JSON in the plain (not encrypted) "ramblr"
 * SharedPreferences file. The entries themselves aren't secrets -- credentials live in
 * [ProviderCredentialStore].
 */
object ProviderChainStore {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_ENTRIES = "provider_chain_entries"

    fun serialize(chain: ProviderChain): String {
        val array = JSONArray()
        chain.entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("kind", entry.kind.name)
                put("model", entry.model)
                put("baseUrlOverride", entry.baseUrlOverride ?: JSONObject.NULL)
                put("transcriptionModel", entry.transcriptionModel ?: JSONObject.NULL)
                // #274: id/enabled/useFor* are new fields. id is written even when blank (an
                // entry that has never been through ProviderAccountMigration) so a round trip
                // through serialize/deserialize is lossless either way.
                put("id", entry.id)
                put("enabled", entry.enabled)
                put("useForTranscription", entry.useForTranscription)
                put("useForCleanup", entry.useForCleanup)
                // #275: preset marker, null for every entry saved before #275 shipped.
                put("presetId", entry.presetId ?: JSONObject.NULL)
                // Phase 3A.1: shared vault key link, lock-in flags, shared 3-attempt pool counts, custom label.
                put("vaultKeyId", entry.vaultKeyId ?: JSONObject.NULL)
                put("providerLocked", entry.providerLocked)
                put("sttLocked", entry.sttLocked)
                put("cleanupLocked", entry.cleanupLocked)
                put("sttAttempts", entry.sttAttempts)
                put("cleanupAttempts", entry.cleanupAttempts)
                put("customLabel", entry.customLabel ?: JSONObject.NULL)
            })
        }
        return array.toString()
    }

    /**
     * Returns null if [raw] is blank (never configured) or malformed, so [load] falls back to
     * [ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY]. A valid empty array is not null: an
     * explicitly-emptied chain loads as zero entries, not the default.
     */
    fun deserialize(raw: String?): ProviderChain? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            val entries = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val kind = ProviderKind.valueOf(obj.getString("kind"))
                val useForTranscription = if (obj.has("useForTranscription")) obj.optBoolean("useForTranscription") else kind.supportsTranscription()
                val useForCleanup = if (obj.has("useForCleanup")) obj.optBoolean("useForCleanup") else kind.supportsCleanup()
                ProviderChainEntry(
                    kind = kind,
                    model = obj.getString("model"),
                    baseUrlOverride = if (obj.isNull("baseUrlOverride")) null else obj.getString("baseUrlOverride"),
                    transcriptionModel = if (!obj.has("transcriptionModel") || obj.isNull("transcriptionModel")) null else obj.getString("transcriptionModel"),
                    id = obj.optString("id", ""),
                    enabled = obj.optBoolean("enabled", true),
                    useForTranscription = useForTranscription,
                    useForCleanup = useForCleanup,
                    presetId = if (!obj.has("presetId") || obj.isNull("presetId")) null else obj.getString("presetId"),
                    vaultKeyId = if (!obj.has("vaultKeyId") || obj.isNull("vaultKeyId")) null else obj.getString("vaultKeyId"),
                    providerLocked = obj.optBoolean("providerLocked", true),
                    sttLocked = if (obj.has("sttLocked")) obj.optBoolean("sttLocked") else useForTranscription,
                    cleanupLocked = if (obj.has("cleanupLocked")) obj.optBoolean("cleanupLocked") else useForCleanup,
                    sttAttempts = obj.optInt("sttAttempts", AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS)
                        .coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS),
                    cleanupAttempts = obj.optInt("cleanupAttempts", AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS)
                        .coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS),
                    customLabel = if (!obj.has("customLabel") || obj.isNull("customLabel")) null else obj.getString("customLabel"),
                )
            }
            ProviderChain(entries)
        } catch (e: Exception) {
            null
        }
    }

    /** The user's configured chain; [ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY] if nothing
     *  (valid) has ever been saved. [normalizeLocalPosition] repairs any chain persisted before
     *  [ProviderChainEditing.addCloud] existed, where a LOCAL entry could have ended up ahead of
     *  cloud entries -- self-healing on every load rather than requiring a one-shot migration
     *  step or a manual re-add from the user (Trevor hit this live on an existing install). */
    fun load(context: Context): ProviderChain =
        normalizeLocalPosition(deserialize(prefs(context).getString(KEY_ENTRIES, null)) ?: ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY)

    /** Moves any [ProviderKind.LOCAL] entry to the end of [chain], preserving the relative order
     *  of every other entry. No-op (returns [chain] unchanged, same instance) if LOCAL is already
     *  last or absent, so a normal already-correct chain never gets a spurious re-save. Internal
     *  (not private) so it's directly unit-testable as the pure function it is, without needing a
     *  fake Android [Context]/SharedPreferences just to exercise list-reordering logic. */
    internal fun normalizeLocalPosition(chain: ProviderChain): ProviderChain {
        val entries = chain.entries
        val localIndex = entries.indexOfFirst { it.kind == ProviderKind.LOCAL }
        if (localIndex < 0 || localIndex == entries.lastIndex) return chain
        val reordered = entries.toMutableList().apply { add(removeAt(localIndex)) }
        return ProviderChain(reordered)
    }

    fun save(context: Context, chain: ProviderChain) {
        prefs(context).edit().putString(KEY_ENTRIES, serialize(chain)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
