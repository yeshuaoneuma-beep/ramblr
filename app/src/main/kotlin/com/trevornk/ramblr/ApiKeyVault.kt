package com.trevornk.ramblr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * Non-secret metadata for an API key stored in the shared API Keys Vault.
 *
 * The actual secret lives exclusively in encrypted SharedPreferences via
 * [ProviderCredentialStore.getVaultSecret] / [ProviderCredentialStore.setVaultSecret] under
 * `"vault_${id}_key"`.
 */
data class ApiKeyVaultEntry(
    val id: String,
    val providerId: String,
    val displayLabel: String,
    val customBaseUrl: String? = null,
    val locked: Boolean = false,
)

/** Result of local format validation before locking an API key in the vault. */
data class ApiKeyFormatCheck(
    val isValid: Boolean,
    val message: String? = null,
)

/** One active or linked consumer referencing a vault key, used by the delete-in-use guard. */
data class ApiKeyUsage(
    val area: String,
    val slotLabel: String,
    val laneLabel: String,
)

/**
 * Local format validation for vault API keys before enabling the green lock-in state.
 */
object ApiKeyVaultValidator {
    fun validate(
        providerId: String,
        key: String,
        customBaseUrl: String? = null,
    ): ApiKeyFormatCheck {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) {
            return ApiKeyFormatCheck(false, "Enter an API key first.")
        }
        if (trimmedKey.any { it.isWhitespace() }) {
            return ApiKeyFormatCheck(false, "API key cannot contain spaces or line breaks.")
        }

        if (providerId == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI) {
            val baseUrl = customBaseUrl?.trim().orEmpty()
            if (baseUrl.isEmpty()) {
                return ApiKeyFormatCheck(false, "Enter a Base URL (e.g. https://host/v1).")
            }
            val parsed = try {
                URI(baseUrl)
            } catch (_: Exception) {
                null
            }
            val scheme = parsed?.scheme?.lowercase()
            if (parsed == null || (scheme != "https" && scheme != "http") || parsed.host.isNullOrBlank()) {
                return ApiKeyFormatCheck(false, "Base URL must be a valid http:// or https:// URL.")
            }
            if (trimmedKey.length < 4) {
                return ApiKeyFormatCheck(false, "API key looks too short.")
            }
            return ApiKeyFormatCheck(true, null)
        }

        if (trimmedKey.length < 8) {
            return ApiKeyFormatCheck(false, "API key looks too short.")
        }

        return when (providerId) {
            ProviderCapabilityRegistry.ID_GEMINI -> {
                if (!trimmedKey.startsWith("AIza") || trimmedKey.length < 20) {
                    ApiKeyFormatCheck(false, "Gemini keys start with \"AIza\" (at least 20 characters).")
                } else {
                    ApiKeyFormatCheck(true, null)
                }
            }
            ProviderCapabilityRegistry.ID_GROQ -> {
                if (!trimmedKey.startsWith("gsk_") || trimmedKey.length < 20) {
                    ApiKeyFormatCheck(false, "Groq keys start with \"gsk_\" (at least 20 characters).")
                } else {
                    ApiKeyFormatCheck(true, null)
                }
            }
            ProviderCapabilityRegistry.ID_OPENROUTER -> {
                if (!trimmedKey.startsWith("sk-or-") || trimmedKey.length < 20) {
                    ApiKeyFormatCheck(false, "OpenRouter keys start with \"sk-or-\" (at least 20 characters).")
                } else {
                    ApiKeyFormatCheck(true, null)
                }
            }
            ProviderCapabilityRegistry.ID_OPENAI -> {
                if (!trimmedKey.startsWith("sk-") || trimmedKey.length < 20) {
                    ApiKeyFormatCheck(false, "OpenAI keys start with \"sk-\" (at least 20 characters).")
                } else {
                    ApiKeyFormatCheck(true, null)
                }
            }
            ProviderCapabilityRegistry.ID_ANTHROPIC -> {
                if (!trimmedKey.startsWith("sk-ant-") || trimmedKey.length < 20) {
                    ApiKeyFormatCheck(false, "Anthropic keys start with \"sk-ant-\" (at least 20 characters).")
                } else {
                    ApiKeyFormatCheck(true, null)
                }
            }
            else -> ApiKeyFormatCheck(true, null)
        }
    }
}

