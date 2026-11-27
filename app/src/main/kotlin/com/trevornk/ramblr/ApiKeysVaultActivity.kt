package com.trevornk.ramblr

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import java.util.UUID

/**
 * Shared API Keys Vault screen (Phase 3A.3 hairline overhaul).
 *
 * Flat hairline sections per provider, underlined EditText inputs, inline flat text actions
 * (`Show`/`Hide`, `Save`, `Delete` — zero pills/boxes), circular `lockCircle` + green `"Active"`,
 * auto-sanitized non-blocking save, and flat hairline `+ Add Custom OpenAI-Compatible` row.
 */
class ApiKeysVaultActivity : BaseSettingsActivity() {

    private lateinit var sectionsContainer: LinearLayout
    private var focusProviderId: String? = null
    private val dirtyCardIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)

        focusProviderId = intent.getStringExtra(EXTRA_FOCUS_PROVIDER_ID)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleNavigateBack()
            }
        })

        val body = vertical(0, 0)
        body.addView(explainer(
            "One shared encrypted vault for Batch STT, Cleanup, and Live transcription. Keys are " +
                "sanitized automatically on save and stored encrypted on this device."
        ))

        sectionsContainer = vertical(0, 0)
        body.addView(sectionsContainer)

        body.addView(hairlineDivider(24, 24))
        body.addView(
            OneUiComponents.flatActionRow(
                context = this,
                label = "+ Add Custom OpenAI-Compatible",
                subtitle = "Self-hosted or third-party OpenAI-format endpoint",
            ) { promptAddCustomVaultKey() }
        )

        setOneUiScreenContent(
            title = "API keys",
            showBack = true,
            icon = R.drawable.ic_settings_cloud,
            content = body,
            onBack = { handleNavigateBack() },
        )

        renderSections()
    }

    override fun onResume() {
        super.onResume()
        if (dirtyCardIds.isEmpty()) renderSections()
    }

    private fun handleNavigateBack() {
        if (dirtyCardIds.isEmpty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved API key changes")
            .setMessage("You have unsaved edits in ${dirtyCardIds.size} provider(s). Discard them and go back?")
            .setPositiveButton("Discard") { _, _ ->
                dirtyCardIds.clear()
                finish()
            }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun renderSections() {
        sectionsContainer.removeAllViews()
        dirtyCardIds.clear()

        val savedEntries = ApiKeyVaultStore.load(this)
        val savedById = savedEntries.associateBy { it.id }

        val rowsToRender = mutableListOf<Triple<ProviderDescriptor, ApiKeyVaultEntry, Boolean>>()

        ProviderCapabilityRegistry.vaultProviders().forEach { descriptor ->
            val canonicalId = ProviderCapabilityRegistry.canonicalVaultKeyId(descriptor.id)
            val entry = savedById[canonicalId] ?: ApiKeyVaultEntry(
                id = canonicalId,
                providerId = descriptor.id,
                displayLabel = descriptor.displayName,
                customBaseUrl = null,
                locked = false,
            )
            rowsToRender += Triple(descriptor, entry, false)

            savedEntries
                .filter { it.providerId == descriptor.id && it.id != canonicalId }
                .forEach { extra ->
                    rowsToRender += Triple(descriptor, extra, true)
                }
        }

        val customDescriptor = ProviderCapabilityRegistry.CUSTOM_OPENAI
        savedEntries
            .filter { it.providerId == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI }
            .forEach { customEntry ->
                rowsToRender += Triple(customDescriptor, customEntry, true)
            }

        rowsToRender.forEachIndexed { idx, (descriptor, entry, isSecondaryOrCustom) ->
            sectionsContainer.addView(buildVaultSection(descriptor, entry, isSecondaryOrCustom))
            if (idx < rowsToRender.lastIndex) {
                sectionsContainer.addView(hairlineDivider(24, 24))
            }
        }
    }

    private fun buildVaultSection(
        descriptor: ProviderDescriptor,
        initialEntry: ApiKeyVaultEntry,
        isSecondaryOrCustom: Boolean,
    ): View {
        val section = vertical(0).apply {
            setPadding(dp(24), dp(14), dp(16), dp(14))
        }
        val existingSecret = ProviderCredentialStore.getVaultSecret(this, initialEntry.id)
        val hasSavedSecret = existingSecret.isNotBlank()
        var currentLocked = initialEntry.locked && hasSavedSecret
        var keyVisible = false

        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val accentColor = attrColor(com.google.android.material.R.attr.colorPrimary)
        val dangerColor = OneUiComponents.resColor(this, R.color.oneui_danger_red)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val warningAmber = OneUiComponents.resColor(this, R.color.oneui_warning_amber)

        // Header row: provider title + subtle lane caption + Active/Not set status
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), dp(2))
        }
        headerRow.addView(TextView(this).apply {
            text = initialEntry.displayLabel
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        val statusText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        headerRow.addView(statusText)
        section.addView(headerRow)

        val lanesSummary = descriptor.supportedLanes.joinToString(" \u00b7 ") { lane ->
            when (lane) {
                CapabilityLane.BATCH_STT -> "Batch STT"
                CapabilityLane.CLEANUP -> "Cleanup"
                CapabilityLane.LIVE_STT -> "Live STT"
            }
        } + if (focusProviderId == descriptor.id) " \u00b7 Needed" else ""
        section.addView(TextView(this).apply {
            text = lanesSummary
            textSize = 12f
            setTextColor(if (focusProviderId == descriptor.id) accentColor else secondaryText)
            setPadding(0, 0, dp(8), dp(8))
        })

        // Optional flat underlined label & base URL inputs for Custom OpenAI-Compatible
        var labelInput: EditText? = null
        var baseUrlInput: EditText? = null
        if (descriptor.requiresCustomBaseUrl) {
            labelInput = EditText(this).apply {
                hint = "Display name (e.g. Local vLLM)"
                setText(initialEntry.displayLabel)
                textSize = 14f
                isSingleLine = true
                setTextColor(primaryText)
                setHintTextColor(secondaryText)
                background = OneUiComponents.flatUnderlineBg(this@ApiKeysVaultActivity)
                setPadding(0, dp(6), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(8)
                }
            }
            baseUrlInput = EditText(this).apply {
                hint = "Base URL (e.g. https://host/v1)"
                setText(initialEntry.customBaseUrl ?: "")
                textSize = 14f
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(primaryText)
                setHintTextColor(secondaryText)
                background = OneUiComponents.flatUnderlineBg(this@ApiKeysVaultActivity)
                setPadding(0, dp(6), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(8)
                }
            }
            section.addView(labelInput)
            section.addView(baseUrlInput)
        }

        // Flat underlined key input + lockCircle
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keyInput = EditText(this).apply {
            hint = if (hasSavedSecret) {
                "Saved (${ProviderCredentialStore.maskForDisplay(existingSecret)}) \u2014 paste to replace"
            } else {
                "Paste API key (${descriptor.keyHint.ifBlank { "API key" }})"
            }
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            background = OneUiComponents.flatUnderlineBg(this@ApiKeysVaultActivity)
            setPadding(0, dp(8), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val lockHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        inputRow.addView(keyInput)
        inputRow.addView(lockHost)
        section.addView(inputRow)

        val helperText = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6), dp(8), dp(4))
        }
        section.addView(helperText)

        fun effectiveKey(): String {
            val typed = ApiKeyVaultValidator.sanitize(keyInput.text?.toString().orEmpty())
            return typed.ifEmpty { existingSecret }
        }

        fun effectiveBaseUrl(): String? =
            baseUrlInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: initialEntry.customBaseUrl

        fun refreshVisuals() {
            val currentSecret = effectiveKey()
            val check = ApiKeyVaultValidator.validate(descriptor.id, currentSecret, effectiveBaseUrl())
            val isLockedAndValid = currentLocked && check.isValid
            statusText.text = when {
                isLockedAndValid -> "Active \u00b7 ${ProviderCredentialStore.maskForDisplay(currentSecret)}"
                hasSavedSecret || keyInput.text?.isNotBlank() == true -> "Unlocked"
                else -> "Not set"
            }
            statusText.setTextColor(
                when {
                    isLockedAndValid -> lockedGreen
                    dirtyCardIds.contains(initialEntry.id) -> warningAmber
                    else -> secondaryText
                }
            )
            helperText.text = when {
                dirtyCardIds.contains(initialEntry.id) && !check.isValid -> check.message ?: "Enter a valid API key."
                dirtyCardIds.contains(initialEntry.id) && check.softWarning != null -> check.softWarning
                dirtyCardIds.contains(initialEntry.id) -> "Unsaved changes \u2014 tap Save."
                !hasSavedSecret -> "Paste your key and tap Save."
                isLockedAndValid -> "Active in shared vault."
                else -> "Saved unlocked."
            }
            helperText.setTextColor(
                when {
                    !check.isValid && (dirtyCardIds.contains(initialEntry.id) || keyInput.text?.isNotBlank() == true) -> dangerColor
                    dirtyCardIds.contains(initialEntry.id) -> warningAmber
                    else -> secondaryText
                }
            )

            lockHost.removeAllViews()
            lockHost.addView(
                OneUiComponents.lockCircle(
                    context = this,
                    locked = isLockedAndValid,
                    enabled = true,
                    contentDescription = "Lock ${initialEntry.displayLabel} API key",
                ) {
                    if (currentLocked) {
                        currentLocked = false
                        dirtyCardIds.add(initialEntry.id)
                        refreshVisuals()
                    } else {
                        val validation = ApiKeyVaultValidator.validate(descriptor.id, effectiveKey(), effectiveBaseUrl())
                        if (!validation.isValid) {
                            toast(validation.message ?: "Enter an API key first")
                        } else {
                            currentLocked = true
                            dirtyCardIds.add(initialEntry.id)
                            refreshVisuals()
                        }
                    }
                }
            )
        }

        // Phase 3B.4: Show/Hide may programmatically populate/clear keyInput with the saved secret
        // so it can be revealed; those writes must never mark the card dirty.
        var suppressWatcher = false
        var revealingSavedSecret = false

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                if (revealingSavedSecret && s?.toString() == existingSecret) {
                    refreshVisuals()
                    return
                }
                dirtyCardIds.add(initialEntry.id)
                refreshVisuals()
            }
        }
        keyInput.addTextChangedListener(watcher)
        labelInput?.addTextChangedListener(watcher)
        baseUrlInput?.addTextChangedListener(watcher)

        // Inline flat text actions: Show/Hide, Delete, Save (NO pills)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), dp(8), 0)
        }

        fun flatTextAction(label: String, color: Int, onClick: (TextView) -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(color)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                OneUiComponents.applySelectableRipple(this)
                setOnClickListener {
                    UiTapHaptics.tick(this)
                    onClick(this)
                }
            }

        actionRow.addView(flatTextAction("Show", secondaryText) { btn ->
            keyVisible = !keyVisible
            suppressWatcher = true
            try {
                if (keyVisible) {
                    if (hasSavedSecret && keyInput.text.isNullOrEmpty()) {
                        keyInput.setText(existingSecret)
                        revealingSavedSecret = true
                    }
                    keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    btn.text = "Hide"
                } else {
                    keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    btn.text = "Show"
                    if (revealingSavedSecret && keyInput.text?.toString() == existingSecret) {
                        keyInput.setText("")
                    }
                    revealingSavedSecret = false
                }
                keyInput.setSelection(keyInput.text?.length ?: 0)
            } finally {
                suppressWatcher = false
            }
            refreshVisuals()
        })

        actionRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        if (hasSavedSecret || isSecondaryOrCustom) {
            actionRow.addView(flatTextAction("Delete", dangerColor) {
                confirmDeleteVaultKey(initialEntry)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                    marginEnd = dp(8)
                }
            })
        }

        actionRow.addView(flatTextAction("Save", accentColor) {
            val rawTyped = keyInput.text?.toString().orEmpty()
            val keyToSave = if (rawTyped.isNotBlank()) ApiKeyVaultValidator.sanitize(rawTyped) else existingSecret
            val baseUrlToSave = effectiveBaseUrl()
            val labelToSave = labelInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: initialEntry.displayLabel
            val check = ApiKeyVaultValidator.validate(descriptor.id, keyToSave, baseUrlToSave)
            if (!check.isValid) {
                toast(check.message ?: "Enter an API key first")
                refreshVisuals()
                return@flatTextAction
            }
            val lockOnSave = if (rawTyped.isNotBlank() && !hasSavedSecret) true else currentLocked
            val updated = initialEntry.copy(
                displayLabel = labelToSave,
                customBaseUrl = baseUrlToSave,
                locked = lockOnSave,
            )
            ApiKeyVaultStore.upsert(this@ApiKeysVaultActivity, updated, secret = keyToSave)
            dirtyCardIds.remove(initialEntry.id)
            toast("${updated.displayLabel} key saved")
            renderSections()
        })
        section.addView(actionRow)

        refreshVisuals()
        return section
    }

    private fun confirmDeleteVaultKey(entry: ApiKeyVaultEntry) {
        val batchChain = ProviderChainStore.load(this)
        val liveConfig = LiveTranscriptionConfigStore.load(this)
        val usages = ApiKeyUsageScanner.usagesOf(entry.id, batchChain, liveConfig)

        if (usages.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Delete ${entry.displayLabel} key?")
                .setMessage("The encrypted API key will be permanently removed from this device.")
                .setPositiveButton("Delete") { _, _ ->
                    ApiKeyVaultStore.delete(this, entry.id)
                    toast("${entry.displayLabel} key deleted")
                    renderSections()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val usageLines = usages.joinToString("\n") { "\u2022 ${it.area}: ${it.slotLabel}" }
        AlertDialog.Builder(this)
            .setTitle("${entry.displayLabel} key is in use")
            .setMessage(
                "This key is currently referenced by:\n\n$usageLines\n\n" +
                    "Detaching will unlock those slots and permanently delete this key."
            )
            .setPositiveButton("Detach & Delete") { _, _ ->
                val detachedChain = ApiKeyUsageScanner.detachFromChain(entry.id, batchChain)
                ProviderChainStore.save(this, detachedChain)
                val detachedLive = ApiKeyUsageScanner.detachFromLiveConfig(entry.id, liveConfig)
                LiveTranscriptionConfigStore.save(this, detachedLive)
                ApiKeyVaultStore.delete(this, entry.id)
                toast("${entry.displayLabel} key detached and deleted")
                renderSections()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptAddCustomVaultKey() {
        val container = vertical(dp(20), dp(12))
        val nameInput = EditText(this).apply { hint = "Display label (e.g. Self-hosted Gateway)" }
        val urlInput = EditText(this).apply {
            hint = "Base URL (https://host/v1)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = EditText(this).apply {
            hint = "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(nameInput)
        container.addView(urlInput)
        container.addView(keyInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Custom OpenAI-Compatible")
            .setView(container)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlInput.text.toString().trim()
                val key = ApiKeyVaultValidator.sanitize(keyInput.text.toString())
                val check = ApiKeyVaultValidator.validate(ProviderCapabilityRegistry.ID_CUSTOM_OPENAI, key, url)
                if (!check.isValid) {
                    toast(check.message ?: "Invalid custom endpoint or key")
                    return@setOnClickListener
                }
                val label = nameInput.text.toString().trim().ifEmpty {
                    PostProcessor.destinationHost(url).ifBlank { "Custom OpenAI" }
                }
                val newEntry = ApiKeyVaultEntry(
                    id = "vault_custom_${UUID.randomUUID().toString().take(8)}",
                    providerId = ProviderCapabilityRegistry.ID_CUSTOM_OPENAI,
                    displayLabel = label,
                    customBaseUrl = url,
                    locked = true,
                )
                ApiKeyVaultStore.upsert(this, newEntry, secret = key)
                dialog.dismiss()
                toast("$label added to vault")
                renderSections()
            }
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_FOCUS_PROVIDER_ID = "extra_focus_provider_id"
    }
}
