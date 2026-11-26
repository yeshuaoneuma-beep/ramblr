package com.trevornk.ramblr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.UUID

/**
 * Dedicated Live Transcription configuration screen (Phase 3A.3 hairline overhaul).
 *
 * Flat hairline layout, key-gated `Live cloud transcription` switch, key-gated `lockCircle`,
 * flat hairline `Verbatim | Smart` segmented selector (NO pills), flat 9:00 cutoff guard notice,
 * Smart Live Disconnect Alert switches, and Post-Cleanup After Live with `attemptBadge` (`1`/`2`/`3`).
 */
class LiveTranscriptionActivity : BaseSettingsActivity() {

    private lateinit var bodyContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)

        bodyContainer = vertical(0, 0)
        setOneUiScreenContent(
            title = "Live transcription",
            showBack = true,
            icon = R.drawable.ic_settings_live_stream,
            content = bodyContainer,
        )
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)
        renderScreen()
    }

    private fun currentConfig(): LiveTranscriptionConfig = LiveTranscriptionConfigStore.load(this)

    private fun saveConfig(config: LiveTranscriptionConfig) {
        LiveTranscriptionConfigStore.save(this, config)
        renderScreen()
    }

    private fun hasGeminiKey(config: LiveTranscriptionConfig): Boolean =
        ApiKeyVaultStore.hasSecret(this, config.effectiveVaultKeyId(), config.providerId) ||
            ProviderCredentialStore.isConfiguredForKind(this, ProviderChainStore.load(this), ProviderKind.GEMINI)

    private fun hasSlotKey(slot: LivePostCleanupSlot): Boolean =
        ApiKeyVaultStore.hasSecret(this, slot.vaultKeyId, slot.providerId)

    private fun renderScreen() {
        bodyContainer.removeAllViews()
        val config = currentConfig()
        val geminiKeyPresent = hasGeminiKey(config)
        val effectiveEnabled = config.enabled && geminiKeyPresent

        // 1. Master switch (key-gated: blocks ON without Gemini key and prompts/routes to API keys)
        val masterSwitch = MaterialSwitch(this).apply {
            isChecked = effectiveEnabled
            isClickable = false
        }
        bodyContainer.addView(
            settingsRow(
                title = "Live cloud transcription",
                subtitle = when {
                    effectiveEnabled -> "On \u00b7 Streams audio while you speak (${config.modelId})"
                    !geminiKeyPresent -> "Off \u00b7 Add a Gemini key in API keys to turn on"
                    else -> "Off \u00b7 Uses batch transcription after you stop speaking"
                },
                widget = masterSwitch,
            ) {
                if (!effectiveEnabled) {
                    if (!geminiKeyPresent) {
                        toast("Add a Gemini API key in API keys first")
                        openVaultForProvider(config.providerId)
                    } else {
                        saveConfig(config.copy(enabled = true, providerLocked = true, modelLocked = true))
                    }
                } else {
                    saveConfig(config.copy(enabled = false))
                }
            }
        )

        // 2. Live Engine section
        bodyContainer.addView(sectionHeader("Live engine"))
        addLiveEngineRows(config, geminiKeyPresent)

        // 3. Flat 9:00 cutoff guard notice (NO pill)
        bodyContainer.addView(sectionHeader("9:00 live cutoff guard"))
        bodyContainer.addView(
            explainer(
                "Gemini Live WebSocket sessions enforce a 10:00 server cap. Rhema Cadence proactively " +
                    "stops the live stream at 9:00 (${LiveTranscriptionConfig.LIVE_CUTOFF_GUARD_SEC}s) " +
                    "and seamlessly finishes over Batch STT from your parallel local recording so no audio is lost."
            )
        )

        // 4. Smart Live Disconnect Alert section
        bodyContainer.addView(sectionHeader("Smart Live Disconnect Alert"))
        bodyContainer.addView(
            explainer(
                "If the live connection drops mid-dictation, parallel local recording continues " +
                    "uninterrupted for a clean full-audio Batch STT handoff on Stop."
            )
        )
        val alertSwitch = MaterialSwitch(this).apply {
            isChecked = config.smartDisconnectAlertEnabled
            isClickable = false
        }
        bodyContainer.addView(
            settingsRow(
                title = "Show live disconnect alert",
                subtitle = "Notify immediately when switching to full-audio Batch fallback",
                widget = alertSwitch,
            ) {
                saveConfig(config.copy(smartDisconnectAlertEnabled = !config.smartDisconnectAlertEnabled))
            }
        )
        val hapticSwitch = MaterialSwitch(this).apply {
            isChecked = config.smartDisconnectHapticEnabled
            isClickable = false
        }
        bodyContainer.addView(
            settingsRow(
                title = "Haptic pulse on disconnect",
                subtitle = "Subtle vibration cue while local recording keeps running",
                widget = hapticSwitch,
            ) {
                saveConfig(config.copy(smartDisconnectHapticEnabled = !config.smartDisconnectHapticEnabled))
            }
        )

        // 5. Post-Cleanup After Live section
        bodyContainer.addView(sectionHeader("Post-Cleanup After Live"))
        addPostCleanupSection(config)
    }

    private fun addLiveEngineRows(config: LiveTranscriptionConfig, hasKey: Boolean) {
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val accent = attrColor(com.google.android.material.R.attr.colorPrimary)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val providerDescriptor = ProviderCapabilityRegistry.forId(config.providerId) ?: ProviderCapabilityRegistry.GEMINI
        val providerLockedEffective = config.providerLocked && hasKey
        val modelLockedEffective = config.modelLocked && hasKey

        val providerStatus = when {
            !hasKey -> "No key in vault"
            providerLockedEffective -> "Active"
            else -> "Unlocked"
        }
        val providerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(4), dp(16), dp(4))
        }
        providerRow.addView(
            OneUiComponents.flatSelectorContent(
                context = this,
                label = "Provider",
                valueText = providerDescriptor.displayName,
                statusText = providerStatus,
                statusColor = if (providerLockedEffective) lockedGreen else secondaryText,
                placeholder = "Select live provider",
            ) { openLiveProviderPicker(config) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(4)
                }
            }
        )
        providerRow.addView(
            OneUiComponents.lockCircle(
                context = this,
                locked = providerLockedEffective,
                enabled = true,
                contentDescription = "Lock Live provider",
            ) {
                if (!hasKey) {
                    toast("Add a Gemini API key in API keys first")
                    openVaultForProvider(config.providerId)
                } else {
                    saveConfig(config.copy(providerLocked = !config.providerLocked))
                }
            }
        )
        bodyContainer.addView(providerRow)
        bodyContainer.addView(hairlineDivider(24, 24))

        val modelDescriptor = ModelCapabilityClassifier.curatedModelById(config.providerId, config.modelId)
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(4), dp(16), dp(4))
        }
        modelRow.addView(
            OneUiComponents.flatSelectorContent(
                context = this,
                label = "Live model",
                valueText = modelDescriptor?.displayName ?: config.modelId,
                placeholder = "Select live model",
            ) { openLiveModelPicker(config) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(4)
                }
            }
        )
        modelRow.addView(
            OneUiComponents.lockCircle(
                context = this,
                locked = modelLockedEffective,
                enabled = true,
                contentDescription = "Lock Live model",
            ) {
                if (!hasKey) {
                    toast("Add a Gemini API key in API keys first")
                    openVaultForProvider(config.providerId)
                } else {
                    saveConfig(config.copy(modelLocked = !config.modelLocked))
                }
            }
        )
        bodyContainer.addView(modelRow)

        // Flat hairline Verbatim | Smart segmented selector (NO pills)
        if (config.supportsModeToggle()) {
            bodyContainer.addView(hairlineDivider(24, 24))
            val modeWrap = vertical(0).apply {
                setPadding(dp(24), dp(12), dp(24), dp(8))
            }
            modeWrap.addView(TextView(this).apply {
                text = "Live transcription mode"
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(0, 0, 0, dp(6))
            })
            val segmented = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val tabs = listOf(
                LiveTranscriptionMode.VERBATIM to "Verbatim",
                LiveTranscriptionMode.SMART to "Smart",
            )
            tabs.forEachIndexed { idx, (mode, label) ->
                val selected = config.mode == mode
                val tabCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
                    isClickable = true
                    isFocusable = true
                    OneUiComponents.applySelectableRipple(this)
                    setOnClickListener { saveConfig(config.copy(mode = mode)) }
                }
                tabCol.addView(TextView(this).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) accent else secondaryText)
                    setPadding(0, dp(10), 0, dp(10))
                })
                tabCol.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LP_MATCH, if (selected) dp(2) else 1)
                    setBackgroundColor(if (selected) accent else OneUiComponents.hairlineColor(this@LiveTranscriptionActivity))
                })
                segmented.addView(tabCol)
                if (idx == 0) {
                    segmented.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(1, LP_MATCH).apply {
                            topMargin = dp(6)
                            bottomMargin = dp(6)
                        }
                        setBackgroundColor(OneUiComponents.hairlineColor(this@LiveTranscriptionActivity))
                    })
                }
            }
            modeWrap.addView(segmented)
            modeWrap.addView(TextView(this).apply {
                text = if (config.mode == LiveTranscriptionMode.VERBATIM) {
                    "Verbatim captures exact spoken words with minimal rewriting."
                } else {
                    "Smart cleans filler words and light punctuation directly in the live stream."
                }
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(0, dp(8), 0, 0)
            })
            bodyContainer.addView(modeWrap)
        }
    }

    private fun addPostCleanupSection(config: LiveTranscriptionConfig) {
        val postSwitch = MaterialSwitch(this).apply {
            isChecked = config.postCleanupEnabled
            isClickable = false
        }
        bodyContainer.addView(
            settingsRow(
                title = "Post-Cleanup After Live",
                subtitle = if (config.postCleanupEnabled) {
                    "On \u00b7 Preview/buffer during speech; cleaned on Stop with raw live text fallback"
                } else {
                    "Off \u00b7 Live transcript injects directly as you speak"
                },
                widget = postSwitch,
            ) {
                saveConfig(config.copy(postCleanupEnabled = !config.postCleanupEnabled))
            }
        )

        val budgetSlots = config.postCleanupSlots.map { slot ->
            AttemptBudgetCalculator.BudgetSlot(
                id = slot.id,
                label = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId,
                locked = slot.locked && slot.modelId.isNotBlank() && slot.modelId != ProviderChainEntry.SKIP_MODEL_ID && hasSlotKey(slot),
                attempts = slot.attempts,
            )
        }

        bodyContainer.addView(
            OneUiComponents.executionOrderRow(
                context = this,
                title = "Live Post-Cleanup execution order",
                orderText = EffectiveRouting.livePostCleanupExecutionOrder(config) { hasSlotKey(it) },
                usedAttempts = AttemptBudgetCalculator.usedAttempts(budgetSlots),
                maxAttempts = AttemptBudgetCalculator.MAX_CLOUD_ATTEMPTS_PER_LANE,
            )
        )

        config.postCleanupSlots.forEachIndexed { index, slot ->
            bodyContainer.addView(hairlineDivider(24, 24))
            bodyContainer.addView(buildPostCleanupSlotBlock(config, slot, index, budgetSlots))
        }

        val atCap = config.postCleanupSlots.size >= LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS
        bodyContainer.addView(hairlineDivider(24, 24))
        bodyContainer.addView(
            OneUiComponents.flatActionRow(
                context = this,
                label = if (atCap) "Post-Cleanup slots full (3/3)" else "+ Add cleanup slot",
                subtitle = "${config.postCleanupSlots.size}/${LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS} slots \u00b7 Shared 3-attempt pool",
                enabled = !atCap,
            ) {
                openAddPostCleanupSlotPicker(config)
            }
        )
    }

    private fun buildPostCleanupSlotBlock(
        config: LiveTranscriptionConfig,
        slot: LivePostCleanupSlot,
        index: Int,
        budgetSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ): View {
        val dangerColor = OneUiComponents.resColor(this, R.color.oneui_danger_red)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val hasKey = hasSlotKey(slot)
        val slotLockedEffective = slot.locked && hasKey
        val providerName = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId

        val block = vertical(0).apply {
            setPadding(dp(24), dp(10), dp(16), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "${index + 1}. $providerName"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        header.addView(TextView(this).apply {
            text = if (!hasKey) "No key in vault" else if (slotLockedEffective) "Active" else "Unlocked"
            textSize = 11f
            setTextColor(if (slotLockedEffective) lockedGreen else secondaryText)
            setPadding(0, 0, dp(10), 0)
        })
        header.addView(TextView(this).apply {
            text = "\u2715"
            textSize = 14f
            contentDescription = "Remove slot ${index + 1}"
            setTextColor(dangerColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(32))
            OneUiComponents.applySelectableRipple(this)
            setOnClickListener {
                val remaining = config.postCleanupSlots.toMutableList().apply { removeAt(index) }
                saveConfig(config.copy(postCleanupSlots = remaining))
            }
        })
        block.addView(header)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(
            OneUiComponents.flatSelectorContent(
                context = this,
                label = "Cleanup model",
                valueText = slot.modelId.takeIf { it.isNotBlank() },
                placeholder = "Pick cleanup model",
            ) { openPostCleanupModelPicker(config, index, slot) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(4)
                }
            }
        )

        val allowedAttempts = AttemptBudgetCalculator.allowedAttemptsForSlot(budgetSlots, slot.id)
        controls.addView(
            OneUiComponents.attemptBadge(
                context = this,
                attempts = slot.attempts,
                allowedAttempts = if (allowedAttempts.isEmpty()) listOf(slot.attempts) else allowedAttempts,
                enabled = hasKey && (slotLockedEffective || allowedAttempts.isNotEmpty()),
            ) { selectedAttempts ->
                val updated = config.postCleanupSlots.toMutableList()
                updated[index] = slot.copy(attempts = selectedAttempts)
                saveConfig(config.copy(postCleanupSlots = updated))
            }
        )

        controls.addView(
            OneUiComponents.lockCircle(
                context = this,
                locked = slotLockedEffective,
                enabled = true,
                contentDescription = "Lock post-cleanup slot ${index + 1}",
            ) {
                if (slotLockedEffective) {
                    val updated = config.postCleanupSlots.toMutableList()
                    updated[index] = slot.copy(locked = false)
                    saveConfig(config.copy(postCleanupSlots = updated))
                } else {
                    if (!hasKey) {
                        toast("Add a $providerName API key in API keys first")
                        openVaultForProvider(slot.providerId)
                        return@lockCircle
                    }
                    if (slot.modelId.isBlank()) {
                        toast("Select a cleanup model first")
                        return@lockCircle
                    }
                    when (val lockResult = AttemptBudgetCalculator.canLockSlot(budgetSlots, slot.id, slot.attempts)) {
                        is AttemptBudgetCalculator.LockResult.Blocked -> toast(lockResult.reason)
                        is AttemptBudgetCalculator.LockResult.Allowed -> {
                            val updated = config.postCleanupSlots.toMutableList()
                            updated[index] = slot.copy(locked = true, attempts = lockResult.assignedAttempts)
                            if (lockResult.wasClamped) {
                                toast("Adjusted to ${lockResult.assignedAttempts} to fit the 3-attempt pool")
                            }
                            saveConfig(config.copy(postCleanupSlots = updated))
                        }
                    }
                }
            }
        )
        block.addView(controls)

        return block
    }

    private fun openLiveProviderPicker(config: LiveTranscriptionConfig) {
        val providers = ProviderCapabilityRegistry.providersForLane(CapabilityLane.LIVE_STT)
        val items = providers.map { p ->
            OneUiComponents.SearchablePickerSheet.PickerItem(
                id = p.id,
                title = p.displayName,
                subtitle = "Streaming WebSocket audio transcription",
                badges = listOf("Live STT"),
                selected = p.id == config.providerId,
            )
        }
        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Select live provider",
            items = items,
            searchHint = "Search live providers...",
            onSelect = { item ->
                saveConfig(
                    config.copy(
                        providerId = item.id,
                        vaultKeyId = ProviderCapabilityRegistry.canonicalVaultKeyId(item.id),
                    )
                )
            },
        )
    }

    private fun openLiveModelPicker(config: LiveTranscriptionConfig) {
        val models = ModelCapabilityClassifier.curatedModelsFor(config.providerId, CapabilityLane.LIVE_STT)
        val items = models.map { m ->
            OneUiComponents.SearchablePickerSheet.PickerItem(
                id = m.id,
                title = m.displayName,
                subtitle = m.description,
                metaLine = m.id,
                badges = m.badges,
                selected = m.id == config.modelId,
            )
        }
        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Select live model",
            items = items,
            searchHint = "Search live models...",
            onSelect = { item ->
                val lockModel = hasGeminiKey(config)
                saveConfig(config.copy(modelId = item.id, modelLocked = lockModel))
            },
        )
    }

    private fun openAddPostCleanupSlotPicker(config: LiveTranscriptionConfig) {
        val providers = ProviderCapabilityRegistry.providersForLane(CapabilityLane.CLEANUP)
        val items = providers.map { p ->
            val hasKey = ApiKeyVaultStore.hasSecret(this, null, p.id)
            OneUiComponents.SearchablePickerSheet.PickerItem(
                id = p.id,
                title = p.displayName,
                subtitle = if (hasKey) "Key saved in vault" else "No key in vault",
                badges = listOf("Cleanup"),
            )
        }
        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Add post-cleanup provider",
            items = items,
            searchHint = "Search cleanup providers...",
            onSelect = { item ->
                val defaultModel = ModelCapabilityClassifier.curatedModelsFor(item.id, CapabilityLane.CLEANUP)
                    .firstOrNull()?.id ?: ""
                val vaultEntry = ApiKeyVaultStore.findLockedForProvider(this, item.id)
                    ?: ApiKeyVaultStore.findForProvider(this, item.id).firstOrNull()
                val newSlot = LivePostCleanupSlot(
                    id = "live_cleanup_${UUID.randomUUID().toString().take(8)}",
                    providerId = item.id,
                    modelId = defaultModel,
                    vaultKeyId = vaultEntry?.id ?: ProviderCapabilityRegistry.canonicalVaultKeyId(item.id),
                    baseUrlOverride = vaultEntry?.customBaseUrl,
                    attempts = AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
                    locked = false,
                )
                saveConfig(config.copy(postCleanupSlots = config.postCleanupSlots + newSlot))
            },
        )
    }

    private fun openPostCleanupModelPicker(
        config: LiveTranscriptionConfig,
        index: Int,
        slot: LivePostCleanupSlot,
    ) {
        fun toPickerItems(models: List<ModelDescriptor>) = models.map { m ->
            val metaParts = listOfNotNull(m.id, m.priceSummary(), m.contextSummary())
            OneUiComponents.SearchablePickerSheet.PickerItem(
                id = m.id,
                title = m.displayName,
                subtitle = m.description,
                metaLine = metaParts.joinToString(" \u00b7 "),
                badges = m.badges,
                selected = m.id == slot.modelId,
            )
        }

        val initialModels = LiveModelCatalogStore.modelsFor(this, slot.providerId, CapabilityLane.CLEANUP, slot.baseUrlOverride)
        val apiKey = ApiKeyVaultStore.resolveSecret(this, slot.vaultKeyId, slot.providerId)

        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Select cleanup model",
            items = toPickerItems(initialModels),
            searchHint = "Search models...",
            onRefreshRequested = { onDone ->
                LiveModelCatalogStore.refreshAsync(
                    context = this,
                    providerId = slot.providerId,
                    apiKey = apiKey,
                    customBaseUrl = slot.baseUrlOverride,
                    force = true,
                ) { res ->
                    val merged = LiveModelCatalogStore.modelsFor(this, slot.providerId, CapabilityLane.CLEANUP, slot.baseUrlOverride)
                    val msg = if (res.success) "Refreshed (${merged.size} models)" else res.errorMessage
                    onDone(toPickerItems(merged), msg)
                }
            },
            allowCustomModelId = true,
            customModelWarning = { typed ->
                ModelCapabilityClassifier.wrongLaneWarning(slot.providerId, typed, CapabilityLane.CLEANUP)
            },
            onSelect = { item ->
                val updated = config.postCleanupSlots.toMutableList()
                updated[index] = slot.copy(modelId = item.id)
                saveConfig(config.copy(postCleanupSlots = updated))
            },
            onCustomIdSelected = { customId ->
                val updated = config.postCleanupSlots.toMutableList()
                updated[index] = slot.copy(modelId = customId)
                saveConfig(config.copy(postCleanupSlots = updated))
            },
        )
    }

    private fun openVaultForProvider(providerId: String) {
        startActivity(
            Intent(this, ApiKeysVaultActivity::class.java)
                .putExtra(ApiKeysVaultActivity.EXTRA_FOCUS_PROVIDER_ID, providerId)
        )
    }

    companion object {
        fun subtitle(context: Context): String {
            val cfg = LiveTranscriptionConfigStore.load(context)
            val hasKey = ApiKeyVaultStore.hasSecret(context, cfg.effectiveVaultKeyId(), cfg.providerId) ||
                ProviderCredentialStore.isConfiguredForKind(context, ProviderChainStore.load(context), ProviderKind.GEMINI)
            return liveTranscriptionMainRowSubtitleText(cfg.enabled, cfg.modelId, hasKey)
        }
    }
}
