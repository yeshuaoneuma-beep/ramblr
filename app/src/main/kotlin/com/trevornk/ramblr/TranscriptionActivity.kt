package com.trevornk.ramblr

import android.content.Intent
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
 * Dedicated "Local speech recognition" screen (Phase 3A.3 hairline overhaul).
 *
 * Strictly manages on-device STT models (with [OneUiComponents.selectionDot], download/delete/
 * progress), Dictation language, Local transcription threads, Canary language, and Live preview.
 * Cloud mode switching and audio upload compression now live in [DictationModeActivity] and
 * [CloudProviderActivity].
 */
class TranscriptionActivity : BaseSettingsActivity() {

    private lateinit var dictationLanguageRowSub: TextView
    private lateinit var localThreadsRowSub: TextView
    private lateinit var canaryLanguageRowSub: TextView
    private lateinit var livePreviewRowSub: TextView

    private data class ModelRowViews(
        val selectionDot: FrameLayout,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton,
    )

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val modelDownloadState = mutableMapOf<String, WorkInfo.State>()
    private val modelDownloadGate = DownloadCompletionGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(explainer(
            "Download and select an on-device speech recognition model. Runs privately on this phone without internet."
        ))

        root.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) root.addView(buildModelRow(m))

        root.addView(sectionHeader("Language"))
        val languageRow = settingsRow("Dictation language", dictationLanguageSummary()) {
            promptDictationLanguage()
        }
        dictationLanguageRowSub = languageRow.findViewWithTag("subtitle")
        root.addView(languageRow)

        root.addView(sectionHeader("On-device tuning"))

        val localThreadsRow = settingsRow(
            "Local transcription threads",
            localThreadsSummary(),
            details = "On-device transcription only, runs on the CPU. Compare speeds with the benchmark log in Diagnostics",
        ) { promptLocalTranscriptionThreads() }
        localThreadsRowSub = localThreadsRow.findViewWithTag("subtitle")
        root.addView(localThreadsRow)

        val canaryLanguageRow = settingsRow(
            "Canary language",
            canaryLanguageSummary(),
            details = "The language you speak when dictating with the Canary local model. Only affects Canary; other models ignore this",
        ) { promptCanaryLanguage() }
        canaryLanguageRowSub = canaryLanguageRow.findViewWithTag("subtitle")
        root.addView(canaryLanguageRow)

        root.addView(sectionHeader("Live preview"))
        val livePreviewRow = settingsRow("Live preview", LivePreviewActivity.subtitle(this)) {
            startActivity(Intent(this, LivePreviewActivity::class.java))
        }
        livePreviewRowSub = livePreviewRow.findViewWithTag("subtitle")
        root.addView(livePreviewRow)

        setOneUiScreenContent(
            title = "Local speech recognition",
            showBack = true,
            icon = R.drawable.ic_settings_mic,
            content = root,
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val useLocal = prefs().getBoolean("use_local", true)
        prefetchVadModel(useLocal)

        dictationLanguageRowSub.text = dictationLanguageSummary()
        localThreadsRowSub.text = localThreadsSummary()
        canaryLanguageRowSub.text = canaryLanguageSummary()
        livePreviewRowSub.text = LivePreviewActivity.subtitle(this)

        val cur = prefs().getString("model_name", "") ?: ""
        val curModel = MODEL_CATALOG.firstOrNull { it.archive == cur }
        if (cur.isBlank() || curModel == null || !ModelDownloader.isInstalled(this, curModel)) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }
        refreshAllCards()
    }

    private fun dictationLanguageSummary(): String {
        val lang = DictationLanguage.languageOrNull(this)
        return if (lang == null) {
            "Auto-detect. Set this if your speech comes back in the wrong language"
        } else {
            "${DictationLanguage.label(lang)}. Sent to cloud transcription so it doesn't guess, and used by the Canary local model"
        }
    }

    private fun promptDictationLanguage() {
        val codes: List<String?> = listOf<String?>(null) + DictationLanguage.pickerOrder()
        val labels = codes.map { DictationLanguage.label(it) }
        val checkedIndex = codes.indexOf(DictationLanguage.languageOrNull(this)).coerceAtLeast(0)
        OneUiComponents.flatSingleChoiceDialog(
            context = this,
            title = "Dictation language",
            message = "The language you speak. Cloud transcription normally guesses it from the " +
                "audio and can guess English, which makes your speech come back in English. " +
                "Choosing your language stops the guessing. The Canary on-device model also " +
                "uses it when it is English, Spanish, German or French.",
            items = labels,
            checkedIndex = checkedIndex,
        ) { which ->
            DictationLanguage.setLanguage(this, codes[which])
            LocalModelReload.reloadIfCanaryActive(this)
            refresh()
        }
    }

    private fun localThreadsSummary(): String {
        val threads = LocalTranscriptionThreads.threadsOrDefault(this)
        return "$threads threads. More can be faster but uses more battery"
    }

    private fun promptLocalTranscriptionThreads() {
        val presets = LocalTranscriptionThreads.PRESET_THREADS
        val labels = presets.map { "$it threads" }
        val current = LocalTranscriptionThreads.threadsOrDefault(this)
        val checkedIndex = presets.indexOf(current).let { if (it < 0) 0 else it }
        OneUiComponents.flatSingleChoiceDialog(
            context = this,
            title = "Local transcription threads",
            message = "How many CPU threads on-device transcription uses to decode. The default (2) " +
                "works well on most phones; try 4 or 6 and compare against the benchmark log in Diagnostics.",
            items = labels,
            checkedIndex = checkedIndex,
        ) { which ->
            LocalTranscriptionThreads.setThreads(this, presets[which])
            refresh()
        }
    }

    private val canaryLanguageLabels = mapOf(
        "en" to "English (en)",
        "es" to "Spanish (es)",
        "de" to "German (de)",
        "fr" to "French (fr)",
    )

    private fun canaryLanguageSummary(): String {
        val lang = CanaryLanguage.languageOrDefault(this)
        val label = canaryLanguageLabels[lang] ?: lang
        val source = if (CanaryLanguage.followsDictationLanguage(this)) "follows Dictation language" else "set here"
        return "$label, $source"
    }

    private fun promptCanaryLanguage() {
        val languages = CanaryLanguage.SUPPORTED
        val dictation = DictationLanguage.languageOrNull(this)
        val followLabel = "Follow Dictation language (" +
            (if (dictation != null && dictation in languages) canaryLanguageLabels[dictation] ?: dictation
            else "English unless Dictation language is one of Canary's four") + ")"
        val labels = listOf(followLabel) + languages.map { canaryLanguageLabels[it] ?: it }
        val explicit = CanaryLanguage.explicitOrNull(this)
        val checkedIndex = if (explicit == null) 0 else languages.indexOf(explicit) + 1
        OneUiComponents.flatSingleChoiceDialog(
            context = this,
            title = "Canary language",
            message = "The language you speak when dictating with the Canary local model. " +
                "By default it follows Dictation language above. Only affects the Canary model.",
            items = labels,
            checkedIndex = checkedIndex,
        ) { which ->
            if (which == 0) CanaryLanguage.followDictationLanguage(this)
            else CanaryLanguage.setLanguage(this, languages[which - 1])
            LocalModelReload.reloadIfCanaryActive(this)
            refresh()
        }
    }

    private fun prefetchVadModel(useLocal: Boolean) {
        if (!VadModelProvisioning.shouldPrefetchForLocalMode(
                localTranscriptionSelected = useLocal,
                modelInstalled = ModelDownloader.vadModelFile(this, SILERO_VAD_MODEL) != null,
            )
        ) {
            return
        }
        android.util.Log.i(TAG, "Local mode active — prefetching VAD model for segmented decode (#169)")
        ModelDownloadWorker.enqueue(this, SILERO_VAD_MODEL)
    }

    private fun buildModelRow(model: Model): View {
        val dot = OneUiComponents.selectionDot(this, selected = false, contentDescription = model.name)
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\u2193"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener {
                UiTapHaptics.tick(it)
                onModelAction(model)
            }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\uD83D\uDDD1"
            textSize = 16f
            setOnClickListener {
                UiTapHaptics.tick(it)
                confirmDeleteModel(model)
            }
        }

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(deleteBtn)
            addView(dot)
        }

        val row = settingsRow(model.name, "${model.quality} \u00b7 ${model.sizeMb} MB", rightContainer) {
            onModelAction(model)
        }

        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        modelRows[model.archive] = ModelRowViews(
            dot, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn
        )
        refreshCard(model)
        observeDownload(model)

        return row
    }

    private fun onModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }
        if (ModelDownloadWorker.isInFlight(modelDownloadState[model.archive])) return
        downloadModelWithLicenseConsent(model)
    }

    private fun observeDownload(model: Model) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.workName(model.archive))
            .observe(this) { infos -> onWorkInfos(model, infos) }
    }

    private fun onWorkInfos(model: Model, infos: List<WorkInfo>) {
        val views = modelRows[model.archive] ?: return
        val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        modelDownloadState[model.archive] = info?.state ?: WorkInfo.State.CANCELLED
        if (info != null && !info.state.isFinished) modelDownloadGate.onInFlight(info.id.toString())

        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                views.dlBtn.isEnabled = false
                views.progress.visibility = View.VISIBLE
                val phase = info.progress.getString(ModelDownloadWorker.KEY_PHASE)
                val retryText = ModelDownloadWorker.retryStatusFor(info.state, info.progress)
                if (phase == ModelDownloadWorker.PHASE_EXTRACTING) {
                    views.progress.isIndeterminate = true
                    views.subtitle.text = "Extracting..."
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
                if (modelDownloadGate.shouldActOnSuccess(info.id.toString())) {
                    selectModel(model.archive)
                    toast("${model.name} ready!")
                }
                refreshCard(model)
            }
            WorkInfo.State.FAILED -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Unknown error"
                views.subtitle.text = "Error: $err"
            }
            else -> {
                views.progress.visibility = View.GONE
                views.dlBtn.isEnabled = true
                refreshCard(model)
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards()
    }

    private fun confirmDeleteModel(model: Model) {
        val isActive = prefs().getString("model_name", "") == model.archive
        val activeNote = if (isActive) " This is your currently selected model." else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This frees ${model.sizeMb} MB of storage.$activeNote You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> deleteModel(model) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteModel(model: Model) {
        ModelDownloader.delete(this, model)
        val remaining = MODEL_CATALOG
            .filter { it.archive != model.archive && ModelDownloader.isInstalled(this, it) }
            .map { it.archive }
        val newSelection = ModelDownloader.resolveSelectionAfterDelete(
            prefs().getString("model_name", "") ?: "", model.archive, remaining
        )
        prefs().edit().putString("model_name", newSelection).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        toast("${model.name} deleted")
        refreshAllCards()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        OneUiComponents.setSelectionDotChecked(views.selectionDot, active)
        views.selectionDot.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.deleteBtn.visibility = if (installed) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} \u00b7 ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    companion object {
        private const val TAG = "TranscriptionActivity"
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT

        internal fun resolveInstalledTranscriptionModel(
            context: android.content.Context,
            persistIfBlank: Boolean = false,
        ): Model? {
            val prefs = context.getSharedPreferences("ramblr", android.content.Context.MODE_PRIVATE)
            val archive = prefs.getString("model_name", "") ?: ""
            if (archive.isNotBlank()) {
                return MODEL_CATALOG.firstOrNull { it.archive == archive && ModelDownloader.isInstalled(context, it) }
            }
            val fallback = MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(context, it) } ?: return null
            if (persistIfBlank) {
                prefs.edit().putString("model_name", fallback.archive).apply()
            }
            return fallback
        }

        fun subtitle(context: android.content.Context): String {
            val model = resolveInstalledTranscriptionModel(context, persistIfBlank = false)
            return if (model != null) "Local \u00b7 ${model.name}" else "Local \u00b7 no model installed yet"
        }
    }
}
