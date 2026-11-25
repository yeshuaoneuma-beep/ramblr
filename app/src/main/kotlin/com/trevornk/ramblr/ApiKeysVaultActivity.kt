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
 * Shared API Keys Vault screen (Phase 3A.1).
 *
 * Driven exclusively from [ProviderCapabilityRegistry.vaultProviders] plus user-created Custom
 * OpenAI-Compatible vault entries. Secrets live in [ProviderCredentialStore]'s encrypted prefs
 * under `"vault_<vaultKeyId>_key"`; metadata lives in [ApiKeyVaultStore].
 */
class ApiKeysVaultActivity : BaseSettingsActivity() {

    private lateinit var cardsContainer: LinearLayout
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
            "One shared vault for Batch STT, Cleanup, and Live transcription. Lock a key green " +
                "and tap Save to store it encrypted on this device."
        ))

        cardsContainer = vertical(0, 0)
        body.addView(cardsContainer)

        val addCustomWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        addCustomWrap.addView(
            OneUiComponents.cobaltButton(
                context = this,
                label = "+ Add Custom OpenAI-Compatible",
                subtitle = "Self-hosted or third-party OpenAI-format endpoint",
            ) { promptAddCustomVaultKey() }
        )
        body.addView(addCustomWrap)

        setOneUiScreenContent(
            title = "API keys",
            showBack = true,
            subtitle = "Shared encrypted vault",
            content = body,
            onBack = { handleNavigateBack() },
        )

        renderCards()
    }

    override fun onResume() {
        super.onResume()
        if (dirtyCardIds.isEmpty()) renderCards()
    }

    private fun handleNavigateBack() {
        if (dirtyCardIds.isEmpty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved API key changes")
            .setMessage("You have unsaved edits in ${dirtyCardIds.size} key card(s). Discard them and go back?")
            .setPositiveButton("Discard") { _, _ ->
                dirtyCardIds.clear()
                finish()
            }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun renderCards() {
        cardsContainer.removeAllViews()
        dirtyCardIds.clear()

        val savedEntries = ApiKeyVaultStore.load(this)
        val savedById = savedEntries.associateBy { it.id }

        // 1. Standard registry-driven vault providers
        ProviderCapabilityRegistry.vaultProviders().forEach { descriptor ->
            val canonicalId = ProviderCapabilityRegistry.canonicalVaultKeyId(descriptor.id)
            val entry = savedById[canonicalId] ?: ApiKeyVaultEntry(
                id = canonicalId,
                providerId = descriptor.id,
                displayLabel = descriptor.displayName,
                customBaseUrl = null,
                locked = false,
            )
            cardsContainer.addView(buildVaultCard(descriptor, entry, isSecondaryOrCustom = false))

            // Render any secondary migrated vault slots for the same provider
            savedEntries
                .filter { it.providerId == descriptor.id && it.id != canonicalId }
                .forEach { extra ->
                    cardsContainer.addView(buildVaultCard(descriptor, extra, isSecondaryOrCustom = true))
                }
        }

        // 2. Saved Custom OpenAI-Compatible entries
        val customDescriptor = ProviderCapabilityRegistry.CUSTOM_OPENAI
        savedEntries
            .filter { it.providerId == ProviderCapabilityRegistry.ID_CUSTOM_OPENAI }
            .forEach { customEntry ->
                cardsContainer.addView(buildVaultCard(customDescriptor, customEntry, isSecondaryOrCustom = true))
            }
    }

    private fun buildVaultCard(
        descriptor: ProviderDescriptor,
        initialEntry: ApiKeyVaultEntry,
        isSecondaryOrCustom: Boolean,
    ): View {
        val card = oneUiCard(padH = 16, padV = 14)
        val existingSecret = ProviderCredentialStore.getVaultSecret(this, initialEntry.id)
        val hasSavedSecret = existingSecret.isNotBlank()
        var currentLocked = initialEntry.locked && hasSavedSecret
        var keyVisible = false

        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val pillSurface = OneUiComponents.resColor(this, R.color.oneui_pill_surface)
        val borderColor = OneUiComponents.resColor(this, R.color.oneui_card_border)
        val accentColor = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt)
        val dangerColor = OneUiComponents.resColor(this, R.color.oneui_danger_red)
        val lockedGreen = OneUiComponents.resColor(this, R.color.oneui_locked_green)
        val warningAmber = OneUiComponents.resColor(this, R.color.oneui_warning_amber)

        // Header row: label + focused badge + status
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = initialEntry.displayLabel
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        val statusText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        headerRow.addView(statusText)
        card.addView(headerRow)

        // Capability chips
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(10))
        }
        descriptor.supportedLanes.forEach { lane ->
            val label = when (lane) {
                CapabilityLane.BATCH_STT -> "Batch STT"
                CapabilityLane.CLEANUP -> "Cleanup"
                CapabilityLane.LIVE_STT -> "Live STT"
            }
            chipRow.addView(
                OneUiComponents.pillBadge(this, label).apply {
                    layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                        marginEnd = dp(6)
                    }
                }
            )
        }
        if (focusProviderId == descriptor.id) {
            chipRow.addView(
                OneUiComponents.pillBadge(
                    this,
                    "Needed",
                    textColor = accentColor,
                    bgColor = OneUiComponents.resColor(this, R.color.oneui_accent_cobalt_container),
                )
            )
        }
        card.addView(chipRow)

        // Optional custom label & base URL inputs for Custom OpenAI-Compatible
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
                background = OneUiComponents.roundedBg(pillSurface, dp(12).toFloat(), dp(1), borderColor)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
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
                background = OneUiComponents.roundedBg(pillSurface, dp(12).toFloat(), dp(1), borderColor)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    bottomMargin = dp(8)
                }
            }
            card.addView(labelInput)
            card.addView(baseUrlInput)
        }

        // Key input + show/hide + side green lock-in button
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keyInput = EditText(this).apply {
            hint = if (hasSavedSecret) {
                "Saved (${ProviderCredentialStore.maskForDisplay(existingSecret)}) — paste to replace"
            } else {
                "Paste key (${descriptor.keyHint.ifBlank { "API key" }})"
            }
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            background = OneUiComponents.roundedBg(pillSurface, dp(12).toFloat(), dp(1), borderColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val toggleVisibilityBtn = TextView(this).apply {
            text = "Show"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(accentColor)
            background = OneUiComponents.roundedBg(pillSurface, dp(10).toFloat(), dp(1), borderColor)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                keyVisible = !keyVisible
                val sel = keyInput.selectionEnd
                keyInput.inputType = if (keyVisible) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                if (sel >= 0) keyInput.setSelection(sel.coerceAtMost(keyInput.text?.length ?: 0))
                text = if (keyVisible) "Hide" else "Show"
            }
        }
        val lockHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        inputRow.addView(keyInput)
        inputRow.addView(toggleVisibilityBtn)
        inputRow.addView(lockHost)
        card.addView(inputRow)

        val helperText = TextView(this).apply {
            textSize = 12f
            setPadding(dp(2), dp(6), dp(2), dp(8))
        }
        card.addView(helperText)

        fun effectiveKey(): String {
            val typed = keyInput.text?.toString()?.trim().orEmpty()
            return typed.ifEmpty { existingSecret }
        }

        fun effectiveBaseUrl(): String? =
            baseUrlInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: initialEntry.customBaseUrl

        fun refreshCardVisuals() {
            val check = ApiKeyVaultValidator.validate(descriptor.id, effectiveKey(), effectiveBaseUrl())
            statusText.text = when {
                currentLocked && check.isValid -> "Locked \u2022 ${ProviderCredentialStore.maskForDisplay(effectiveKey())}"
                hasSavedSecret || keyInput.text?.isNotBlank() == true -> "Draft (unlocked)"
                else -> "Not set"
            }
            statusText.setTextColor(
                when {
                    currentLocked && check.isValid -> lockedGreen
                    dirtyCardIds.contains(initialEntry.id) -> warningAmber
                    else -> secondaryText
                }
            )
            helperText.text = when {
                dirtyCardIds.contains(initialEntry.id) && !check.isValid -> check.message ?: "Check API key format."
                dirtyCardIds.contains(initialEntry.id) -> "Unsaved changes — tap Save to persist."
                !hasSavedSecret -> "Expected format: ${descriptor.keyHint.ifBlank { "API key" }}"
                currentLocked -> "Active in shared vault. Tap lock to unlock or Save to update."
                else -> "Saved unlocked. Tap lock and Save to mark active."
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
                OneUiComponents.lockInButton(
                    context = this,
                    locked = currentLocked,
                    enabled = true,
                    contentDescription = "Lock ${initialEntry.displayLabel} API key",
                ) {
                    if (currentLocked) {
                        currentLocked = false
                        dirtyCardIds.add(initialEntry.id)
                        refreshCardVisuals()
                    } else {
                        val validation = ApiKeyVaultValidator.validate(descriptor.id, effectiveKey(), effectiveBaseUrl())
                        if (!validation.isValid) {
                            toast(validation.message ?: "Invalid API key format")
                        } else {
                            currentLocked = true
                            dirtyCardIds.add(initialEntry.id)
                            refreshCardVisuals()
                        }
                    }
                }
            )
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                dirtyCardIds.add(initialEntry.id)
                refreshCardVisuals()
            }
        }
        keyInput.addTextChangedListener(watcher)
        labelInput?.addTextChangedListener(watcher)
        baseUrlInput?.addTextChangedListener(watcher)

        // Action row: Save + Delete
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }

        if (hasSavedSecret || isSecondaryOrCustom) {
            actionRow.addView(TextView(this).apply {
                text = "Delete"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(dangerColor)
                background = OneUiComponents.roundedBg(pillSurface, dp(12).toFloat(), dp(1), borderColor)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { confirmDeleteVaultKey(initialEntry) }
            })
        }

        actionRow.addView(TextView(this).apply {
            text = "Save"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            background = OneUiComponents.roundedBg(accentColor, dp(12).toFloat())
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener {
                val keyToSave = effectiveKey()
                val baseUrlToSave = effectiveBaseUrl()
                val labelToSave = labelInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: initialEntry.displayLabel
                val check = ApiKeyVaultValidator.validate(descriptor.id, keyToSave, baseUrlToSave)
                if (!check.isValid) {
                    toast(check.message ?: "Invalid API key")
                    refreshCardVisuals()
                    return@setOnClickListener
                }
                // Auto-lock on valid save if the user pasted a fresh key and hadn't toggled lock yet.
                val lockOnSave = if (keyInput.text?.isNotBlank() == true && !hasSavedSecret) true else currentLocked
                val updated = initialEntry.copy(
                    displayLabel = labelToSave,
                    customBaseUrl = baseUrlToSave,
                    locked = lockOnSave,
                )
                ApiKeyVaultStore.upsert(this@ApiKeysVaultActivity, updated, secret = keyToSave)
                dirtyCardIds.remove(initialEntry.id)
                toast("${updated.displayLabel} key saved")
                renderCards()
            }
        })
        card.addView(actionRow)

        refreshCardVisuals()
        return card
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
                    renderCards()
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
                renderCards()
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
                val key = keyInput.text.toString().trim()
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
                renderCards()
            }
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_FOCUS_PROVIDER_ID = "extra_focus_provider_id"
    }
}
