package com.trevornk.ramblr

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.UUID

/**
 * Dedicated Live Transcription configuration screen (Phase 3A.1).
 *
 * Manages:
 *  - Master Live Cloud Transcription switch (bidirectionally synced with [CloudLiveToggle])
 *  - Live Engine card (Gemini only — zero dead buttons for unimplemented engines; strictly
 *    excludes `gemini-3.5-live-translate-preview`)
 *  - Verbatim / Smart mode toggle for `gemini-3.5-transcribe-live`
 *  - Prominent 9:00 live cutoff guard notice
 *  - Smart Live Disconnect Alert + haptic toggles
 *  - Post-Cleanup After Live toggle, Execution Order strip, and up to 3 slots sharing the
 *    3-attempt pool via [AttemptBudgetCalculator]
 */
class LiveTranscriptionActivity : BaseSettingsActivity() {

    private lateinit var bodyContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)

        bodyContainer = vertical(0, 0)
        setOneUiScreenContent(
            title = "Live transcription",
            showBack = true,
            subtitle = "Streaming audio & post-cleanup",
            content = bodyContainer,
        )
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        renderScreen()
    }

    private fun currentConfig(): LiveTranscriptionConfig = LiveTranscriptionConfigStore.load(this)

    private fun saveConfig(config: LiveTranscriptionConfig) {
        LiveTranscriptionConfigStore.save(this, config)
        renderScreen()
    }

    private fun renderScreen() {
        bodyContainer.removeAllViews()
        val config = currentConfig()

        // 1. Master switch card
        val masterCard = oneUiCard()
        val masterSwitch = MaterialSwitch(this).apply {
            isChecked = config.enabled
            isClickable = false
        }
        masterCard.addView(
            settingsRow(
                title = "Live cloud transcription",
                subtitle = if (config.enabled) {
                    "On \u2022 Streams audio while you speak (${config.modelId})"
                } else {
                    "Off \u2022 Uses batch transcription after you stop speaking"
                },
                widget = masterSwitch,
            ) {
                saveConfig(config.copy(enabled = !config.enabled))
            }
        )
        bodyContainer.addView(masterCard)

        // 2. Live Engine card (Gemini only, zero dead streaming engine buttons)
        bodyContainer.addView(buildLiveEngineCard(config))

        // 3. Prominent 9:00 live cutoff guard notice card
        bodyContainer.addView(buildCutoffGuardCard())

        // 4. Smart Live Disconnect Alert card
        bodyContainer.addView(buildSmartDisconnectCard(config))

        // 5. Post-Cleanup After Live card (max 3 slots, shared 3-attempt pool)
        bodyContainer.addView(buildPostCleanupCard(config))
    }

    private fun buildLiveEngineCard(config: LiveTranscriptionConfig): View {
        val card = oneUiCard(padH = 16, padV = 14)
        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val accent = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val warningAmber = OneUiComponents.resColor(this, R.color.oneui_warning_amber)

        card.addView(TextView(this).apply {
            text = "Live Engine"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
            setPadding(0, 0, 0, dp(8))
        })

        // Provider row + green lock-in
        val providerDescriptor = ProviderCapabilityRegistry.forId(config.providerId) ?: ProviderCapabilityRegistry.GEMINI
        val providerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                bottomMargin = dp(8)
            }
        }
        providerRow.addView(
            OneUiComponents.selectorBox(
                context = this,
                label = "Provider",
                valueText = providerDescriptor.displayName,
                badgeText = "Live STT",
                placeholder = "Select live provider",
            ) { openLiveProviderPicker(config) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(8)
                }
            }
        )
        val geminiSecret = ApiKeyVaultStore.resolveSecret(this, config.effectiveVaultKeyId(), config.providerId)
        val hasGeminiKey = geminiSecret.isNotBlank()
        providerRow.addView(
            OneUiComponents.lockInButton(
                context = this,
                locked = config.providerLocked && hasGeminiKey,
                enabled = true,
                contentDescription = "Lock Live provider",
            ) {
                if (!hasGeminiKey && !config.providerLocked) {
                    toast("Add a Gemini API key in API keys first")
                    openVaultForProvider(config.providerId)
                } else {
                    saveConfig(config.copy(providerLocked = !config.providerLocked))
                }
            }
        )
        card.addView(providerRow)

        // Shared Gemini vault key status bar (tap-through to ApiKeysVaultActivity if unset)
        val keyStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OneUiComponents.roundedBg(
                fillColor = OneUiComponents.resColor(this@LiveTranscriptionActivity, R.color.oneui_pill_surface),
                radiusPx = dp(12).toFloat(),
                strokeWidthPx = dp(1),
                strokeColor = OneUiComponents.resColor(this@LiveTranscriptionActivity, R.color.oneui_card_border),
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener { openVaultForProvider(config.providerId) }
        }
        keyStatusRow.addView(TextView(this).apply {
            text = if (hasGeminiKey) {
                "Shared Gemini vault key \u2022 ${ProviderCredentialStore.maskForDisplay(geminiSecret)}"
            } else {
                "No Gemini key in vault \u2014 tap to add"
            }
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (hasGeminiKey) lockedGreen else warningAmber)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        keyStatusRow.addView(TextView(this).apply {
            text = if (hasGeminiKey) "Manage" else "Add key"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(accent)
        })
        card.addView(keyStatusRow)

        // Model row + green lock-in
        val modelDescriptor = ModelCapabilityClassifier.curatedModelById(config.providerId, config.modelId)
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                bottomMargin = dp(10)
            }
        }
        modelRow.addView(
            OneUiComponents.selectorBox(
                context = this,
                label = "Live Model",
                valueText = modelDescriptor?.displayName ?: config.modelId,
                badgeText = if (config.modelId == LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID) "Default" else null,
                placeholder = "Select live model",
            ) { openLiveModelPicker(config) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(8)
                }
            }
        )
        modelRow.addView(
            OneUiComponents.lockInButton(
                context = this,
                locked = config.modelLocked,
                enabled = true,
                contentDescription = "Lock Live model",
            ) {
                saveConfig(config.copy(modelLocked = !config.modelLocked))
            }
        )
        card.addView(modelRow)

        // VERBATIM / SMART mode toggle when gemini-3.5-transcribe-live is selected
        if (config.supportsModeToggle()) {
            card.addView(TextView(this).apply {
                text = "Live transcription mode"
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(0, dp(2), 0, dp(6))
            })
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            listOf(
                LiveTranscriptionMode.VERBATIM to "Verbatim",
                LiveTranscriptionMode.SMART to "Smart",
            ).forEachIndexed { idx, (mode, label) ->
                val selected = config.mode == mode
                modeRow.addView(TextView(this).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (selected) accent else secondaryText)
                    background = OneUiComponents.roundedBg(
                        fillColor = if (selected) OneUiComponents.resColor(this@LiveTranscriptionActivity, R.color.oneui_accent_cobalt_container)
                        else OneUiComponents.resColor(this@LiveTranscriptionActivity, R.color.oneui_pill_surface),
                        radiusPx = dp(12).toFloat(),
                        strokeWidthPx = dp(1),
                        strokeColor = if (selected) accent else OneUiComponents.resColor(this@LiveTranscriptionActivity, R.color.oneui_card_border),
                    )
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                        if (idx == 0) marginEnd = dp(8)
                    }
                    setOnClickListener { saveConfig(config.copy(mode = mode)) }
                })
            }
            card.addView(modeRow)
            card.addView(TextView(this).apply {
                text = if (config.mode == LiveTranscriptionMode.VERBATIM) {
                    "Verbatim captures exact spoken words with minimal rewriting."
                } else {
                    "Smart cleans filler words and light punctuation directly in the live stream."
                }
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(0, dp(6), 0, 0)
            })
        }

        return card
    }

    private fun buildCutoffGuardCard(): View {
        val card = oneUiCard(padH = 16, padV = 14)
        val amber = OneUiComponents.resColor(this, R.color.oneui_warning_amber)
        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "9:00 live cutoff guard"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        headerRow.addView(
            OneUiComponents.pillBadge(
                this,
                "9:00 max",
                textColor = amber,
                bgColor = OneUiComponents.resColor(this, R.color.oneui_pill_surface),
                borderColor = amber,
            )
        )
        card.addView(headerRow)
        card.addView(TextView(this).apply {
            text = "Gemini Live WebSocket sessions enforce a 10:00 server cap. Ramblr proactively " +
                "stops the live stream at 9:00 (${LiveTranscriptionConfig.LIVE_CUTOFF_GUARD_SEC}s) " +
                "and seamlessly finishes over Batch STT from your parallel local recording so no audio is lost."
            textSize = 13f
            setTextColor(secondaryText)
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    private fun buildSmartDisconnectCard(config: LiveTranscriptionConfig): View {
        val card = oneUiCard()
        card.addView(TextView(this).apply {
            text = "Smart Live Disconnect Alert"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            setPadding(dp(24), dp(14), dp(24), dp(4))
        })
        card.addView(TextView(this).apply {
            text = "If the live connection drops mid-dictation, parallel local recording continues " +
                "uninterrupted for a clean full-audio Batch STT handoff on Stop (no partial transcript stitching)."
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(6))
        })

        val alertSwitch = MaterialSwitch(this).apply {
            isChecked = config.smartDisconnectAlertEnabled
            isClickable = false
        }
        card.addView(
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
        card.addView(
            settingsRow(
                title = "Haptic pulse on disconnect",
                subtitle = "Subtle vibration cue while local recording keeps running",
                widget = hapticSwitch,
            ) {
                saveConfig(config.copy(smartDisconnectHapticEnabled = !config.smartDisconnectHapticEnabled))
            }
        )
        return card
    }

    private fun buildPostCleanupCard(config: LiveTranscriptionConfig): View {
        val card = oneUiCard(padH = 16, padV = 14)
        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val titleCol = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                marginEnd = dp(12)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = "Post-Cleanup After Live"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
        })
        titleCol.addView(TextView(this).apply {
            text = if (config.postCleanupEnabled) {
                "ON \u2022 Preview/buffer only during speech; cleaned on Stop with raw live text fallback."
            } else {
                "OFF \u2022 Live transcript injects directly as you speak with zero post-pass."
            }
            textSize = 12f
            setTextColor(secondaryText)
            setPadding(0, dp(3), 0, 0)
        })
        val postSwitch = MaterialSwitch(this).apply {
            isChecked = config.postCleanupEnabled
            setOnCheckedChangeListener { _, checked ->
                saveConfig(config.copy(postCleanupEnabled = checked))
            }
        }
        toggleRow.addView(titleCol)
        toggleRow.addView(postSwitch)
        card.addView(toggleRow)

        val budgetSlots = config.postCleanupSlots.map { slot ->
            AttemptBudgetCalculator.BudgetSlot(
                id = slot.id,
                label = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId,
                locked = slot.locked && slot.modelId.isNotBlank() && slot.modelId != ProviderChainEntry.SKIP_MODEL_ID,
                attempts = slot.attempts,
            )
        }

        // Live Execution Order strip
        card.addView(
            OneUiComponents.executionOrderStrip(
                context = this,
                title = "Live Post-Cleanup Execution Order",
                orderText = EffectiveRouting.livePostCleanupExecutionOrder(config),
                usedAttempts = AttemptBudgetCalculator.usedAttempts(budgetSlots),
                maxAttempts = AttemptBudgetCalculator.MAX_CLOUD_ATTEMPTS_PER_LANE,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
        )

        // Up to 3 Post-Cleanup slots
        config.postCleanupSlots.forEachIndexed { index, slot ->
            card.addView(buildPostCleanupSlotRow(config, slot, index, budgetSlots))
        }

        val atCap = config.postCleanupSlots.size >= LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS
        card.addView(
            OneUiComponents.cobaltButton(
                context = this,
                label = if (atCap) "Post-Cleanup Slots Full (3/3)" else "+ Add Post-Cleanup Slot",
                subtitle = "${config.postCleanupSlots.size}/${LiveTranscriptionConfig.MAX_LIVE_POST_CLEANUP_SLOTS} slots \u2022 Shared 3-attempt pool",
                enabled = !atCap,
            ) {
                openAddPostCleanupSlotPicker(config)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    topMargin = dp(6)
                }
            }
        )

        return card
    }

    private fun buildPostCleanupSlotRow(
        config: LiveTranscriptionConfig,
        slot: LivePostCleanupSlot,
        index: Int,
        budgetSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ): View {
        val pillSurface = OneUiComponents.resColor(this, R.color.oneui_pill_surface)
        val borderColor = OneUiComponents.resColor(this, R.color.oneui_card_border)
        val dangerColor = OneUiComponents.resColor(this, R.color.oneui_danger_red)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OneUiComponents.roundedBg(pillSurface, dp(16).toFloat(), dp(1), borderColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                bottomMargin = dp(8)
            }
        }

        val providerName = ProviderCapabilityRegistry.forId(slot.providerId)?.displayName ?: slot.providerId
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        header.addView(TextView(this).apply {
            text = "${index + 1}. $providerName"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        header.addView(TextView(this).apply {
            text = "Remove"
            textSize = 12f
            setTextColor(dangerColor)
            setPadding(dp(8), dp(2), dp(2), dp(2))
            setOnClickListener {
                val remaining = config.postCleanupSlots.toMutableList().apply { removeAt(index) }
                saveConfig(config.copy(postCleanupSlots = remaining))
            }
        })
        box.addView(header)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(
            OneUiComponents.selectorBox(
                context = this,
                label = "Cleanup Model",
                valueText = slot.modelId.takeIf { it.isNotBlank() },
                placeholder = "Pick cleanup model",
            ) { openPostCleanupModelPicker(config, index, slot) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(8)
                }
            }
        )

        val allowedAttempts = AttemptBudgetCalculator.allowedAttemptsForSlot(budgetSlots, slot.id)
        controls.addView(
            OneUiComponents.attemptsPill(
                context = this,
                attempts = slot.attempts,
                allowedAttempts = if (allowedAttempts.isEmpty()) listOf(slot.attempts) else allowedAttempts,
                enabled = slot.locked || allowedAttempts.isNotEmpty(),
            ) { selectedAttempts ->
                val updated = config.postCleanupSlots.toMutableList()
                updated[index] = slot.copy(attempts = selectedAttempts)
                saveConfig(config.copy(postCleanupSlots = updated))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                    marginEnd = dp(8)
                }
            }
        )

        controls.addView(
            OneUiComponents.lockInButton(
                context = this,
                locked = slot.locked,
                enabled = true,
                contentDescription = "Lock post-cleanup slot ${index + 1}",
            ) {
                if (slot.locked) {
                    val updated = config.postCleanupSlots.toMutableList()
                    updated[index] = slot.copy(locked = false)
                    saveConfig(config.copy(postCleanupSlots = updated))
                } else {
                    if (slot.modelId.isBlank()) {
                        toast("Select a cleanup model first")
                        return@lockInButton
                    }
                    when (val lockResult = AttemptBudgetCalculator.canLockSlot(budgetSlots, slot.id, slot.attempts)) {
                        is AttemptBudgetCalculator.LockResult.Blocked -> toast(lockResult.reason)
                        is AttemptBudgetCalculator.LockResult.Allowed -> {
                            val updated = config.postCleanupSlots.toMutableList()
                            updated[index] = slot.copy(locked = true, attempts = lockResult.assignedAttempts)
                            if (lockResult.wasClamped) {
                                toast("Adjusted to ${lockResult.assignedAttempts}x to fit the 3-attempt pool")
                            }
                            saveConfig(config.copy(postCleanupSlots = updated))
                        }
                    }
                }
            }
        )
        box.addView(controls)

        val secret = ApiKeyVaultStore.resolveSecret(this, slot.vaultKeyId, slot.providerId)
        if (secret.isBlank()) {
            box.addView(TextView(this).apply {
                text = "No $providerName key in vault \u2014 tap to add"
                textSize = 11f
                setTextColor(OneUiComponents.resColor(this@LiveTranscriptionActivity, R.color.oneui_warning_amber))
                setPadding(dp(2), dp(6), 0, 0)
                setOnClickListener { openVaultForProvider(slot.providerId) }
            })
        } else {
            box.addView(TextView(this).apply {
                text = "Vault key \u2022 ${ProviderCredentialStore.maskForDisplay(secret)}"
                textSize = 11f
                setTextColor(secondaryText)
                setPadding(dp(2), dp(6), 0, 0)
            })
        }

        return box
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
            title = "Select Live Provider",
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
            title = "Select Live Model",
            items = items,
            searchHint = "Search live models...",
            onSelect = { item ->
                saveConfig(config.copy(modelId = item.id, modelLocked = true))
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
                subtitle = if (hasKey) "Vault key ready" else "No key in vault yet",
                badges = listOf("Cleanup"),
            )
        }
        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Add Post-Cleanup Provider",
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
            title = "Select Cleanup Model",
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
}
