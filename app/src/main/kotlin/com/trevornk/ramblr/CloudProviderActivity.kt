package com.trevornk.ramblr

import android.app.AlertDialog
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
 * Dedicated Cloud providers screen (Phase 3A.3 hairline overhaul).
 *
 * Dedicated solely to Cloud routing:
 *  - Top `API keys` row showing count ONLY (`"No keys saved"`, `"1 key saved"`, `"N keys saved"`)
 *  - `Execution order` section (key-gated Batch STT & Batch Cleanup + plain-text `Pool X/3`)
 *  - `Batch providers` (up to 10 flat hairline blocks, `▲`/`▼`/`✕`, `attemptBadge` `1`/`2`/`3`,
 *    key-gated `lockCircle`, single green `"Active"` or muted `"No key in vault"` on Provider row)
 *  - Flat hairline `+ Add provider` row
 *  - `Audio upload` section with `Compress audio before cloud upload` ([CompressedUploadToggle])
 */
class CloudProviderActivity : BaseSettingsActivity() {

    private lateinit var vaultSummarySub: TextView
    private lateinit var executionOrderContainer: LinearLayout
    private lateinit var chainContainer: LinearLayout
    private lateinit var emptyStateView: View
    private lateinit var addProviderButtonContainer: LinearLayout
    private lateinit var compressedUploadSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)

        val root = vertical(0, 0)

        // 1. API keys row (count ONLY, no parenthesized provider names, no Manage keys pill)
        val vaultRow = settingsRow(
            title = "API keys",
            subtitle = "No keys saved",
        ) {
            startActivity(Intent(this, ApiKeysVaultActivity::class.java))
        }
        vaultSummarySub = vaultRow.findViewWithTag("subtitle")
        root.addView(vaultRow)

        // 2. Execution order section
        root.addView(sectionHeader("Execution order"))
        executionOrderContainer = vertical(0)
        root.addView(executionOrderContainer)

        // 3. Batch providers section
        root.addView(sectionHeader("Batch providers"))
        chainContainer = vertical(0)
        root.addView(chainContainer)

        emptyStateView = explainer(
            "No cloud providers added yet. Add a provider below to route batch transcription or cleanup to the cloud."
        )
        root.addView(emptyStateView)

        addProviderButtonContainer = vertical(0)
        root.addView(addProviderButtonContainer)

        // 4. Audio upload section (relocated from TranscriptionActivity, same CompressedUploadToggle pref)
        root.addView(sectionHeader("Audio upload"))
        compressedUploadSwitch = MaterialSwitch(this).apply {
            isChecked = CompressedUploadToggle.isEnabled(this@CloudProviderActivity)
            isClickable = false
        }
        root.addView(
            settingsRow(
                title = "Compress audio before cloud upload",
                subtitle = "Smaller uploads for mobile data or slow internet. Accuracy with compressed audio isn't proven yet",
                widget = compressedUploadSwitch,
                details = "Encodes to AAC/M4A before sending to cloud STT providers. Leave off where maximum accuracy matters.",
            ) {
                val newVal = !compressedUploadSwitch.isChecked
                CompressedUploadToggle.setEnabled(this, newVal)
                compressedUploadSwitch.isChecked = newVal
            }
        )

        setOneUiScreenContent(
            title = "Cloud providers",
            showBack = true,
            icon = R.drawable.ic_settings_cloud,
            content = root,
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)
        refresh()
    }

    private fun currentChain(): ProviderChain = ProviderChainStore.load(this)

    private fun saveChain(entries: List<ProviderChainEntry>) {
        ProviderChainStore.save(this, ProviderChain(entries))
        refresh()
    }

    private fun hasKeyFor(entry: ProviderChainEntry): Boolean =
        ProviderCredentialStore.isConfiguredOrLegacy(this, entry)

    private fun refresh() {
        val chain = currentChain()
        refreshVaultSummary()
        refreshExecutionOrder(chain)
        refreshChainCards(chain)
        compressedUploadSwitch.isChecked = CompressedUploadToggle.isEnabled(this)
    }

    private fun refreshVaultSummary() {
        val entries = ApiKeyVaultStore.load(this)
        val totalConfigured = entries.count { ProviderCredentialStore.isVaultConfigured(this, it.id) }
        vaultSummarySub.text = vaultKeyCountText(totalConfigured)
    }

    private fun sttBudgetSlots(chain: ProviderChain): List<AttemptBudgetCalculator.BudgetSlot> =
        chain.entries.mapIndexedNotNull { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) null
            else AttemptBudgetCalculator.BudgetSlot(
                id = "card_$idx",
                label = ProviderCapabilityRegistry.displayNameFor(entry),
                locked = entry.isBatchSttActive() && hasKeyFor(entry),
                attempts = entry.sttAttempts,
            )
        }

    private fun cleanupBudgetSlots(chain: ProviderChain): List<AttemptBudgetCalculator.BudgetSlot> =
        chain.entries.mapIndexedNotNull { idx, entry ->
            if (entry.kind == ProviderKind.LOCAL) null
            else AttemptBudgetCalculator.BudgetSlot(
                id = "card_$idx",
                label = ProviderCapabilityRegistry.displayNameFor(entry),
                locked = entry.isBatchCleanupActive() && hasKeyFor(entry),
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
        val isConfigured: (ProviderChainEntry) -> Boolean = { hasKeyFor(it) }

        executionOrderContainer.addView(
            OneUiComponents.executionOrderRow(
                context = this,
                title = "Batch STT",
                orderText = EffectiveRouting.batchSttExecutionOrder(
                    chain = chain,
                    allowLocalFallback = allowLocalFallback,
                    localAvailable = localSttReady,
                    useLocalTranscription = useLocalStt,
                    isConfigured = isConfigured,
                ),
                usedAttempts = AttemptBudgetCalculator.usedAttempts(sttSlots),
            )
        )
        executionOrderContainer.addView(hairlineDivider(24, 24))
        executionOrderContainer.addView(
            OneUiComponents.executionOrderRow(
                context = this,
                title = "Batch Cleanup",
                orderText = EffectiveRouting.batchCleanupExecutionOrder(
                    chain = chain,
                    postProcessingEnabled = postProcessingOn,
                    cloudCleanupEnabled = cloudCleanupOn,
                    allowLocalFallback = allowLocalFallback,
                    localAvailable = localCleanupReady,
                    isConfigured = isConfigured,
                ),
                usedAttempts = AttemptBudgetCalculator.usedAttempts(cleanupSlots),
            )
        )
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
                    buildFlatProviderBlock(
                        chain = chain,
                        entry = entry,
                        realIndex = realIndex,
                        displayPos = displayPos,
                        totalCloud = cloudEntries.size,
                        sttSlots = sttSlots,
                        cleanupSlots = cleanupSlots,
                    )
                )
                if (displayPos < cloudEntries.lastIndex) {
                    chainContainer.addView(hairlineDivider(24, 24))
                }
            }
        }

        val atCap = cloudEntries.size >= AttemptBudgetCalculator.MAX_SAVED_CARDS
        addProviderButtonContainer.removeAllViews()
        if (cloudEntries.isNotEmpty()) {
            addProviderButtonContainer.addView(hairlineDivider(24, 24))
        }
        addProviderButtonContainer.addView(
            OneUiComponents.flatActionRow(
                context = this,
                label = "+ Add provider",
                subtitle = if (atCap) {
                    "${cloudEntries.size}/${AttemptBudgetCalculator.MAX_SAVED_CARDS} providers saved (limit reached)"
                } else {
                    "${cloudEntries.size}/${AttemptBudgetCalculator.MAX_SAVED_CARDS} saved \u00b7 Shared 3-attempt pool"
                },
                enabled = !atCap,
                titleTag = true,
            ) {
                openAddProviderPicker()
            }
        )
    }

    private fun buildFlatProviderBlock(
        chain: ProviderChain,
        entry: ProviderChainEntry,
        realIndex: Int,
        displayPos: Int,
        totalCloud: Int,
        sttSlots: List<AttemptBudgetCalculator.BudgetSlot>,
        cleanupSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ): View {
        val block = vertical(0).apply {
            setPadding(dp(24), dp(12), dp(16), dp(12))
        }
        val providerId = ProviderCapabilityRegistry.providerIdFor(entry)
        val descriptor = ProviderCapabilityRegistry.forId(providerId)
        val slotId = "card_$realIndex"
        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val dangerRed = OneUiComponents.resColor(this, R.color.oneui_danger_red)
        val hasKey = hasKeyFor(entry)
        val providerLockedEffective = entry.providerLocked && hasKey

        // --- Compact priority bar: #N + ▲ / ▼ / ✕ ---
        val topMeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        topMeta.addView(TextView(this).apply {
            text = "Priority ${displayPos + 1}"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })

        fun flatIconAction(label: String, desc: String, enabled: Boolean, color: Int = primaryText, onClick: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                contentDescription = desc
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(32))
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
                alpha = if (enabled) 0.9f else 0.3f
                if (enabled) {
                    OneUiComponents.applySelectableRipple(this)
                    setOnClickListener { onClick() }
                }
            }

        topMeta.addView(flatIconAction("\u25b2", "Move up", displayPos > 0) {
            saveChain(ProviderChainEditing.moveCloudUp(currentChain().entries, realIndex))
        })
        topMeta.addView(flatIconAction("\u25bc", "Move down", displayPos < totalCloud - 1) {
            saveChain(ProviderChainEditing.moveCloudDown(currentChain().entries, realIndex))
        })
        topMeta.addView(flatIconAction("\u2715", "Remove provider", true, dangerRed) {
            confirmRemoveCard(entry, realIndex)
        })
        block.addView(topMeta)

        // --- Provider Row (single compact 11sp "Active" or "No key in vault" + lockCircle) ---
        val providerStatusText = when {
            !hasKey -> "No key in vault"
            providerLockedEffective -> "Active"
            else -> "Paused"
        }
        val providerStatusColor = if (providerLockedEffective) lockedGreen else secondaryText

        val providerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        providerRow.addView(
            OneUiComponents.flatSelectorContent(
                context = this,
                label = "Provider",
                valueText = ProviderCapabilityRegistry.displayNameFor(entry),
                statusText = providerStatusText,
                statusColor = providerStatusColor,
                placeholder = "Select provider",
            ) { openChangeProviderPicker(realIndex, entry) }.apply {
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
                contentDescription = "Lock provider ${ProviderCapabilityRegistry.displayNameFor(entry)}",
            ) {
                toggleProviderLock(chain, realIndex, entry, hasKey, providerId, sttSlots, cleanupSlots)
            }
        )
        block.addView(providerRow)

        // --- STT Row ---
        val supportsStt = entry.kind.supportsTranscription() && (descriptor?.supports(CapabilityLane.BATCH_STT) != false)
        if (!supportsStt) {
            block.addView(TextView(this).apply {
                text = "STT \u00b7 Cleanup only (no audio transcription)"
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(0, dp(4), 0, dp(4))
            })
        } else {
            val sttSkipped = entry.transcriptionModel == ProviderChainEntry.SKIP_MODEL_ID
            val sttModelId = if (sttSkipped) null else entry.transcriptionModel?.takeIf { it.isNotBlank() }
                ?: defaultBatchModelFor(providerId, CapabilityLane.BATCH_STT)
            val sttMeta = sttModelId?.let { ModelCapabilityClassifier.curatedModelById(providerId, it) }
            val sttActive = entry.isBatchSttActive() && hasKey

            val sttRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                alpha = if (providerLockedEffective) 1f else 0.55f
            }
            sttRow.addView(
                OneUiComponents.flatSelectorContent(
                    context = this,
                    label = "STT",
                    valueText = if (sttSkipped) "Skip / None" else (sttMeta?.displayName ?: sttModelId),
                    placeholder = "Select STT model",
                    enabled = providerLockedEffective,
                ) { openBatchModelPicker(realIndex, entry, CapabilityLane.BATCH_STT) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                        marginEnd = dp(4)
                    }
                }
            )
            val allowedSttAttempts = AttemptBudgetCalculator.allowedAttemptsForSlot(sttSlots, slotId)
            sttRow.addView(
                OneUiComponents.attemptBadge(
                    context = this,
                    attempts = entry.sttAttempts,
                    allowedAttempts = if (allowedSttAttempts.isEmpty()) listOf(entry.sttAttempts) else allowedSttAttempts,
                    enabled = providerLockedEffective && !sttSkipped && (sttActive || allowedSttAttempts.isNotEmpty()),
                ) { chosen ->
                    val updated = entry.copy(sttAttempts = chosen).withSynchronizedParticipation()
                    saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
                }
            )
            sttRow.addView(
                OneUiComponents.lockCircle(
                    context = this,
                    locked = sttActive,
                    enabled = !sttSkipped,
                    contentDescription = "Lock STT for ${ProviderCapabilityRegistry.displayNameFor(entry)}",
                ) {
                    toggleLaneLock(realIndex, entry, hasKey, providerId, CapabilityLane.BATCH_STT, sttSlots)
                }
            )
            block.addView(sttRow)
        }

        // --- Cleanup Row ---
        val cleanupSkipped = entry.model == ProviderChainEntry.SKIP_MODEL_ID
        val cleanupModelId = if (cleanupSkipped) null else entry.model.takeIf { it.isNotBlank() }
            ?: defaultBatchModelFor(providerId, CapabilityLane.CLEANUP)
        val cleanupMeta = cleanupModelId?.let { ModelCapabilityClassifier.curatedModelById(providerId, it) }
        val cleanupActive = entry.isBatchCleanupActive() && hasKey

        val cleanupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (providerLockedEffective) 1f else 0.55f
        }
        cleanupRow.addView(
            OneUiComponents.flatSelectorContent(
                context = this,
                label = "Cleanup",
                valueText = if (cleanupSkipped) "Skip / None" else (cleanupMeta?.displayName ?: cleanupModelId),
                placeholder = "Select cleanup model",
                enabled = providerLockedEffective,
            ) { openBatchModelPicker(realIndex, entry, CapabilityLane.CLEANUP) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(4)
                }
            }
        )
        val allowedCleanupAttempts = AttemptBudgetCalculator.allowedAttemptsForSlot(cleanupSlots, slotId)
        cleanupRow.addView(
            OneUiComponents.attemptBadge(
                context = this,
                attempts = entry.cleanupAttempts,
                allowedAttempts = if (allowedCleanupAttempts.isEmpty()) listOf(entry.cleanupAttempts) else allowedCleanupAttempts,
                enabled = providerLockedEffective && !cleanupSkipped && (cleanupActive || allowedCleanupAttempts.isNotEmpty()),
            ) { chosen ->
                val updated = entry.copy(cleanupAttempts = chosen).withSynchronizedParticipation()
                saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
            }
        )
        cleanupRow.addView(
            OneUiComponents.lockCircle(
                context = this,
                locked = cleanupActive,
                enabled = !cleanupSkipped,
                contentDescription = "Lock Cleanup for ${ProviderCapabilityRegistry.displayNameFor(entry)}",
            ) {
                toggleLaneLock(realIndex, entry, hasKey, providerId, CapabilityLane.CLEANUP, cleanupSlots)
            }
        )
        block.addView(cleanupRow)

        return block
    }

    private fun openVaultForProvider(providerId: String) {
        startActivity(
            Intent(this, ApiKeysVaultActivity::class.java)
                .putExtra(ApiKeysVaultActivity.EXTRA_FOCUS_PROVIDER_ID, providerId)
        )
    }

    private fun toggleProviderLock(
        chain: ProviderChain,
        realIndex: Int,
        entry: ProviderChainEntry,
        hasKey: Boolean,
        providerId: String,
        sttSlots: List<AttemptBudgetCalculator.BudgetSlot>,
        cleanupSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ) {
        val slotId = "card_$realIndex"
        if (entry.providerLocked && hasKey) {
            val paused = entry.copy(
                providerLocked = false,
                useForTranscription = false,
                useForCleanup = false,
            )
            saveChain(ProviderChainEditing.replace(chain.entries, realIndex, paused))
            return
        }
        if (!hasKey) {
            toast("Add a ${ProviderCapabilityRegistry.displayNameFor(entry)} API key in API keys first")
            openVaultForProvider(providerId)
            return
        }

        val supportsStt = entry.kind.supportsTranscription() && entry.transcriptionModel != ProviderChainEntry.SKIP_MODEL_ID
        val supportsCleanup = entry.model != ProviderChainEntry.SKIP_MODEL_ID
        var newSttLocked = supportsStt && (entry.sttLocked || (!entry.sttLocked && !entry.cleanupLocked))
        var newSttAttempts = entry.sttAttempts
        var newCleanupLocked = supportsCleanup && (entry.cleanupLocked || (!entry.sttLocked && !entry.cleanupLocked))
        var newCleanupAttempts = entry.cleanupAttempts
        val notes = mutableListOf<String>()

        if (newSttLocked) {
            when (val res = AttemptBudgetCalculator.canLockSlot(sttSlots, slotId, newSttAttempts)) {
                is AttemptBudgetCalculator.LockResult.Allowed -> {
                    newSttAttempts = res.assignedAttempts
                    if (res.wasClamped) notes += "STT adjusted to ${res.assignedAttempts}"
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
                    if (res.wasClamped) notes += "Cleanup adjusted to ${res.assignedAttempts}"
                }
                is AttemptBudgetCalculator.LockResult.Blocked -> {
                    newCleanupLocked = false
                    notes += "Cleanup kept unlocked (3/3 pool full)"
                }
            }
        }

        if (notes.isNotEmpty()) toast(notes.joinToString(" \u00b7 "))
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
        hasKey: Boolean,
        providerId: String,
        lane: CapabilityLane,
        laneSlots: List<AttemptBudgetCalculator.BudgetSlot>,
    ) {
        val slotId = "card_$realIndex"
        val currentlyLocked = hasKey && if (lane == CapabilityLane.BATCH_STT) entry.isBatchSttActive() else entry.isBatchCleanupActive()
        if (currentlyLocked) {
            val updated = if (lane == CapabilityLane.BATCH_STT) {
                entry.copy(sttLocked = false).withSynchronizedParticipation()
            } else {
                entry.copy(cleanupLocked = false).withSynchronizedParticipation()
            }
            saveChain(ProviderChainEditing.replace(currentChain().entries, realIndex, updated))
            return
        }
        if (!hasKey) {
            toast("Add a ${ProviderCapabilityRegistry.displayNameFor(entry)} API key in API keys first")
            openVaultForProvider(providerId)
            return
        }

        val requested = if (lane == CapabilityLane.BATCH_STT) entry.sttAttempts else entry.cleanupAttempts
        when (val res = AttemptBudgetCalculator.canLockSlot(laneSlots, slotId, requested)) {
            is AttemptBudgetCalculator.LockResult.Blocked -> toast(res.reason)
            is AttemptBudgetCalculator.LockResult.Allowed -> {
                if (res.wasClamped) {
                    toast("Adjusted to ${res.assignedAttempts} to fit the 3-attempt pool")
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
                                subtitle = "Add endpoint & key in API keys",
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
                                    badges = listOf("Custom"),
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
                            subtitle = if (hasKey) "Key saved in vault" else "No key in vault",
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
                subtitle = if (hasKey) "Key saved in vault" else "No key in vault",
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
            val hasKey = ProviderCredentialStore.isVaultConfigured(this, vaultEntry.id)
            val sttLock = hasKey && AttemptBudgetCalculator.canLockSlot(sttSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed
            val cleanupLock = hasKey && AttemptBudgetCalculator.canLockSlot(cleanupSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed
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
                providerLocked = hasKey,
                sttLocked = sttLock,
                cleanupLocked = cleanupLock,
                sttAttempts = 1,
                cleanupAttempts = 1,
                customLabel = vaultEntry.displayLabel,
            ).withSynchronizedParticipation()
        }

        if (selectionId == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI) {
            openVaultForProvider(ProviderCapabilityRegistry.ID_CUSTOM_OPENAI)
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
        val hasKey = ApiKeyVaultStore.hasSecret(this, vaultId, descriptor.id)

        val canLockStt = hasKey && descriptor.supports(CapabilityLane.BATCH_STT) &&
            AttemptBudgetCalculator.canLockSlot(sttSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed
        val canLockCleanup = hasKey &&
            AttemptBudgetCalculator.canLockSlot(cleanupSlots, "new", 1) is AttemptBudgetCalculator.LockResult.Allowed

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
            providerLocked = hasKey,
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
        val laneTitle = if (lane == CapabilityLane.BATCH_STT) "Select STT model" else "Select cleanup model"

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
            .setMessage("This removes the provider from your Batch chain. Your API key stays safe in API keys.")
            .setPositiveButton("Remove") { _, _ ->
                saveChain(ProviderChainEditing.remove(currentChain().entries, realIndex))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
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
