package com.trevornk.ramblr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Dedicated Fallback configuration screen (Phase 3A.3).
 *
 * Uses the exact existing [DictationModeToggle] SharedPreferences keys for cloud<->on-device
 * failover, displays live on-device STT & Cleanup install readiness with links to their model
 * screens, and summarizes the effective routing.
 */
class FallbackActivity : BaseSettingsActivity() {

    private lateinit var localFallbackSwitch: MaterialSwitch
    private lateinit var localFallbackRowSub: TextView
    private lateinit var cloudFallbackSwitch: MaterialSwitch
    private lateinit var cloudFallbackRowSub: TextView
    private lateinit var localSttStatusSub: TextView
    private lateinit var localCleanupStatusSub: TextView
    private lateinit var routingTranscriptionSub: TextView
    private lateinit var routingCleanupSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProviderAccountMigration.runIfNeeded(this)
        ApiKeyVaultMigration.runIfNeeded(this)
        KeyGateNormalizer.normalizeAndPersistIfChanged(this)

        val root = vertical(0, 0)

        root.addView(explainer(
            "Control whether Rhema Cadence automatically switches between cloud providers and on-device models if one fails."
        ))

        root.addView(sectionHeader("Automatic failover"))

        localFallbackSwitch = MaterialSwitch(this).apply { isClickable = false }
        val localFallbackRow = settingsRow(
            title = "Fall back to on-device if cloud fails",
            subtitle = "",
            widget = localFallbackSwitch,
        ) {
            DictationModeToggle.setAllowLocalFallback(this, !localFallbackSwitch.isChecked)
            refresh()
        }
        localFallbackRowSub = localFallbackRow.findViewWithTag("subtitle")
        root.addView(localFallbackRow)

        cloudFallbackSwitch = MaterialSwitch(this).apply { isClickable = false }
        val cloudFallbackRow = settingsRow(
            title = "Fall back to cloud if on-device fails",
            subtitle = "",
            widget = cloudFallbackSwitch,
        ) {
            DictationModeToggle.setAllowCloudFallback(this, !cloudFallbackSwitch.isChecked)
            refresh()
        }
        cloudFallbackRowSub = cloudFallbackRow.findViewWithTag("subtitle")
        root.addView(cloudFallbackRow)

        root.addView(sectionHeader("On-device models"))

        val localSttRow = settingsRow(
            title = "Local speech recognition",
            subtitle = "Checking...",
        ) { startActivity(Intent(this, TranscriptionActivity::class.java)) }
        localSttStatusSub = localSttRow.findViewWithTag("subtitle")
        root.addView(localSttRow)

        val localCleanupRow = settingsRow(
            title = "Local AI cleanup",
            subtitle = "Checking...",
        ) { startActivity(Intent(this, CleanupActivity::class.java)) }
        localCleanupStatusSub = localCleanupRow.findViewWithTag("subtitle")
        root.addView(localCleanupRow)

        root.addView(sectionHeader("Effective routing"))

        val routingTransRow = settingsRow("Transcription", "")
        routingTranscriptionSub = routingTransRow.findViewWithTag("subtitle")
        root.addView(routingTransRow)

        val routingCleanRow = settingsRow("Cleanup", "")
        routingCleanupSub = routingCleanRow.findViewWithTag("subtitle")
        root.addView(routingCleanRow)

        setOneUiScreenContent(
            title = "Fallback",
            showBack = true,
            icon = R.drawable.ic_settings_fallback,
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
        val allowLocal = DictationModeToggle.allowLocalFallback(this)
        val allowCloud = DictationModeToggle.allowCloudFallback(this)
        val localSttModel = TranscriptionActivity.resolveInstalledTranscriptionModel(this, persistIfBlank = false)
        val localSttReady = localSttModel != null
        val localCleanupModel = LocalCleanupProvider.selectedModel(this)
        val localCleanupReady = ModelDownloader.localCleanupModelFile(this, localCleanupModel) != null

        localFallbackSwitch.isChecked = allowLocal
        localFallbackRowSub.text = when {
            allowLocal && (localSttReady || localCleanupReady) ->
                "On \u2014 if every cloud provider fails, dictation falls back to on-device"
            allowLocal ->
                "On, but on-device models are not installed yet \u2014 install one below"
            else ->
                "Off \u2014 if cloud fails, you'll see an error instead of falling back on-device"
        }

        cloudFallbackSwitch.isChecked = allowCloud
        cloudFallbackRowSub.text = if (allowCloud) {
            "On \u2014 if on-device isn't ready or fails, dictation falls back to cloud"
        } else {
            "Off \u2014 if on-device isn't ready or fails, you'll see an error instead of falling back to cloud"
        }

        localSttStatusSub.text = if (localSttModel != null) {
            "${localSttModel.name} (ready) \u2014 tap to manage"
        } else {
            "Not installed \u2014 tap to download an on-device speech model"
        }

        localCleanupStatusSub.text = if (localCleanupReady) {
            "${localCleanupModel.name} (ready) \u2014 tap to manage"
        } else {
            "Not installed \u2014 tap to download an on-device cleanup model"
        }

        val chain = ProviderChainStore.load(this)
        val isConfigured: (ProviderChainEntry) -> Boolean = { ProviderCredentialStore.isConfiguredOrLegacy(this, it) }
        routingTranscriptionSub.text = EffectiveRouting.transcription(
            chain = chain,
            useLocalTranscription = prefs().getBoolean("use_local", true),
            allowLocalFallback = allowLocal,
            allowCloudFallback = allowCloud,
            isConfigured = isConfigured,
            localAvailable = localSttReady,
        )
        routingCleanupSub.text = EffectiveRouting.cleanup(
            chain = chain,
            postProcessingEnabled = PostProcessingToggle.isEnabled(this),
            cloudCleanupEnabled = CloudFeatureToggle.cleanupEnabled(this),
            allowLocalFallback = allowLocal,
            isConfigured = isConfigured,
            localAvailable = localCleanupReady,
        )
    }

    companion object {
        fun subtitle(context: Context): String {
            val localSttInstalled = TranscriptionActivity.resolveInstalledTranscriptionModel(context, persistIfBlank = false) != null
            val localCleanupInstalled = ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context)) != null
            return fallbackMainRowSubtitleText(
                allowLocalFallback = DictationModeToggle.allowLocalFallback(context),
                allowCloudFallback = DictationModeToggle.allowCloudFallback(context),
                localSttInstalled = localSttInstalled,
                localCleanupInstalled = localCleanupInstalled,
            )
        }
    }
}