/**
 * Pure usage scanner for the vault's delete-in-use guard.
 *
 * Scans Batch STT, Batch Cleanup, Live STT, and Live Post-Cleanup so deleting a vault key warns
 * the user with every slot that depends on it and offers a clean detach-and-delete path.
 */
object ApiKeyUsageScanner {
    fun usagesOf(
        vaultKeyId: String,
        batchChain: ProviderChain,
        liveConfig: LiveTranscriptionConfig,
    ): List<ApiKeyUsage> {
        if (vaultKeyId.isBlank()) return emptyList()
        val usages = mutableListOf<ApiKeyUsage>()

        batchChain.entries.forEachIndexed { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) return@forEachIndexed
            val resolvedKeyId = entry.vaultKeyId
                ?: ProviderCapabilityRegistry.canonicalVaultKeyId(ProviderCapabilityRegistry.providerIdFor(entry))
            if (resolvedKeyId != vaultKeyId) return@forEachIndexed

            val cardLabel = "${ProviderCapabilityRegistry.displayNameFor(entry)} (#${idx + 1})"
            var addedLane = false
            if (entry.isBatchSttActive()) {
                val model = entry.transcriptionModel?.takeIf { it.isNotBlank() && it != ProviderChainEntry.SKIP_MODEL_ID }
                    ?: "default STT"
                usages += ApiKeyUsage("Batch STT", "$cardLabel \u00b7 $model", "Batch STT")
                addedLane = true
            }
            if (entry.isBatchCleanupActive()) {
                val model = entry.model.takeIf { it.isNotBlank() && it != ProviderChainEntry.SKIP_MODEL_ID }
                    ?: "default cleanup"
                usages += ApiKeyUsage("Batch Cleanup", "$cardLabel \u00b7 $model", "Batch Cleanup")
                addedLane = true
            }
            if (!addedLane && entry.vaultKeyId == vaultKeyId) {
                usages += ApiKeyUsage("Provider Card", cardLabel, "Saved card")
            }
        }

        if ((liveConfig.enabled || liveConfig.providerLocked || liveConfig.modelLocked) &&
            liveConfig.effectiveVaultKeyId() == vaultKeyId
        ) {
            usages += ApiKeyUsage(
                area = "Live STT",
                slotLabel = "${ProviderCapabilityRegistry.forId(liveConfig.providerId)?.displayName ?: liveConfig.providerId} \u00b7 ${liveConfig.modelId}",
                laneLabel = "Live transcription",
            )
        }

        liveConfig.postCleanupSlots.forEachIndexed { idx, slot ->
            val slotKeyId = slot.vaultKeyId ?: ProviderCapabilityRegistry.canonicalVaultKeyId(slot.providerId)
            if (slotKeyId == vaultKeyId) {
                val providerLabel = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId
                usages += ApiKeyUsage(
                    area = "Live Post-Cleanup",
                    slotLabel = "$providerLabel (#${idx + 1}) \u00b7 ${slot.modelId.ifBlank { "unset" }}",
                    laneLabel = "Post-cleanup",
                )
            }
        }

        return usages
    }

    /** Detaches and unlocks all batch cards referencing [vaultKeyId] without deleting the cards. */
    fun detachFromChain(vaultKeyId: String, chain: ProviderChain): ProviderChain = ProviderChain(
        chain.entries.map { entry ->
            if (entry.kind == ProviderKind.LOCAL) return@map entry
            val resolvedKeyId = entry.vaultKeyId
                ?: ProviderCapabilityRegistry.canonicalVaultKeyId(ProviderCapabilityRegistry.providerIdFor(entry))
            if (resolvedKeyId == vaultKeyId) {
                entry.copy(
                    vaultKeyId = null,
                    providerLocked = false,
                    sttLocked = false,
                    cleanupLocked = false,
                    useForTranscription = false,
                    useForCleanup = false,
                )
            } else {
                entry
            }
        }
    )

    /** Detaches and unlocks live STT / post-cleanup slots referencing [vaultKeyId]. */
    fun detachFromLiveConfig(vaultKeyId: String, config: LiveTranscriptionConfig): LiveTranscriptionConfig {
        val detachLive = config.effectiveVaultKeyId() == vaultKeyId
        val updatedSlots = config.postCleanupSlots.map { slot ->
            val slotKeyId = slot.vaultKeyId ?: ProviderCapabilityRegistry.canonicalVaultKeyId(slot.providerId)
            if (slotKeyId == vaultKeyId) {
                slot.copy(vaultKeyId = null, locked = false)
            } else {
                slot
            }
        }
        return config.copy(
            providerLocked = if (detachLive) false else config.providerLocked,
            modelLocked = if (detachLive) false else config.modelLocked,
            vaultKeyId = if (detachLive) null else config.vaultKeyId,
            postCleanupSlots = updatedSlots,
        )
    }
}

