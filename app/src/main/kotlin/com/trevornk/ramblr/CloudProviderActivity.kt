package com.trevornk.ramblr

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.util.UUID

/**
 * Unified Samsung One UI AMOLED Cloud Providers screen (Phase 3A.1).
 *
 * Surfaces:
 *  - Shared API Keys Vault summary card -> [ApiKeysVaultActivity]
 *  - Live Batch STT & Batch Cleanup Execution Order strips (`Pool X/3`)
 *  - Unified Batch Provider & Model Cards (up to 10 saved cards, 1-line searchable bottom-sheet
 *    pickers, green lock-in per Provider/STT/Cleanup, tappable `1x`/`2x`/`3x` Attempts pills)
 *  - On-Device Fallback compact status card + fallback switches
 *  - Live Transcription summary card -> [LiveTranscriptionActivity]
 *  - Dictation Mode presets & Mix-and-Match switches
 */
class CloudProviderActivity : BaseSettingsActivity() {

    private lateinit var vaultSummarySub: TextView
    private lateinit var executionOrderContainer: LinearLayout
    private lateinit var chainContainer: LinearLayout
    private lateinit var emptyStateView: View
    private lateinit var addProviderButtonContainer: LinearLayout
    private lateinit var localFallbackStatusView: TextView
    private lateinit var localFallbackSwitch: MaterialSwitch
    private lateinit var localFallbackRowSub: TextView
    private lateinit var cloudFallbackSwitch: MaterialSwitch
    private lateinit var cloudFallbackRowSub: TextView
    private lateinit var liveSummarySub: TextView
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var modeRadioButtons: Map<DictationMode, MaterialRadioButton>
    private lateinit var modeCustomNote: TextView
    private lateinit var cloudTranscriptionSwitch: MaterialSwitch
    private lateinit var cloudTranscriptionRowSub: TextView
    private lateinit var cloudCleanupSwitch: MaterialSwitch
    private lateinit var cloudCleanupRowSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)

        val root = vertical(0, 0)

        // 1. Shared API Keys Vault card
        val vaultCard = oneUiCard()
        val vaultRow = settingsRow(
            title = "API keys vault",
            subtitle = "Manage keys",
            widget = OneUiComponents.pillBadge(
                context = this,
                label = "Manage keys",
                textColor = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt),
                bgColor = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt_container),
            ),
        ) {
            startActivity(Intent(this, ApiKeysVaultActivity::class.java))
        }
        vaultSummarySub = vaultRow.findViewWithTag("subtitle")
        vaultCard.addView(vaultRow)
        root.addView(vaultCard)

        // 2. Execution Order card (live Batch STT + Batch Cleanup strips)
        val orderCard = oneUiCard(padH = 16, padV = 14)
        orderCard.addView(TextView(this).apply {
            text = "Execution Order"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            setPadding(0, 0, 0, dp(8))
        })
        executionOrderContainer = vertical(0)
        orderCard.addView(executionOrderContainer)
        root.addView(orderCard)

        // 3. Unified Batch Provider & Model Cards
        root.addView(sectionHeader("Batch providers"))
        chainContainer = vertical(0)
        root.addView(chainContainer)

        emptyStateView = oneUiCard(padH = 16, padV = 14).apply {
            addView(TextView(this@CloudProviderActivity).apply {
                text = "No cloud providers saved yet. On-device transcription and cleanup are used instead."
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
            })
        }
        root.addView(emptyStateView)

        addProviderButtonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(12))
        }
        root.addView(addProviderButtonContainer)

        // 4. On-Device Fallback compact status card
        val fallbackCard = oneUiCard()
        val fallbackHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(16), dp(6))
        }
        fallbackHeaderRow.addView(TextView(this).apply {
            text = "On-device fallback"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        val accent = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt)
        fallbackHeaderRow.addView(TextView(this).apply {
            text = "Edit STT"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(accent)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { startActivity(Intent(this@CloudProviderActivity, TranscriptionActivity::class.java)) }
        })
        fallbackHeaderRow.addView(TextView(this).apply {
            text = "Edit Cleanup"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(accent)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { startActivity(Intent(this@CloudProviderActivity, CleanupActivity::class.java)) }
        })
        fallbackCard.addView(fallbackHeaderRow)

        localFallbackStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(6))
        }
        fallbackCard.addView(localFallbackStatusView)

        localFallbackSwitch = MaterialSwitch(this).apply { isClickable = false }
        val localFallbackRow = settingsRow(
            "Fall back to on-device if cloud fails",
            localFallbackSubtitle(),
            localFallbackSwitch,
        ) { onToggleLocalFallback(!localFallbackSwitch.isChecked) }
        localFallbackRowSub = localFallbackRow.findViewWithTag("subtitle")
        fallbackCard.addView(localFallbackRow)

        cloudFallbackSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cloudFallbackRow = settingsRow(
            "Fall back to cloud if on-device fails",
            cloudFallbackSubtitle(),
            cloudFallbackSwitch,
        ) { onToggleCloudFallback(!cloudFallbackSwitch.isChecked) }
        cloudFallbackRowSub = cloudFallbackRow.findViewWithTag("subtitle")
        fallbackCard.addView(cloudFallbackRow)
        root.addView(fallbackCard)

        // 5. Live Transcription card
        val liveCard = oneUiCard()
        val liveRow = settingsRow(
            title = "Live transcription",
            subtitle = "",
            widget = OneUiComponents.pillBadge(
                context = this,
                label = "Configure",
                textColor = accent,
                bgColor = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt_container),
            ),
            details = "The streaming preview model is on-device. With Live cloud transcription on (under Speech recognition) or here, Cloud Live supplies the live text instead.",
        ) {
            startActivity(Intent(this, LiveTranscriptionActivity::class.java))
        }
        liveSummarySub = liveRow.findViewWithTag("subtitle")
        liveCard.addView(liveRow)
        root.addView(liveCard)

        // 6. Dictation Mode & Mix-and-Match card
        val modeCard = oneUiCard()
        modeCard.addView(TextView(this).apply {
            text = "Dictation mode"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            setPadding(dp(24), dp(14), dp(24), dp(4))
        })
        modeCard.addView(TextView(this).apply {
            text = "Pick how your speech is turned into text and cleaned up, or fine-tune per feature."
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(6))
        })

        modeRadioGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(24), dp(6))
        }
        modeRadioButtons = DICTATION_MODE_OPTIONS.associateWith { mode ->
            MaterialRadioButton(this).apply {
                text = DictationModePresets.label(mode)
                id = View.generateViewId()
                minHeight = dp(48)
                buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
                setOnClickListener { applyMode(mode) }
            }
        }
        modeRadioButtons.values.forEach { modeRadioGroup.addView(it) }
        modeCard.addView(modeRadioGroup)

        modeCustomNote = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(8))
            visibility = View.GONE
        }
        modeCard.addView(modeCustomNote)

        modeCard.addView(subsectionHeader("Mix and match", indent = 0))

        cloudTranscriptionSwitch = MaterialSwitch(this).apply { isClickable = false }
        val transcriptionRow = settingsRow(
            "Transcription",
            cloudTranscriptionSubtitle(),
            cloudTranscriptionSwitch,
        ) { onToggleCloudTranscription(!cloudTranscriptionSwitch.isChecked) }
        cloudTranscriptionRowSub = transcriptionRow.findViewWithTag("subtitle")
        modeCard.addView(transcriptionRow)

        cloudCleanupSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cleanupRow = settingsRow(
            "Cleanup",
            cloudCleanupSubtitle(),
            cloudCleanupSwitch,
        ) { onToggleCloudCleanup(!cloudCleanupSwitch.isChecked) }
        cloudCleanupRowSub = cleanupRow.findViewWithTag("subtitle")
        modeCard.addView(cleanupRow)
        root.addView(modeCard)

        setOneUiScreenContent(
            title = "Cloud providers",
            showBack = true,
            subtitle = "Unified batch & live routing",
            content = root,
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun currentChain(): ProviderChain = ProviderChainStore.load(this)

    private fun saveChain(entries: List<ProviderChainEntry>) {
        ProviderChainStore.save(this, ProviderChain(entries))
        refresh()
    }

    private fun refresh() {
        val chain = currentChain()
        refreshVaultSummary()
        refreshExecutionOrder(chain)
        refreshChainCards(chain)
        refreshLocalFallbackSummary()
        refreshLiveSummary()

        cloudTranscriptionSwitch.isChecked = !prefs().getBoolean("use_local", true)
        cloudTranscriptionRowSub.text = cloudTranscriptionSubtitle()

        cloudCleanupSwitch.isChecked = CloudFeatureToggle.cleanupEnabled(this)
        cloudCleanupRowSub.text = cloudCleanupSubtitle()

        localFallbackSwitch.isChecked = DictationModeToggle.allowLocalFallback(this)
        localFallbackRowSub.text = localFallbackSubtitle()
        cloudFallbackSwitch.isChecked = DictationModeToggle.allowCloudFallback(this)
        cloudFallbackRowSub.text = cloudFallbackSubtitle()

        refreshModeSelection()
    }

    private fun refreshVaultSummary() {
        val entries = ApiKeyVaultStore.load(this)
        val lockedReady = entries.filter { it.locked && ProviderCredentialStore.isVaultConfigured(this, it.id) }
        val totalConfigured = entries.count { ProviderCredentialStore.isVaultConfigured(this, it.id) }
        vaultSummarySub.text = when {
            lockedReady.isNotEmpty() -> {
                val names = lockedReady.take(4).joinToString(", ") { it.displayLabel }
                val more = if (lockedReady.size > 4) " +${lockedReady.size - 4}" else ""
                "${lockedReady.size} locked ($names$more)"
            }
            totalConfigured > 0 -> "$totalConfigured saved (unlocked) \u2014 tap to lock"
            else -> "No keys saved yet \u2014 add keys once to reuse across Batch & Live"
        }
    }

    private fun sttBudgetSlots(chain: ProviderChain): List<AttemptBudgetCalculator.BudgetSlot> =
        chain.entries.mapIndexedNotNull { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) null
            else AttemptBudgetCalculator.BudgetSlot(
                id = "card_$idx",
                label = ProviderCapabilityRegistry.displayNameFor(entry),
                locked = entry.isBatchSttActive(),
                attempts = entry.sttAttempts,
            )
        }

    private fun cleanupBudgetSlots(chain: ProviderChain): List<AttemptBudgetCalculator.BudgetSlot> =
        chain.entries.mapIndexedNotNull { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) null
            else AttemptBudgetCalculator.BudgetSlot(
                id = "card_$idx",
                label = ProviderCapabilityRegistry.displayNameFor(entry),
                locked = entry.isBatchCleanupActive(),
                attempts = entry.cleanupAttempts,
            )
        }

    private fun refreshExecutionOrder(chain: ProviderChain) {
        executionOrderContainer.removeAllViews()
        val allowLocalFallback = DictationModeToggle.allowLocalFallback(this)
        val useLocalStt = prefs().getBoolean("use_local", true)
        val localSttReady = LocalTranscriber.availableModels(this).isNotEmpty()
        val postProcessingOn = prefs().getBoolean("use_post_processing", false)
        val cloudCleanupOn = CloudFeatureToggle.cleanupEnabled(this)
        val localCleanupReady = ModelDownloader.localCleanupModelFile(this, LocalCleanupProvider.selectedModel(this)) != null

        val sttSlots = sttBudgetSlots(chain)
        val cleanupSlots = cleanupBudgetSlots(chain)

        executionOrderContainer.addView(
            OneUiComponents.executionOrderStrip(
                context = this,
                title = "Batch STT",
                orderText = EffectiveRouting.batchSttExecutionOrder(
                    chain = chain,
                    allowLocalFallback = allowLocalFallback,
                    localAvailable = localSttReady,
                    useLocalTranscription = useLocalStt,
                ),
                usedAttempts = AttemptBudgetCalculator.usedAttempts(sttSlots),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    bottomMargin = dp(8)
                }
            }
        )

        executionOrderContainer.addView(
            OneUiComponents.executionOrderStrip(
                context = this,
                title = "Batch Cleanup",
                orderText = EffectiveRouting.batchCleanupExecutionOrder(
                    chain = chain,
                    postProcessingEnabled = postProcessingOn,
                    cloudCleanupEnabled = cloudCleanupOn,
                    allowLocalFallback = allowLocalFallback,
                    localAvailable = localCleanupReady,
                ),
                usedAttempts = AttemptBudgetCalculator.usedAttempts(cleanupSlots),
            )
        )
    }

    private fun refreshLocalFallbackSummary() {
        val localStt = TranscriptionActivity.resolveInstalledTranscriptionModel(this, persistIfBlank = false)
        val localCleanupModel = LocalCleanupProvider.selectedModel(this)
        val localCleanupInstalled = ModelDownloader.localCleanupModelFile(this, localCleanupModel) != null
        val sttStatus = if (localStt != null) "STT: ${localStt.name} (ready)" else "STT: not installed"
        val cleanupStatus = if (localCleanupInstalled) "Cleanup: ${localCleanupModel.name} (ready)" else "Cleanup: not installed"
        localFallbackStatusView.text = "$sttStatus \u2022 $cleanupStatus"
    }

    private fun refreshLiveSummary() {
        val cfg = LiveTranscriptionConfigStore.load(this)
        val state = if (cfg.enabled) "On" else "Off"
        val post = if (cfg.postCleanupEnabled) {
            val active = cfg.postCleanupSlots.count { it.locked }
            "Post-Cleanup on ($active slot${if (active == 1) "" else "s"})"
        } else {
            "Post-Cleanup off"
        }
        liveSummarySub.text = "$state \u2022 ${cfg.modelId} \u2022 $post"
    }

    private fun refreshChainCards(chain: ProviderChain) {
        chainContainer.removeAllViews()
        val cloudEntries = chain.entries.withIndex().filter { it.value.kind != ProviderKind.LOCAL }
        val sttSlots = sttBudgetSlots(chain)
        val cleanupSlots = cleanupBudgetSlots(chain)

        if (cloudEntries.isEmpty()) {
            emptyStateView.visibility = View.VISIBLE
        } else {
            emptyStateView.visibility = View.GONE
            cloudEntries.forEachIndexed { displayPos, (realIndex, entry) ->
                chainContainer.addView(
                    buildUnifiedProviderCard(
                        chain = chain,
                        entry = entry,
                        realIndex = realIndex,
                        displayPos = displayPos,
                        totalCloud = cloudEntries.size,
                        sttSlots = sttSlots,
                        cleanupSlots = cleanupSlots,
                    )
                )
            }
        }

        val atCap = cloudEntries.size >= AttemptBudgetCalculator.MAX_SAVED_CARDS
        addProviderButtonContainer.removeAllViews()
        addProviderButtonContainer.addView(
            OneUiComponents.cobaltButton(
                context = this,
                label = "Add provider",
                subtitle = if (atCap) {
                    "${cloudEntries.size}/${AttemptBudgetCalculator.MAX_SAVED_CARDS} saved cards (limit reached)"
                } else {
                    "${cloudEntries.size}/${AttemptBudgetCalculator.MAX_SAVED_CARDS} saved cards \u2022 Shared 3-attempt pool"
                },
                enabled = !atCap,
                titleTag = true,
            ) {
                openAddProviderPicker()
            }
        )
    }

    private fun buildUnifiedProviderCard(
        chain: ProviderChain,
        entry: ProviderChainEntry,
        realIndex: Int,
        displayPos: Int,
        totalCloud: Int,
        sttSlots: List<AttemptBudgetCalculator.BudgetSlot>,
        cleanupSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ): View {
        val card = oneUiCard(padH = 14, padV = 12)
        val providerId = ProviderCapabilityRegistry.providerIdFor(entry)
        val descriptor = ProviderCapabilityRegistry.forId(providerId)
        val slotId = "card_$realIndex"
        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val warningAmber = OneUiComponents.resColor(this, R.color.oneui_warning_amber)
        val dangerRed = OneUiComponents.resColor(this, R.color.oneui_danger_red)
        val pillSurface = OneUiComponents.resColor(this, R.color.oneui_pill_surface)
        val borderColor = OneUiComponents.resColor(this, R.color.oneui_card_border)

        val keyValue = ProviderCredentialStore.getOrLegacy(this, entry)
        val hasKey = keyValue.isNotBlank()

        // --- Card Header: priority, reorder ▲/▼, remove ✕ ---
        val topMeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        topMeta.addView(TextView(this).apply {
            text = "Card ${displayPos + 1}" + if (!entry.providerLocked) " \u2022 Paused" else ""
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (entry.providerLocked) secondaryText else warningAmber)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })

        // Vault key status pill (tappable to jump straight to ApiKeysVaultActivity)
        val keyPillText = if (hasKey) "Key ${ProviderCredentialStore.maskForDisplay(keyValue)}" else "Add key"
        topMeta.addView(
            OneUiComponents.pillBadge(
                context = this,
                label = keyPillText,
                textColor = if (hasKey) lockedGreen else warningAmber,
                bgColor = pillSurface,
                borderColor = if (hasKey) lockedGreen else warningAmber,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    startActivity(
                        Intent(this@CloudProviderActivity, ApiKeysVaultActivity::class.java)
                            .putExtra(ApiKeysVaultActivity.EXTRA_FOCUS_PROVIDER_ID, providerId)
                    )
                }
            }
        )

        fun iconAction(label: String, enabled: Boolean, color: Int = primaryText, onClick: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(color)
                background = OneUiComponents.roundedBg(pillSurface, dp(10).toFloat(), dp(1), borderColor)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(30)).apply { marginStart = dp(4) }
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                if (enabled) setOnClickListener { onClick() }
            }

        topMeta.addView(iconAction("\u25b2", displayPos > 0) {
            saveChain(ProviderChainEditing.moveCloudUp(currentChain().entries, realIndex))
        })
        topMeta.addView(iconAction("\u25bc", displayPos < totalCloud - 1) {
            saveChain(ProviderChainEditing.moveCloudDown(currentChain().entries, realIndex))
        })
        topMeta.addView(iconAction("\u2715", true, dangerRed) {
            confirmRemoveCard(entry, realIndex)
        })
        card.addView(topMeta)

        // --- Provider Selector Row + Side Green Lock-In ---
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
                valueText = ProviderCapabilityRegistry.displayNameFor(entry),
                badgeText = if (entry.providerLocked) "Active" else "Unlocked",
                placeholder = "Select provider",
            ) { openChangeProviderPicker(realIndex, entry) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(8)
                }
            }
        )
        providerRow.addView(
            OneUiComponents.lockInButton(
                context = this,
                locked = entry.providerLocked,
                enabled = true,
                contentDescription = "Lock provider ${ProviderCapabilityRegistry.displayNameFor(entry)}",
            ) {
                toggleProviderLock(chain, realIndex, entry, sttSlots, cleanupSlots)
            }
        )
        card.addView(providerRow)

        // --- STT Row ---
        val supportsStt = entry.kind.supportsTranscription() && (descriptor?.supports(CapabilityLane.BATCH_STT) != false)
        if (!supportsStt) {
            card.addView(TextView(this).apply {
                text = "Batch STT: Cleanup only \u2014 this provider does not support audio transcription."
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
        } else {
            val sttSkipped = entry.transcriptionModel == ProviderChainEntry.SKIP_MODEL_ID
            val sttModelId = if (sttSkipped) null else entry.transcriptionModel?.takeIf { it.isNotBlank() }
                ?: defaultBatchModelFor(providerId, CapabilityLane.BATCH_STT)
            val sttMeta = sttModelId?.let { ModelCapabilityClassifier.curatedModelById(providerId, it) }
            val sttBadge = when {
                sttSkipped -> null
                sttMeta?.isNonThinking == true -> "Non-thinking"
                else -> null
            }

            val sttRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    bottomMargin = dp(8)
                }
                alpha = if (entry.providerLocked) 1f else 0.55f
            }
            sttRow.addView(
                OneUiComponents.selectorBox(
                    context = this,
                    label = "STT",
                    valueText = if (sttSkipped) "Skip / None" else (sttMeta?.displayName ?: sttModelId),
                    badgeText = sttBadge,
                    placeholder = "Select STT model",
                    enabled = entry.providerLocked,
                ) { openBatchModelPicker(realIndex, entry, CapabilityLane.BATCH_STT) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                        marginEnd = dp(8)
                    }
                }
            )
            val allowedSttAttempts = AttemptBudgetCalculator.allowedAttemptsForSlot(sttSlots, slotId)
            sttRow.addView(
                OneUiComponents.attemptsPill(
                    context = this,
                    attempts = entry.sttAttempts,
                    allowedAttempts = if (allowedSttAttempts.isEmpty()) listOf(entry.sttAttempts) else allowedSttAttempts,
                    enabled = entry.providerLocked && !sttSkipped && (entry.isBatchSttActive() || allowedSttAttempts.isNotEmpty()),
                ) { chosen ->
                    val updated = entry.copy(sttAttempts = chosen).withSynchronizedParticipation()
                    saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                        marginEnd = dp(8)
                    }
                }
            )
            sttRow.addView(
                OneUiComponents.lockInButton(
                    context = this,
                    locked = entry.isBatchSttActive(),
                    enabled = entry.providerLocked && !sttSkipped,
                    contentDescription = "Lock STT for ${ProviderCapabilityRegistry.displayNameFor(entry)}",
                ) {
                    toggleLaneLock(realIndex, entry, CapabilityLane.BATCH_STT, sttSlots)
                }
            )
            card.addView(sttRow)
        }

        // --- Cleanup Row ---
        val cleanupSkipped = entry.model == ProviderChainEntry.SKIP_MODEL_ID
        val cleanupModelId = if (cleanupSkipped) null else entry.model.takeIf { it.isNotBlank() }
            ?: defaultBatchModelFor(providerId, CapabilityLane.CLEANUP)
        val cleanupMeta = cleanupModelId?.let { ModelCapabilityClassifier.curatedModelById(providerId, it) }

        val cleanupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (entry.providerLocked) 1f else 0.55f
        }
        cleanupRow.addView(
            OneUiComponents.selectorBox(
                context = this,
                label = "Cleanup",
                valueText = if (cleanupSkipped) "Skip / None" else (cleanupMeta?.displayName ?: cleanupModelId),
                placeholder = "Select cleanup model",
                enabled = entry.providerLocked,
            ) { openBatchModelPicker(realIndex, entry, CapabilityLane.CLEANUP) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(8)
                }
            }
        )
        val allowedCleanupAttempts = AttemptBudgetCalculator.allowedAttemptsForSlot(cleanupSlots, slotId)
        cleanupRow.addView(
            OneUiComponents.attemptsPill(
                context = this,
                attempts = entry.cleanupAttempts,
                allowedAttempts = if (allowedCleanupAttempts.isEmpty()) listOf(entry.cleanupAttempts) else allowedCleanupAttempts,
                enabled = entry.providerLocked && !cleanupSkipped && (entry.isBatchCleanupActive() || allowedCleanupAttempts.isNotEmpty()),
            ) { chosen ->
                val updated = entry.copy(cleanupAttempts = chosen).withSynchronizedParticipation()
                saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                    marginEnd = dp(8)
                }
            }
        )
        cleanupRow.addView(
            OneUiComponents.lockInButton(
                context = this,
                locked = entry.isBatchCleanupActive(),
                enabled = entry.providerLocked && !cleanupSkipped,
                contentDescription = "Lock Cleanup for ${ProviderCapabilityRegistry.displayNameFor(entry)}",
            ) {
                toggleLaneLock(realIndex, entry, CapabilityLane.CLEANUP, cleanupSlots)
            }
        )
        card.addView(cleanupRow)

        return card
    }

    private fun toggleProviderLock(
        chain: ProviderChain,
        realIndex: Int,
        entry: ProviderChainEntry,
        sttSlots: List<AttemptBudgetCalculator.BudgetSlot>,
        cleanupSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ) {
        val slotId = "card_$realIndex"
        if (entry.providerLocked) {
            // Unlocking pauses both STT and Cleanup rows and immediately returns their attempts to the 3-attempt pool.
            val paused = entry.copy(
                providerLocked = false,
                useForTranscription = false,
                useForCleanup = false,
            )
            saveChain(ProviderChainEditing.replace(chain.entries, realIndex, paused))
            return
        }

        // Re-locking validates available 3-attempt budget independently per lane.
        var newSttLocked = entry.sttLocked && entry.kind.supportsTranscription() && entry.transcriptionModel != ProviderChainEntry.SKIP_MODEL_ID
        var newSttAttempts = entry.sttAttempts
        var newCleanupLocked = entry.cleanupLocked && entry.model != ProviderChainEntry.SKIP_MODEL_ID
        var newCleanupAttempts = entry.cleanupAttempts
        val notes = mutableListOf<String>()

        if (newSttLocked) {
            when (val res = AttemptBudgetCalculator.canLockSlot(sttSlots, slotId, newSttAttempts)) {
                is AttemptBudgetCalculator.LockResult.Allowed -> {
                    newSttAttempts = res.assignedAttempts
                    if (res.wasClamped) notes += "STT adjusted to ${res.assignedAttempts}x"
                }
                is AttemptBudgetCalculator.LockResult.Blocked -> {
                    newSttLocked = false
                    notes += "STT kept unlocked (3/3 pool full)"
                }
            }
        }
        if (newCleanupLocked) {
            when (val res = AttemptBudgetCalculator.canLockSlot(cleanupSlots, slotId, newCleanupAttempts)) {
                is AttemptBudgetCalculator.LockResult.Allowed -> {
                    newCleanupAttempts = res.assignedAttempts
                    if (res.wasClamped) notes += "Cleanup adjusted to ${res.assignedAttempts}x"
                }
                is AttemptBudgetCalculator.LockResult.Blocked -> {
                    newCleanupLocked = false
                    notes += "Cleanup kept unlocked (3/3 pool full)"
                }
            }
        }

        if (notes.isNotEmpty()) toast(notes.joinToString(" \u2022 "))
        val relocked = entry.copy(
            enabled = true,
            providerLocked = true,
            sttLocked = newSttLocked,
            cleanupLocked = newCleanupLocked,
            sttAttempts = newSttAttempts,
            cleanupAttempts = newCleanupAttempts,
        ).withSynchronizedParticipation()
        saveChain(ProviderChainEditing.replace(chain.entries, realIndex, relocked))
    }

    private fun toggleLaneLock(
        realIndex: Int,
        entry: ProviderChainEntry,
        lane: CapabilityLane,
        laneSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ) {
        val slotId = "card_$realIndex"
        val currentlyLocked = if (lane == CapabilityLane.BATCH_STT) entry.isBatchSttActive() else entry.isBatchCleanupActive()
        if (currentlyLocked) {
            val updated = if (lane == CapabilityLane.BATCH_STT) {
                entry.copy(sttLocked = false).withSynchronizedParticipation()
            } else {
                entry.copy(cleanupLocked = false).withSynchronizedParticipation()
            }
            saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
            return
        }

        val requested = if (lane == CapabilityLane.BATCH_STT) entry.sttAttempts else entry.cleanupAttempts
        when (val res = AttemptBudgetCalculator.canLockSlot(laneSlots, slotId, requested)) {
            is AttemptBudgetCalculator.LockResult.Blocked -> toast(res.reason)
            is AttemptBudgetCalculator.LockResult.Allowed -> {
                if (res.wasClamped) {
                    toast("Adjusted to ${res.assignedAttempts}x to fit the 3-attempt pool")
                }
                val updated = if (lane == CapabilityLane.BATCH_STT) {
                    entry.copy(
                        enabled = true,
                        providerLocked = true,
                        sttLocked = true,
                        sttAttempts = res.assignedAttempts,
                    ).withSynchronizedParticipation()
                } else {
                    entry.copy(
                        enabled = true,
                        providerLocked = true,
                        cleanupLocked = true,
                        cleanupAttempts = res.assignedAttempts,
                    ).withSynchronizedParticipation()
                }
                saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
            }
        }
    }

    private fun openAddProviderPicker() {
        val providers = ProviderCapabilityRegistry.batchProviders()
        val vaultEntries = ApiKeyVaultStore.load(this)
        val items = buildList {
            providers.forEach { p ->
                if (p.id == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI) {
                    val customVaults = vaultEntries.filter { it.providerId == p.id }
                    if (customVaults.isEmpty()) {
                        add(
                            OneUiComponents.SearchablePickerSheet.PickerItem(
                                id = p.id,
                                title = p.displayName,
                                subtitle = "Add endpoint & key in API keys vault",
                                badges = listOf("Batch STT", "Cleanup"),
                            )
                        )
                    } else {
                        customVaults.forEach { cv ->
                            add(
                                OneUiComponents.SearchablePickerSheet.PickerItem(
                                    id = "custom_vault:${cv.id}",
                                    title = cv.displayLabel,
                                    subtitle = cv.customBaseUrl ?: "Custom OpenAI-Compatible",
                                    badges = listOf("Custom", "Vault ready"),
                                )
                            )
                        }
                    }
                } else {
                    val hasKey = ApiKeyVaultStore.hasSecret(this@CloudProviderActivity, null, p.id)
                    val lanes = buildList {
                        if (p.supports(CapabilityLane.BATCH_STT)) add("Batch STT")
                        if (p.supports(CapabilityLane.CLEANUP)) add("Cleanup")
                        if (p.supports(CapabilityLane.LIVE_STT)) add("Live STT")
                    }
                    add(
                        OneUiComponents.SearchablePickerSheet.PickerItem(
                            id = p.id,
                            title = p.displayName,
                            subtitle = if (hasKey) "Shared vault key ready" else "No key in vault yet",
                            badges = lanes,
                        )
                    )
                }
            }
        }

        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Add provider",
            items = items,
            searchHint = "Search providers...",
            onSelect = { item ->
                val newEntry = createEntryForSelection(item.id)
                if (newEntry != null) {
                    saveChain(ProviderChainEditing.addCloud(currentChain().entries, newEntry))
                }
            },
        )
    }

    private fun openChangeProviderPicker(realIndex: Int, existing: ProviderChainEntry) {
        val currentProviderId = ProviderCapabilityRegistry.providerIdFor(existing)
        val providers = ProviderCapabilityRegistry.batchProviders()
        val items = providers.map { p ->
            val hasKey = ApiKeyVaultStore.hasSecret(this, null, p.id)
            OneUiComponents.SearchablePickerSheet.PickerItem(
                id = p.id,
                title = p.displayName,
                subtitle = if (hasKey) "Shared vault key ready" else "No key in vault yet",
                badges = p.supportedLanes.map {
                    when (it) {
                        CapabilityLane.BATCH_STT -> "Batch STT"
                        CapabilityLane.CLEANUP -> "Cleanup"
                        CapabilityLane.LIVE_STT -> "Live STT"
                    }
                },
                selected = p.id == currentProviderId,
            )
        }

        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "Change provider",
            items = items,
            searchHint = "Search providers...",
            onSelect = { item ->
                val replacement = createEntryForSelection(item.id, preserveId = existing.id) ?: return@show
                saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, replacement))
            },
        )
    }

    private fun createEntryForSelection(selectionId: String, preserveId: String? = null): ProviderChainEntry? {
        val entryId = preserveId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val chain = currentChain()
        val sttSlots = sttBudgetSlots(chain)
        val cleanupSlots = cleanupBudgetSlots(chain)

        if (selectionId.startsWith("custom_vault:")) {
            val vaultId = selectionId.removePrefix("custom_vault:")
            val vaultEntry = ApiKeyVaultStore.findById(this, vaultId) ?: return null
            val sttLock = AttemptBudgetCalculator.canLockSlot(sttSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed
            val cleanupLock = AttemptBudgetCalculator.canLockSlot(cleanupSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed
            return ProviderChainEntry(
                kind = ProviderKind.OPENAI,
                model = "",
                baseUrlOverride = vaultEntry.customBaseUrl,
                transcriptionModel = null,
                id = entryId,
                enabled = true,
                useForTranscription = sttLock,
                useForCleanup = cleanupLock,
                presetId = null,
                vaultKeyId = vaultEntry.id,
                providerLocked = true,
                sttLocked = sttLock,
                cleanupLocked = cleanupLock,
                sttAttempts = 1,
                cleanupAttempts = 1,
                customLabel = vaultEntry.displayLabel,
            ).withSynchronizedParticipation()
        }

        if (selectionId == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI) {
            startActivity(
                Intent(this, ApiKeysVaultActivity::class.java)
                    .putExtra(ApiKeysVaultActivity.EXTRA_FOCUS_PROVIDER_ID, ProviderCapabilityRegistry.ID_CUSTOM_OPENAI)
            )
            toast("Add your Custom OpenAI-Compatible endpoint and key in API keys first")
            return null
        }

        val descriptor = ProviderCapabilityRegistry.forId(selectionId) ?: return null
        val defaultStt = if (descriptor.supports(CapabilityLane.BATCH_STT)) {
            defaultBatchModelFor(descriptor.id, CapabilityLane.BATCH_STT)
        } else {
            null
        }
        val defaultCleanup = defaultBatchModelFor(descriptor.id, CapabilityLane.CLEANUP) ?: ""
        val vaultEntry = ApiKeyVaultStore.findLockedForProvider(this, descriptor.id)
            ?: ApiKeyVaultStore.findForProvider(this, descriptor.id).firstOrNull()
        val vaultId = vaultEntry?.id ?: ProviderCapabilityRegistry.canonicalVaultKeyId(descriptor.id)

        val canLockStt = descriptor.supports(CapabilityLane.BATCH_STT) &&
            AttemptBudgetCalculator.canLockSlot(sttSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed
        val canLockCleanup = AttemptBudgetCalculator.canLockSlot(cleanupSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed

        val baseUrl = if (descriptor.presetId != null) descriptor.defaultBaseUrl else null

        return ProviderChainEntry(
            kind = descriptor.kind,
            model = defaultCleanup,
            baseUrlOverride = baseUrl,
            transcriptionModel = defaultStt,
            id = entryId,
            enabled = true,
            useForTranscription = canLockStt,
            useForCleanup = canLockCleanup,
            presetId = descriptor.presetId,
            vaultKeyId = vaultId,
            providerLocked = true,
            sttLocked = canLockStt,
            cleanupLocked = canLockCleanup,
            sttAttempts = 1,
            cleanupAttempts = 1,
        ).withSynchronizedParticipation()
    }

    private fun openBatchModelPicker(realIndex: Int, entry: ProviderChainEntry, lane: CapabilityLane) {
        val providerId = ProviderCapabilityRegistry.providerIdFor(entry)
        val currentModel = if (lane == CapabilityLane.BATCH_STT) entry.transcriptionModel else entry.model
        val isSkipped = currentModel == ProviderChainEntry.SKIP_MODEL_ID

        fun toPickerItems(models: List<ModelDescriptor>) = models.map { m ->
            val metaParts = listOfNotNull(m.id, m.priceSummary(), m.contextSummary())
            OneUiComponents.SearchablePickerSheet.PickerItem(
                id = m.id,
                title = m.displayName,
                subtitle = m.description,
                metaLine = metaParts.joinToString(" \u00b7 "),
                badges = m.badges,
                selected = !isSkipped && m.id == currentModel,
            )
        }

        val initialModels = LiveModelCatalogStore.modelsFor(this, providerId, lane, entry.baseUrlOverride)
        val apiKey = ProviderCredentialStore.getOrLegacy(this, entry)
        val laneTitle = if (lane == CapabilityLane.BATCH_STT) "Select STT Model" else "Select Cleanup Model"

        OneUiComponents.SearchablePickerSheet.show(
            context = this,
            title = "$laneTitle (${ProviderCapabilityRegistry.displayNameFor(entry)})",
            items = toPickerItems(initialModels),
            searchHint = "Search models...",
            skipOptionLabel = "Skip / None (do not use this provider for ${if (lane == CapabilityLane.BATCH_STT) "STT" else "Cleanup"})",
            skipSelected = isSkipped,
            onSkipSelected = {
                val updated = if (lane == CapabilityLane.BATCH_STT) {
                    entry.copy(transcriptionModel = ProviderChainEntry.SKIP_MODEL_ID, sttLocked = false)
                } else {
                    entry.copy(model = ProviderChainEntry.SKIP_MODEL_ID, cleanupLocked = false)
                }.withSynchronizedParticipation()
                saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
            },
            onRefreshRequested = { onDone ->
                LiveModelCatalogStore.refreshAsync(
                    context = this,
                    providerId = providerId,
                    apiKey = apiKey,
                    customBaseUrl = entry.baseUrlOverride,
                    force = true,
                ) { res ->
                    val merged = LiveModelCatalogStore.modelsFor(this, providerId, lane, entry.baseUrlOverride)
                    val msg = if (res.success) "Refreshed (${merged.size} models)" else res.errorMessage
                    onDone(toPickerItems(merged), msg)
                }
            },
            allowCustomModelId = true,
            customModelWarning = { typed ->
                ModelCapabilityClassifier.wrongLaneWarning(providerId, typed, lane)
            },
            onSelect = { item ->
                applyBatchModelChoice(realIndex, entry, lane, item.id)
            },
            onCustomIdSelected = { customId ->
                applyBatchModelChoice(realIndex, entry, lane, customId)
            },
        )
    }

    private fun applyBatchModelChoice(
        realIndex: Int,
        entry: ProviderChainEntry,
        lane: CapabilityLane,
        modelId: String,
    ) {
        val updated = if (lane == CapabilityLane.BATCH_STT) {
            entry.copy(transcriptionModel = modelId)
        } else {
            entry.copy(model = modelId)
        }.withSynchronizedParticipation()
        saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
    }

    private fun defaultBatchModelFor(providerId: String, lane: CapabilityLane): String? =
        ModelCapabilityClassifier.curatedModelsFor(providerId, lane).firstOrNull()?.id

    private fun confirmRemoveCard(entry: ProviderChainEntry, realIndex: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${ProviderCapabilityRegistry.displayNameFor(entry)}?")
            .setMessage("This removes the card from your Batch chain. Your API key stays safe in the API keys vault.")
            .setPositiveButton("Remove") { _, _ ->
                saveChain(ProviderChainEditing.remove(currentChain().entries, realIndex))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Dictation mode & Mix-and-match ---

    private fun refreshModeSelection() {
        val mode = DictationModePresets.current(this)
        val button = modeRadioButtons[mode]
        if (button == null) modeRadioGroup.clearCheck() else modeRadioGroup.check(button.id)
        if (mode == DictationMode.CUSTOM) {
            modeCustomNote.visibility = View.VISIBLE
            modeCustomNote.text = "Custom \u2014 transcription and cleanup are set independently under Mix and match."
        } else {
            modeCustomNote.visibility = View.GONE
        }
    }

    private fun applyMode(mode: DictationMode) {
        DictationModePresets.apply(this, mode)
        refresh()
    }

    private fun cloudTranscriptionSubtitle(): String {
        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal) return "Off \u2014 transcription runs on-device"
        val chain = currentChain()
        val hasCandidate = ProviderChainRuntime.transcriptionCandidates(chain).any { it.kind != ProviderKind.LOCAL }
        val hasConfigured = hasConfiguredCloudTranscription(chain) { ProviderCredentialStore.isConfiguredOrLegacy(this, it) }
        return when {
            hasConfigured -> "On \u2014 uses the active Batch STT chain"
            hasCandidate -> "On, but no key is set for an active STT provider yet \u2014 falls back to on-device"
            else -> "On, but no locked provider supports Batch STT yet \u2014 falls back to on-device"
        }
    }

    private fun cloudCleanupSubtitle(): String {
        if (!CloudFeatureToggle.cleanupEnabled(this)) return "Off \u2014 cleanup runs on-device (or is skipped if no local model is set)"
        val chain = currentChain()
        val cloudEntries = chain.capableEntriesFor(needsTranscription = false).filter { it.kind != ProviderKind.LOCAL }
        val hasConfigured = cloudEntries.any { ProviderCredentialStore.isConfiguredOrLegacy(this, it) }
        return when {
            hasConfigured -> "On \u2014 uses the active Batch Cleanup chain"
            cloudEntries.isNotEmpty() -> "On, but no key is set for an active cleanup provider yet"
            else -> "On, but no locked cleanup provider is configured yet"
        }
    }

    private fun onToggleCloudTranscription(newCloud: Boolean) {
        prefs().edit().putBoolean("use_local", !newCloud).apply()
        refresh()
    }

    private fun onToggleCloudCleanup(enabled: Boolean) {
        CloudFeatureToggle.setCleanupEnabled(this, enabled)
        refresh()
    }

    private fun localFallbackSubtitle(): String =
        if (DictationModeToggle.allowLocalFallback(this))
            "On \u2014 if every cloud provider fails, dictation falls back to on-device"
        else
            "Off \u2014 if cloud fails, you'll see an error instead of a silent on-device fallback"

    private fun cloudFallbackSubtitle(): String =
        if (DictationModeToggle.allowCloudFallback(this))
            "On \u2014 if on-device isn't ready or fails, dictation falls back to cloud"
        else
            "Off \u2014 if on-device isn't ready or fails, you'll see an error instead of a silent cloud fallback"

    private fun onToggleLocalFallback(enabled: Boolean) {
        DictationModeToggle.setAllowLocalFallback(this, enabled)
        refresh()
    }

    private fun onToggleCloudFallback(enabled: Boolean) {
        DictationModeToggle.setAllowCloudFallback(this, enabled)
        refresh()
    }

    companion object {
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        private val DICTATION_MODE_OPTIONS = DictationModePresets.OPTIONS

        fun subtitle(context: android.content.Context): String =
            subtitleFor(ProviderChainStore.load(context))

        fun subtitleFor(chain: ProviderChain): String {
            val count = chain.entries.count { it.kind != ProviderKind.LOCAL }
            return when (count) {
                0 -> "Not configured \u2014 using on-device"
                1 -> "1 provider configured"
                else -> "$count providers configured"
            }
        }
    }
}
