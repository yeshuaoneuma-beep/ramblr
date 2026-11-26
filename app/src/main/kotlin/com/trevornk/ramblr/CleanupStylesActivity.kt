package com.trevornk.ramblr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Dedicated Cleanup styles screen (Phase 3A.3).
 *
 * Holds the shared AI cleanup master toggle (preserving [LocalCleanupConsent]), active style link
 * into [StyleManagerActivity], Preview before inserting, Remember cleanup style per app, Offer raw
 * text after cleanup, and Translation languages — applying equally to both Cloud and Local AI cleanup.
 */
class CleanupStylesActivity : BaseSettingsActivity() {

    private lateinit var postProcessSwitch: MaterialSwitch
    private lateinit var postProcessRowSub: TextView
    private lateinit var postProcessRowDetails: TextView
    private lateinit var styleSummaryRowTitle: TextView
    private lateinit var previewBeforeInjectSwitch: MaterialSwitch
    private lateinit var perAppPersonaSwitch: MaterialSwitch
    private lateinit var rawTextRetrySwitch: MaterialSwitch
    private lateinit var translationLanguagesRowSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(explainer("Applies to both Cloud and Local AI cleanup."))

        postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean("use_post_processing", false)
            isClickable = false
        }
        val postProcessRow = settingsRow(
            title = "Improve my dictation with AI",
            subtitle = cleanupSubtitle(),
            widget = postProcessSwitch,
            details = cleanupCostNote().orEmpty(),
        ) {
            val newVal = !postProcessSwitch.isChecked
            val useLocal = prefs().getBoolean("use_local", true)
            applyLocalCleanupChange(useLocal, newVal)
        }
        postProcessRowSub = postProcessRow.findViewWithTag("subtitle")
        postProcessRowDetails = postProcessRow.findViewWithTag("details")
        root.addView(postProcessRow)

        root.addView(sectionHeader("Style"))
        val styleRow = settingsRow(
            title = currentPersonaSummary(this),
            subtitle = "Manage styles, quick-menu selection, and custom prompts",
        ) { startActivity(Intent(this, StyleManagerActivity::class.java)) }
        styleSummaryRowTitle = styleRow.findViewWithTag("title")
        root.addView(styleRow)

        root.addView(sectionHeader("Cleanup options"))

        previewBeforeInjectSwitch = MaterialSwitch(this).apply {
            isChecked = PreviewBeforeInjectToggle.isEnabled(this@CleanupStylesActivity)
            isClickable = false
        }
        root.addView(
            settingsRow(
                title = "Preview before inserting",
                subtitle = "Review the cleaned-up text and tap to insert it, instead of inserting automatically",
                widget = previewBeforeInjectSwitch,
            ) {
                val newVal = !previewBeforeInjectSwitch.isChecked
                PreviewBeforeInjectToggle.setEnabled(this, newVal)
                previewBeforeInjectSwitch.isChecked = newVal
            }
        )

        perAppPersonaSwitch = MaterialSwitch(this).apply {
            isChecked = PerAppPersonaToggle.isEnabled(this@CleanupStylesActivity)
            isClickable = false
        }
        root.addView(
            settingsRow(
                title = "Remember cleanup style per app",
                subtitle = "Each app keeps the cleanup style you last picked in it",
                widget = perAppPersonaSwitch,
                details = "Instead of always using your global default style",
            ) {
                val newVal = !perAppPersonaSwitch.isChecked
                PerAppPersonaToggle.setEnabled(this, newVal)
                perAppPersonaSwitch.isChecked = newVal
            }
        )

        rawTextRetrySwitch = MaterialSwitch(this).apply {
            isChecked = RawTextRetryToggle.isEnabled(this@CleanupStylesActivity)
            isClickable = false
        }
        root.addView(
            settingsRow(
                title = "Offer raw text after cleanup",
                subtitle = "After cleanup changes your words, a bubble lets you swap back to exactly what you said",
                widget = rawTextRetrySwitch,
                details = "The \u201cTap to use raw text\u201d bubble stays for a few seconds",
            ) {
                val newVal = !rawTextRetrySwitch.isChecked
                RawTextRetryToggle.setEnabled(this, newVal)
                rawTextRetrySwitch.isChecked = newVal
            }
        )

        root.addView(sectionHeader(getString(R.string.translation_section_title)))
        val translationLanguagesRow = settingsRow(
            title = getString(R.string.keyboard_translation_languages_title),
            subtitle = KeyboardTranslation.summary(this),
            details = getString(R.string.translation_languages_details),
        ) {
            TranslationLanguagesDialog.show(this) { refresh() }
        }
        translationLanguagesRowSub = translationLanguagesRow.findViewWithTag("subtitle")
        root.addView(translationLanguagesRow)

        setOneUiScreenContent(
            title = "Cleanup styles",
            showBack = true,
            icon = R.drawable.ic_settings_cleanup_styles,
            content = root,
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        postProcessSwitch.isChecked = usePostProcessing
        postProcessRowSub.text = cleanupSubtitle()
        val costNote = cleanupCostNote()
        postProcessRowDetails.text = costNote.orEmpty()
        postProcessRowDetails.visibility = if (costNote == null) View.GONE else View.VISIBLE
        styleSummaryRowTitle.text = currentPersonaSummary(this)
        previewBeforeInjectSwitch.isChecked = PreviewBeforeInjectToggle.isEnabled(this)
        perAppPersonaSwitch.isChecked = PerAppPersonaToggle.isEnabled(this)
        rawTextRetrySwitch.isChecked = RawTextRetryToggle.isEnabled(this)
        translationLanguagesRowSub.text = KeyboardTranslation.summary(this)
    }

    private fun applyLocalCleanupChange(useLocal: Boolean, usePostProcessing: Boolean) {
        val hasConsented = prefs().getBoolean(KEY_LOCAL_CLEANUP_CONSENT, false)
        val cleanupIsLocalOnly = !CloudFeatureToggle.cleanupEnabled(this)
        if (!LocalCleanupConsent.shouldPrompt(useLocal, usePostProcessing, hasConsented, cleanupIsLocalOnly)) {
            prefs().edit()
                .putBoolean("use_local", useLocal)
                .putBoolean("use_post_processing", usePostProcessing)
                .apply()
            refresh()
            return
        }

        val declineCleanup = {
            prefs().edit()
                .putBoolean("use_local", useLocal)
                .putBoolean("use_post_processing", false)
                .apply()
            refresh()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Cleanup sends text off-device")
            .setMessage(
                "Local transcription keeps audio on your phone, but cleanup sends the " +
                    "transcribed text to ${CleanupDestination.consentHost(ProviderChainStore.load(this))} to fix " +
                    "grammar and punctuation. Enable cleanup anyway?"
            )
            .setPositiveButton("Enable cleanup") { _, _ ->
                prefs().edit()
                    .putBoolean("use_local", useLocal)
                    .putBoolean("use_post_processing", true)
                    .putBoolean(KEY_LOCAL_CLEANUP_CONSENT, true)
                    .apply()
                refresh()
            }
            .setNegativeButton("Keep cleanup off") { _, _ -> declineCleanup() }
            .setOnCancelListener { declineCleanup() }
            .show()
    }

    private fun cleanupSubtitle(): String {
        if (!CloudFeatureToggle.cleanupEnabled(this)) {
            return "Cleans up your dictation on-device — the text never leaves your phone"
        }
        val useLocal = prefs().getBoolean("use_local", true)
        val chain = ProviderChainStore.load(this)
        val entry = CleanupDestination.firstCloudEntry(chain)
        val host = entry?.let { CleanupDestination.hostFor(it) } ?: CleanupDestination.consentHost(chain)
        return if (useLocal) "Sends transcript over the network to $host, even though transcription stays on-device."
        else "Sends transcript to $host to fix grammar and punctuation."
    }

    private fun cleanupCostNote(): String? {
        if (!CloudFeatureToggle.cleanupEnabled(this)) return null
        val entry = CleanupDestination.firstCloudEntry(ProviderChainStore.load(this)) ?: return null
        return "Uses your ${ProviderCapabilityRegistry.displayNameFor(entry)} API key and is billed pay-per-use (typically a fraction of a cent per dictation)."
    }

    companion object {
        private const val KEY_LOCAL_CLEANUP_CONSENT = "local_cleanup_consent_seen"

        fun currentPersonaTitle(context: Context): String {
            val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            val prompt = PostProcessor.effectiveSavedPrompt(
                prefs.getString("post_processing_prompt", null),
                prefs.getString("cleanup_style", null),
            )
            return PersonaRegistry.currentPersona(context, prefs.getString("cleanup_style", null), prompt).title
        }

        fun currentPersonaSummary(context: Context): String = "Style: ${currentPersonaTitle(context)}"

        fun subtitle(context: Context): String = cleanupStylesMainRowSubtitleText(
            cleanupEnabled = PostProcessingToggle.isEnabled(context),
            activeStyleTitle = currentPersonaTitle(context),
        )
    }
}
