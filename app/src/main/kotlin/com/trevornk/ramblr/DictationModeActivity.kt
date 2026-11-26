package com.trevornk.ramblr

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Dedicated Dictation mode screen (Phase 3A.3).
 *
 * Flat hairline layout using [OneUiComponents.selectionDot] (zero RadioButtons) for Cloud / Local /
 * Fastest presets, Mix and match toggles (`Transcription`, `Cleanup`) with accurate vault-key and
 * on-device install status, and a read-only Effective routing preview.
 */
class DictationModeActivity : BaseSettingsActivity() {

    private lateinit var modeDots: Map<DictationMode, FrameLayout>
    private lateinit var modeCustomNote: TextView
    private lateinit var cloudTranscriptionSwitch: MaterialSwitch
    private lateinit var cloudTranscriptionRowSub: TextView
    private lateinit var cloudCleanupSwitch: MaterialSwitch
    private lateinit var cloudCleanupRowSub: TextView
    private lateinit var routingTranscriptionSub: TextView
    private lateinit var routingCleanupSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)

        val root = vertical(0, 0)

        root.addView(explainer(
            "Choose where speech is recognized and where AI cleanup runs, or mix and match below."
        ))

        root.addView(sectionHeader("Presets"))
        val dots = linkedMapOf<DictationMode, FrameLayout>()
        for (mode in DictationModePresets.OPTIONS) {
            val parts = DictationModePresets.label(mode).split(" — ", limit = 2)
            val dot = OneUiComponents.selectionDot(this, selected = false, contentDescription = parts[0])
            dots[mode] = dot
            val row = settingsRow(
                title = parts[0],
                subtitle = parts.getOrNull(1)?.replaceFirstChar { it.uppercase() }.orEmpty(),
                widget = dot,
            ) {
                DictationModePresets.apply(this, mode)
                refresh()
            }
            root.addView(row)
        }
        modeDots = dots

        modeCustomNote = TextView(this).apply {
            text = "Custom \u2014 transcription and cleanup are set independently under Mix and match."
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(8), dp(24), dp(4))
            visibility = View.GONE
        }
        root.addView(modeCustomNote)

        root.addView(sectionHeader("Mix and match"))

        cloudTranscriptionSwitch = MaterialSwitch(this).apply { isClickable = false }
        val transcriptionRow = settingsRow(
            title = "Transcription",
            subtitle = "",
            widget = cloudTranscriptionSwitch,
        ) {
            val newCloud = !cloudTranscriptionSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            refresh()
        }
        cloudTranscriptionRowSub = transcriptionRow.findViewWithTag("subtitle")
        root.addView(transcriptionRow)

        cloudCleanupSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cleanupRow = settingsRow(
            title = "Cleanup",
            subtitle = "",
            widget = cloudCleanupSwitch,
        ) {
            CloudFeatureToggle.setCleanupEnabled(this, !cloudCleanupSwitch.isChecked)
            refresh()
        }
        cloudCleanupRowSub = cleanupRow.findViewWithTag("subtitle")
        root.addView(cleanupRow)

        root.addView(sectionHeader("Effective routing"))
        val routingTransRow = settingsRow("Transcription", "")
        routingTranscriptionSub = routingTransRow.findViewWithTag("subtitle")
        root.addView(routingTransRow)

        val routingCleanRow = settingsRow("Cleanup", "")
        routingCleanupSub = routingCleanRow.findViewWithTag("subtitle")
        root.addView(routingCleanRow)

        setOneUiScreenContent(
            title = "Dictation mode",
            showBack = true,
            icon = R.drawable.ic_settings_tune,
            content = root,
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)
        refresh()
    }

    private fun refresh() {
        val currentMode = DictationModePresets.current(this)
        modeDots.forEach { (mode, dot) ->
            OneUiComponents.setSelectionDotChecked(dot, mode == currentMode)
        }
        modeCustomNote.visibility = if (currentMode == DictationMode.CUSTOM) View.VISIBLE else View.GONE

        val useLocal = prefs().getBoolean("use_local", true)
        val cloudCleanup = CloudFeatureToggle.cleanupEnabled(this)
        val postProcessingOn = PostProcessingToggle.isEnabled(this)
        val allowLocalFallback = DictationModeToggle.allowLocalFallback(this)
        val allowCloudFallback = DictationModeToggle.allowCloudFallback(this)
        val localSttAvailable = LocalTranscriber.availableModels(this).isNotEmpty()
        val localCleanupAvailable = ModelDownloader.localCleanupModelFile(this, LocalCleanupProvider.selectedModel(this)) != null
        val chain = ProviderChainStore.load(this)
        val isConfigured: (ProviderChainEntry) -> Boolean = { ProviderCredentialStore.isConfiguredOrLegacy(this, it) }

        cloudTranscriptionSwitch.isChecked = !useLocal
        cloudTranscriptionRowSub.text = cloudTranscriptionSubtitle(chain, useLocal, localSttAvailable, allowLocalFallback, isConfigured)

        cloudCleanupSwitch.isChecked = cloudCleanup
        cloudCleanupRowSub.text = cloudCleanupSubtitle(chain, cloudCleanup, localCleanupAvailable, allowLocalFallback, isConfigured)

        routingTranscriptionSub.text = EffectiveRouting.transcription(
            chain = chain,
            useLocalTranscription = useLocal,
            allowLocalFallback = allowLocalFallback,
            allowCloudFallback = allowCloudFallback,
            isConfigured = isConfigured,
            localAvailable = localSttAvailable,
        )
        routingCleanupSub.text = EffectiveRouting.cleanup(
            chain = chain,
            postProcessingEnabled = postProcessingOn,
            cloudCleanupEnabled = cloudCleanup,
            allowLocalFallback = allowLocalFallback,
            isConfigured = isConfigured,
            localAvailable = localCleanupAvailable,
        )
    }

    private fun cloudTranscriptionSubtitle(
        chain: ProviderChain,
        useLocal: Boolean,
        localSttAvailable: Boolean,
        allowLocalFallback: Boolean,
        isConfigured: (ProviderChainEntry) -> Boolean,
    ): String {
        if (useLocal) {
            return if (localSttAvailable) {
                "Off \u2014 transcription runs on-device"
            } else {
                "Off \u2014 on-device not installed (download a model under Local speech recognition)"
            }
        }
        val activeEntry = chain.capableEntriesFor(needsTranscription = true)
            .firstOrNull { it.kind != ProviderKind.LOCAL && isConfigured(it) }
        return when {
            activeEntry != null -> "On \u2014 uses ${ProviderCapabilityRegistry.displayNameFor(activeEntry)} (Batch STT chain)"
            allowLocalFallback && localSttAvailable ->
                "On, but no active cloud STT key is locked \u2014 falls back to on-device"
            allowLocalFallback ->
                "On, but no active cloud STT key is locked \u2014 on-device not installed"
            else -> "On, but no active cloud STT key is locked"
        }
    }

    private fun cloudCleanupSubtitle(
        chain: ProviderChain,
        cloudCleanup: Boolean,
        localCleanupAvailable: Boolean,
        allowLocalFallback: Boolean,
        isConfigured: (ProviderChainEntry) -> Boolean,
    ): String {
        if (!cloudCleanup) {
            return if (localCleanupAvailable) {
                "Off \u2014 cleanup runs on-device"
            } else {
                "Off \u2014 on-device not installed (download a model under Local AI cleanup)"
            }
        }
        val activeEntry = chain.capableEntriesFor(needsTranscription = false)
            .firstOrNull { it.kind != ProviderKind.LOCAL && isConfigured(it) }
        return when {
            activeEntry != null -> "On \u2014 uses ${ProviderCapabilityRegistry.displayNameFor(activeEntry)} (Batch Cleanup chain)"
            allowLocalFallback && localCleanupAvailable ->
                "On, but no active cloud cleanup key is locked \u2014 falls back to on-device"
            allowLocalFallback ->
                "On, but no active cloud cleanup key is locked \u2014 on-device not installed"
            else -> "On, but no active cloud cleanup key is locked"
        }
    }
}
