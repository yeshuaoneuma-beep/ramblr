package com.trevornk.ramblr

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Dedicated "Local AI cleanup" screen (Phase 3A.3 hairline overhaul).
 *
 * Strictly manages local cleanup readiness status and on-device cleanup models (`Mumble Cleanup`,
 * `LFM2.5`) using [OneUiComponents.selectionDot], download/cancel/delete, and progress indicators.
 * Shared AI cleanup toggle, styles, and options now live in [CleanupStylesActivity]; cloud vs.
 * local routing lives in [DictationModeActivity] and [CloudProviderActivity].
 */
class CleanupActivity : BaseSettingsActivity() {

    private lateinit var readinessStatusSub: TextView

    private val cleanupModelRows = mutableMapOf<String, ModelRowViews>()
    private val cleanupModelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val cleanupModelDownloadGate = DownloadCompletionGate()

    private data class ModelRowViews(
        val selectionDot: FrameLayout,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(explainer(
            "Download and select an on-device AI cleanup model. Runs completely on this phone with no API key."
        ))

        root.addView(sectionHeader("Readiness"))
        val statusRow = settingsRow("On-device cleanup status", "Checking...")
        readinessStatusSub = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        root.addView(sectionHeader("Local cleanup models"))
        for (m in LOCAL_CLEANUP_MODEL_CATALOG) root.addView(buildCleanupModelRow(m))

        setOneUiScreenContent(
            title = "Local AI cleanup",
            showBack = true,
            icon = R.drawable.ic_settings_auto_awesome,
            content = root,
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val activeModel = selectedCleanupModel()
        val installed = ModelDownloader.isInstalled(this, activeModel)
        readinessStatusSub.text = if (installed) {
            "Ready \u00b7 ${activeModel.name} installed on this device"
        } else {
            "Not installed \u00b7 download a model below for offline AI cleanup or cloud fallback"
        }
        refreshAllCleanupRows()
    }

    private fun selectedCleanupModel(): Model =
        ModelDownloader.resolveActiveModel(
            LOCAL_CLEANUP_MODEL_CATALOG,
            prefs().getString(KEY_LOCAL_CLEANUP_MODEL_NAME, "") ?: "",
        ) { ModelDownloader.isInstalled(this, it) }

    private fun buildCleanupModelRow(model: Model): View {
        val dot = OneUiComponents.selectionDot(this, selected = false, contentDescription = model.name)
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\u2193"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onCleanupModelAction(model) }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\uD83D\uDDD1"
            textSize = 16f
            setOnClickListener { confirmDeleteCleanupModel(model) }
        }
        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(deleteBtn)
            addView(dot)
        }
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply { topMargin = dp(8) }
        }

        val row = settingsRow(model.name, cleanupModelSubtitle(model), rightContainer) {
            onCleanupModelAction(model)
        }
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        cleanupModelRows[model.archive] = ModelRowViews(
            dot, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn
        )
        observeCleanupModelDownload(model)
        refreshCleanupModelRow(model)
        return row
    }

    private fun onCleanupModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectCleanupModel(model.archive)
            return
        }
        if (ModelDownloader.isSideloadOnly(model)) {
            toast("${model.name} must be sideloaded manually — no download available")
            return
        }
        if (ModelDownloadWorker.isInFlight(cleanupModelDownloadState[model.archive])) return
        downloadModelWithLicenseConsent(model)
    }

    private fun selectCleanupModel(archive: String) {
        prefs().edit().putString(KEY_LOCAL_CLEANUP_MODEL_NAME, archive).apply()
        ProviderChainStore.save(this, ProviderChainStore.load(this).withLocalFloor(archive))
        refresh()
    }

    private fun confirmDeleteCleanupModel(model: Model) {
        val isActive = !CloudFeatureToggle.cleanupEnabled(this) &&
            selectedCleanupModel().archive == model.archive
        val activeNote = if (!isActive) "" else if (canFallBackToCloud()) {
            " Cleanup will switch back to Cloud."
        } else {
            " Cleanup will turn off (no cloud provider is configured)."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This frees ${model.sizeMb} MB of storage.$activeNote You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> deleteCleanupModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCleanupModel(model: Model) {
        val wasActive = !CloudFeatureToggle.cleanupEnabled(this) &&
            selectedCleanupModel().archive == model.archive
        ModelDownloader.delete(this, model)
        ModelLicenseConsent.clearAccepted(this, model)
        if (wasActive) {
            if (canFallBackToCloud()) {
                CloudFeatureToggle.setCleanupEnabled(this, true)
            } else {
                prefs().edit().putBoolean("use_post_processing", false).apply()
                toast("Cleanup turned off — no cloud provider configured")
            }
        }
        toast("${model.name} deleted")
        refresh()
    }

    private fun canFallBackToCloud(): Boolean =
        canFallBackToCloudCleanup(ProviderChainStore.load(this)) { ProviderCredentialStore.isConfiguredOrLegacy(this, it) }

    private fun observeCleanupModelDownload(model: Model) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(model.archive))
            .observe(this) { infos -> onCleanupModelWorkInfos(model, infos) }
    }

    internal fun onCleanupModelWorkInfos(model: Model, infos: List<WorkInfo>) {
        val views = cleanupModelRows[model.archive] ?: return
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        cleanupModelDownloadState[model.archive] = info?.state ?: WorkInfo.State.CANCELLED
        if (info != null && !info.state.isFinished) cleanupModelDownloadGate.onInFlight(info.id.toString())

        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                views.dlBtn.visibility = View.VISIBLE
                views.dlBtn.isEnabled = true
                views.dlBtn.text = "\u2715"
                views.dlBtn.setOnClickListener { ModelDownloadWorker.cancel(this, model) }
                views.progress.visibility = View.VISIBLE
                val phase = info.progress.getString(ModelDownloadWorker.KEY_PHASE)
                val retryText = ModelDownloadWorker.retryStatusFor(info.state, info.progress)
                if (phase == ModelDownloadWorker.PHASE_EXTRACTING) {
                    views.progress.isIndeterminate = true
                    views.subtitle.text = "Installing..."
                } else if (retryText != null) {
                    views.progress.isIndeterminate = true
                    views.subtitle.text = retryText
                } else {
                    val pct = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f)
                    views.progress.isIndeterminate = false
                    views.progress.progress = (pct * 100).toInt()
                    views.subtitle.text =
                        if (info.state == WorkInfo.State.ENQUEUED) "Starting download..."
                        else "Downloading: ${(pct * 100).toInt()}%"
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                if (cleanupModelDownloadGate.shouldActOnSuccess(info.id.toString())) {
                    selectCleanupModel(model.archive)
                    toast("${model.name} ready")
                }
                refreshCleanupModelRow(model)
            }
            WorkInfo.State.FAILED -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                views.dlBtn.text = "\u2193"
                views.dlBtn.setOnClickListener { onCleanupModelAction(model) }
                val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Unknown error"
                views.subtitle.text = "Error: $err"
            }
            else -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                refreshCleanupModelRow(model)
            }
        }
    }

    private fun refreshCleanupModelRow(model: Model) {
        val views = cleanupModelRows[model.archive] ?: return
        views.dlBtn.text = "\u2193"
        views.dlBtn.setOnClickListener { onCleanupModelAction(model) }
        val active = selectedCleanupModel().archive == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        val sideloadOnly = ModelDownloader.isSideloadOnly(model)

        OneUiComponents.setSelectionDotChecked(views.selectionDot, active)
        views.selectionDot.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed || sideloadOnly) View.GONE else View.VISIBLE
        views.deleteBtn.visibility = if (installed) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = cleanupModelSubtitle(model, installed = installed)
        }
    }

    private fun cleanupModelSubtitle(
        model: Model,
        installed: Boolean = ModelDownloader.isInstalled(this, model),
    ): String = cleanupModelSubtitleText(
        model = model,
        installed = installed,
        sideloadOnly = ModelDownloader.isSideloadOnly(model),
    )

    private fun refreshAllCleanupRows() = LOCAL_CLEANUP_MODEL_CATALOG.forEach { refreshCleanupModelRow(it) }

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val KEY_LOCAL_CLEANUP_MODEL_NAME = "local_cleanup_model_name"

        fun subtitle(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
            val archive = prefs.getString(KEY_LOCAL_CLEANUP_MODEL_NAME, "") ?: ""
            val model = ModelDownloader.resolveActiveModel(LOCAL_CLEANUP_MODEL_CATALOG, archive) {
                ModelDownloader.isInstalled(context, it)
            }
            return if (ModelDownloader.isInstalled(context, model)) {
                "Local \u00b7 ${model.name}"
            } else {
                "Local \u00b7 no model installed yet"
            }
        }
    }
}