/**
 * Persistence for shared API Keys Vault metadata (plain prefs `"ramblr"`, key `"api_key_vault_v1"`).
 * Secrets are always stored in [ProviderCredentialStore]'s encrypted prefs.
 */
object ApiKeyVaultStore {
    private const val PREFS_NAME = "ramblr"
    const val KEY_VAULT = "api_key_vault_v1"

    fun serialize(entries: List<ApiKeyVaultEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("providerId", entry.providerId)
                put("displayLabel", entry.displayLabel)
                put("customBaseUrl", entry.customBaseUrl ?: JSONObject.NULL)
                put("locked", entry.locked)
            })
        }
        return array.toString()
    }

    fun deserialize(raw: String?): List<ApiKeyVaultEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "").trim()
                val providerId = obj.optString("providerId", "").trim()
                if (id.isEmpty() || providerId.isEmpty()) return@mapNotNull null
                ApiKeyVaultEntry(
                    id = id,
                    providerId = providerId,
                    displayLabel = obj.optString("displayLabel", providerId),
                    customBaseUrl = if (!obj.has("customBaseUrl") || obj.isNull("customBaseUrl")) null else obj.optString("customBaseUrl").takeIf { it.isNotBlank() },
                    locked = obj.optBoolean("locked", false),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun load(context: Context): List<ApiKeyVaultEntry> =
        deserialize(prefs(context).getString(KEY_VAULT, null))

    fun save(context: Context, entries: List<ApiKeyVaultEntry>) {
        prefs(context).edit().putString(KEY_VAULT, serialize(entries)).apply()
    }

    fun findById(context: Context, vaultKeyId: String?): ApiKeyVaultEntry? =
        vaultKeyId?.takeIf { it.isNotBlank() }?.let { id -> load(context).firstOrNull { it.id == id } }

    fun findForProvider(context: Context, providerId: String): List<ApiKeyVaultEntry> =
        load(context).filter { it.providerId == providerId }

    fun findLockedForProvider(context: Context, providerId: String): ApiKeyVaultEntry? =
        load(context).firstOrNull {
            it.providerId == providerId && it.locked && ProviderCredentialStore.isVaultConfigured(context, it.id)
        }

    fun upsert(context: Context, entry: ApiKeyVaultEntry, secret: String? = null) {
        if (secret != null) {
            if (secret.isBlank()) {
                ProviderCredentialStore.clearVaultSecret(context, entry.id)
            } else {
                ProviderCredentialStore.setVaultSecret(context, entry.id, secret.trim())
            }
        }
        val current = load(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.id == entry.id }
        if (existingIdx >= 0) current[existingIdx] = entry else current.add(entry)
        save(context, current)
    }

    fun delete(context: Context, vaultKeyId: String) {
        ProviderCredentialStore.clearVaultSecret(context, vaultKeyId)
        val remaining = load(context).filterNot { it.id == vaultKeyId }
        save(context, remaining)
    }

    /**
     * Resolves a vault secret by explicit [vaultKeyId], or falls back to a configured vault entry
     * for [providerId] (preferring locked entries, then the canonical slot).
     */
    fun resolveSecret(context: Context, vaultKeyId: String?, providerId: String? = null): String {
        if (!vaultKeyId.isNullOrBlank()) {
            val direct = ProviderCredentialStore.getVaultSecret(context, vaultKeyId)
            if (direct.isNotBlank()) return direct
        }
        if (!providerId.isNullOrBlank()) {
            val entries = findForProvider(context, providerId)
            entries.firstOrNull { it.locked }?.let { locked ->
                val secret = ProviderCredentialStore.getVaultSecret(context, locked.id)
                if (secret.isNotBlank()) return secret
            }
            entries.forEach { entry ->
                val secret = ProviderCredentialStore.getVaultSecret(context, entry.id)
                if (secret.isNotBlank()) return secret
            }
            val canonical = ProviderCapabilityRegistry.canonicalVaultKeyId(providerId)
            val canonicalSecret = ProviderCredentialStore.getVaultSecret(context, canonical)
            if (canonicalSecret.isNotBlank()) return canonicalSecret
        }
        return ""
    }

    fun hasSecret(context: Context, vaultKeyId: String?, providerId: String? = null): Boolean =
        resolveSecret(context, vaultKeyId, providerId).isNotBlank()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Version-gated, crash-safe, idempotent migration into the shared API Keys Vault and 3-attempt
 * pool model.
 *
 * Never deletes legacy per-kind slots or per-entry `entry_<id>_key` slots. Preserves distinct
 * secrets on same-kind collisions by allocating secondary vault slots, parks overflow cards
 * #4..#10 unlocked without deleting them, and seeds [LiveTranscriptionConfig] from [CloudLiveToggle]
 * when absent.
 */
object ApiKeyVaultMigration {
    private const val PREFS_NAME = "ramblr"
    const val KEY_MIGRATION_VERSION = "api_key_vault_migration_version"
    const val MIGRATION_VERSION = 1

    data class MigrationResult(
        val vaultEntries: List<ApiKeyVaultEntry>,
        val secretWrites: Map<String, String>,
        val chain: ProviderChain,
        val seededLiveConfig: LiveTranscriptionConfig?,
    )

    /**
     * Pure core of the vault migration — fully JVM-testable without Android Context.
     */
    fun migratePure(
        chain: ProviderChain,
        existingVault: List<ApiKeyVaultEntry>,
        existingVaultSecrets: Map<String, String>,
        entrySecrets: Map<String, String>,
        legacyKindSecrets: Map<ProviderKind, String>,
        cloudLiveEnabled: Boolean,
        hasPersistedLiveConfig: Boolean,
        newId: () -> String = { UUID.randomUUID().toString().take(8) },
    ): MigrationResult {
        val vaultById = linkedMapOf<String, ApiKeyVaultEntry>()
        existingVault.forEach { vaultById[it.id] = it }
        val effectiveSecrets = existingVaultSecrets.toMutableMap()
        val secretWrites = linkedMapOf<String, String>()

        fun putVaultEntry(entry: ApiKeyVaultEntry, secret: String?) {
            vaultById[entry.id] = entry
            if (!secret.isNullOrBlank()) {
                effectiveSecrets[entry.id] = secret
                secretWrites[entry.id] = secret
            }
        }

        fun allocateForProvider(
            providerId: String,
            secret: String,
            customBaseUrl: String?,
            customLabel: String?,
        ): String {
            val descriptor = ProviderCapabilityRegistry.forId(providerId)
            if (providerId == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI) {
                val existingMatch = vaultById.values.firstOrNull {
                    it.providerId == providerId &&
                        it.customBaseUrl == customBaseUrl &&
                        (secret.isBlank() || effectiveSecrets[it.id].isNullOrBlank() || effectiveSecrets[it.id] == secret)
                }
                if (existingMatch != null) {
                    if (secret.isNotBlank() && effectiveSecrets[existingMatch.id].isNullOrBlank()) {
                        putVaultEntry(existingMatch.copy(locked = true), secret)
                    }
                    return existingMatch.id
                }
                val id = "vault_custom_${newId()}"
                val label = customLabel?.takeIf { it.isNotBlank() }
                    ?: customBaseUrl?.let { PostProcessor.destinationHost(it) }?.takeIf { it.isNotBlank() }
                    ?: "Custom OpenAI"
                putVaultEntry(
                    ApiKeyVaultEntry(
                        id = id,
                        providerId = providerId,
                        displayLabel = label,
                        customBaseUrl = customBaseUrl,
                        locked = secret.isNotBlank(),
                    ),
                    secret.takeIf { it.isNotBlank() },
                )
                return id
            }

            val canonicalId = ProviderCapabilityRegistry.canonicalVaultKeyId(providerId)
            val baseLabel = descriptor?.displayName ?: providerId
            val existingCanonical = vaultById[canonicalId]
            val existingCanonicalSecret = effectiveSecrets[canonicalId].orEmpty()

            if (existingCanonical == null) {
                putVaultEntry(
                    ApiKeyVaultEntry(
                        id = canonicalId,
                        providerId = providerId,
                        displayLabel = baseLabel,
                        locked = secret.isNotBlank(),
                    ),
                    secret.takeIf { it.isNotBlank() },
                )
                return canonicalId
            }

            if (secret.isBlank() || existingCanonicalSecret.isBlank() || existingCanonicalSecret == secret) {
                if (secret.isNotBlank() && existingCanonicalSecret.isBlank()) {
                    putVaultEntry(existingCanonical.copy(locked = true), secret)
                }
                return canonicalId
            }

            // Distinct secret on the same providerId — preserve both without overwriting!
            val existingSameSecret = vaultById.values.firstOrNull {
                it.providerId == providerId && effectiveSecrets[it.id] == secret
            }
            if (existingSameSecret != null) return existingSameSecret.id

            var suffix = 2
            while (vaultById.containsKey("${canonicalId}_$suffix")) suffix++
            val secondaryId = "${canonicalId}_$suffix"
            putVaultEntry(
                ApiKeyVaultEntry(
                    id = secondaryId,
                    providerId = providerId,
                    displayLabel = "$baseLabel #$suffix",
                    locked = true,
                ),
                secret,
            )
            return secondaryId
        }

        // Step 1: link every cloud ProviderChainEntry to a vault slot.
        val linkedEntries = chain.entries.map { entry ->
            if (entry.kind == ProviderKind.LOCAL) return@map entry
            val providerId = ProviderCapabilityRegistry.providerIdFor(entry)
            val secret = entrySecrets[entry.id]?.takeIf { it.isNotBlank() }
                ?: legacyKindSecrets[entry.kind]?.takeIf { it.isNotBlank() }
                ?: ""

            val existingVaultId = entry.vaultKeyId?.takeIf { it.isNotBlank() && vaultById.containsKey(it) }
            val targetVaultId = if (existingVaultId != null) {
                if (secret.isNotBlank() && effectiveSecrets[existingVaultId].isNullOrBlank()) {
                    putVaultEntry(vaultById[existingVaultId]!!.copy(locked = true), secret)
                }
                existingVaultId
            } else {
                allocateForProvider(providerId, secret, entry.baseUrlOverride, entry.customLabel)
            }

            entry.copy(
                vaultKeyId = targetVaultId,
                providerLocked = entry.enabled && entry.providerLocked,
                sttLocked = entry.enabled && entry.providerLocked && entry.sttLocked && entry.useForTranscription && entry.kind.supportsTranscription() && entry.transcriptionModel != ProviderChainEntry.SKIP_MODEL_ID,
                cleanupLocked = entry.enabled && entry.providerLocked && entry.cleanupLocked && entry.useForCleanup && entry.kind.supportsCleanup() && entry.model != ProviderChainEntry.SKIP_MODEL_ID,
                sttAttempts = entry.sttAttempts.coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS),
                cleanupAttempts = entry.cleanupAttempts.coerceIn(AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS, AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS),
            )
        }

        // Step 2: preserve any standalone legacy per-kind keys that had no chain entry.
        legacyKindSecrets.forEach { (kind, secret) ->
            if (kind == ProviderKind.LOCAL || secret.isBlank()) return@forEach
            val providerId = ProviderCapabilityRegistry.providerIdFor(kind)
            val canonicalId = ProviderCapabilityRegistry.canonicalVaultKeyId(providerId)
            if (effectiveSecrets[canonicalId].isNullOrBlank()) {
                val descriptor = ProviderCapabilityRegistry.forId(providerId)
                putVaultEntry(
                    ApiKeyVaultEntry(
                        id = canonicalId,
                        providerId = providerId,
                        displayLabel = descriptor?.displayName ?: providerId,
                        locked = true,
                    ),
                    secret,
                )
            }
        }

        // Step 3: normalize active locked cloud slots per lane against the 3-attempt pool.
        val sttBudgetInput = linkedEntries.mapIndexedNotNull { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) null
            else AttemptBudgetCalculator.BudgetSlot(
                id = "idx_$idx",
                label = ProviderCapabilityRegistry.displayNameFor(entry),
                locked = entry.providerLocked && entry.sttLocked,
                attempts = entry.sttAttempts,
            )
        }
        val sttNormalized = AttemptBudgetCalculator.normalizeOnUpgrade(sttBudgetInput).associateBy { it.id }

        val cleanupBudgetInput = linkedEntries.mapIndexedNotNull { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) null
            else AttemptBudgetCalculator.BudgetSlot(
                id = "idx_$idx",
                label = ProviderCapabilityRegistry.displayNameFor(entry),
                locked = entry.providerLocked && entry.cleanupLocked,
                attempts = entry.cleanupAttempts,
            )
        }
        val cleanupNormalized = AttemptBudgetCalculator.normalizeOnUpgrade(cleanupBudgetInput).associateBy { it.id }

        val normalizedEntries = linkedEntries.mapIndexed { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) return@mapIndexed entry
            val sttSlot = sttNormalized["idx_$idx"]
            val cleanupSlot = cleanupNormalized["idx_$idx"]
            val newSttLocked = sttSlot?.locked == true
            val newCleanupLocked = cleanupSlot?.locked == true
            val newProviderLocked = entry.providerLocked && (newSttLocked || newCleanupLocked || (!entry.sttLocked && !entry.cleanupLocked))
            entry.copy(
                providerLocked = newProviderLocked,
                sttLocked = newSttLocked,
                cleanupLocked = newCleanupLocked,
                sttAttempts = sttSlot?.attempts ?: AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
                cleanupAttempts = cleanupSlot?.attempts ?: AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
                useForTranscription = newProviderLocked && newSttLocked && entry.kind.supportsTranscription() && entry.transcriptionModel != ProviderChainEntry.SKIP_MODEL_ID,
                useForCleanup = newProviderLocked && newCleanupLocked && entry.kind.supportsCleanup() && entry.model != ProviderChainEntry.SKIP_MODEL_ID,
            )
        }

        // Step 4: seed LiveTranscriptionConfig from CloudLiveToggle if absent.
        val seededLiveConfig = if (!hasPersistedLiveConfig) {
            val geminiVaultId = ProviderCapabilityRegistry.canonicalVaultKeyId(ProviderCapabilityRegistry.ID_GEMINI)
            val hasGeminiKey = !effectiveSecrets[geminiVaultId].isNullOrBlank()
            LiveTranscriptionConfig(
                enabled = cloudLiveEnabled,
                providerId = ProviderCapabilityRegistry.ID_GEMINI,
                modelId = LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID,
                vaultKeyId = geminiVaultId,
                providerLocked = hasGeminiKey,
                modelLocked = hasGeminiKey,
                mode = LiveTranscriptionMode.VERBATIM,
                postCleanupEnabled = false,
                postCleanupSlots = emptyList(),
                smartDisconnectAlertEnabled = true,
                smartDisconnectHapticEnabled = true,
            )
        } else {
            null
        }

        return MigrationResult(
            vaultEntries = vaultById.values.toList(),
            secretWrites = secretWrites,
            chain = ProviderChain(normalizedEntries),
            seededLiveConfig = seededLiveConfig,
        )
    }

    fun runIfNeeded(context: Context) {
        ProviderAccountMigration.runIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_MIGRATION_VERSION, 0) >= MIGRATION_VERSION) return

        val chain = ProviderChainStore.load(context)
        val existingVault = ApiKeyVaultStore.load(context)
        val existingVaultSecrets = existingVault.associate {
            it.id to ProviderCredentialStore.getVaultSecret(context, it.id)
        }
        val entrySecrets = chain.entries
            .filter { it.kind != ProviderKind.LOCAL && it.id.isNotBlank() }
            .associate { it.id to ProviderCredentialStore.getDirectEntrySecret(context, it.id) }
        val legacyKindSecrets = ProviderKind.entries
            .filter { it != ProviderKind.LOCAL }
            .associateWith { ProviderCredentialStore.getLegacyByKind(context, it) }

        val result = migratePure(
            chain = chain,
            existingVault = existingVault,
            existingVaultSecrets = existingVaultSecrets,
            entrySecrets = entrySecrets,
            legacyKindSecrets = legacyKindSecrets,
            cloudLiveEnabled = CloudLiveToggle.isEnabled(context),
            hasPersistedLiveConfig = LiveTranscriptionConfigStore.hasPersistedConfig(context),
        )

        // Crash-safe write order: secrets first, then vault metadata, then chain, then live config, then version gate.
        result.secretWrites.forEach { (vaultId, secret) ->
            ProviderCredentialStore.setVaultSecret(context, vaultId, secret)
        }
        if (result.vaultEntries != existingVault) {
            ApiKeyVaultStore.save(context, result.vaultEntries)
        }
        if (result.chain != chain) {
            ProviderChainStore.save(context, result.chain)
        }
        result.seededLiveConfig?.let {
            LiveTranscriptionConfigStore.save(context, it)
        }
        prefs.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION).apply()
    }
}
