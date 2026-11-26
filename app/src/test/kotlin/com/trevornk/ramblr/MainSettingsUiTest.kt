package com.trevornk.ramblr

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainSettingsUiTest {

    @Before fun initWorkManager() {
        val app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun View.isShownInTree(): Boolean =
        generateSequence(this) { it.parent as? View }.all { it.visibility == View.VISIBLE }

    private fun View.row(title: String): View = all().first { v ->
        v.isClickable && v.findViewWithTag<TextView>("title")?.text?.toString() == title
    }

    @Test fun `main rows open the matching part of a shared screen`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun open(title: String) = root.row(title).performClick().let { shadowOf(activity).nextStartedActivity }

        open("Dictation mode").let {
            assertEquals(DictationModeActivity::class.java.name, it.component?.className)
        }
        open("Live transcription").let {
            assertEquals(LiveTranscriptionActivity::class.java.name, it.component?.className)
        }
        open("Cleanup styles").let {
            assertEquals(CleanupStylesActivity::class.java.name, it.component?.className)
        }
        open("Fallback").let {
            assertEquals(FallbackActivity::class.java.name, it.component?.className)
        }
        open("Notifications").let {
            assertEquals(NotificationsActivity::class.java.name, it.component?.className)
        }
        open("Voice keyboard").let {
            assertEquals(InvocationActivity::class.java.name, it.component?.className)
            assertEquals(InvocationActivity.Section.KEYBOARD.name, it.getStringExtra(InvocationActivity.EXTRA_SECTION))
        }
        open("Other ways to start").let {
            assertEquals(InvocationActivity.Section.SHORTCUTS.name, it.getStringExtra(InvocationActivity.EXTRA_SECTION))
        }
        open("History").let {
            assertEquals(DataLogsActivity.Section.HISTORY.name, it.getStringExtra(DataLogsActivity.EXTRA_SECTION))
            assertEquals(true, it.getBooleanExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, false))
        }
        open("Backup & restore").let {
            assertEquals(DataLogsActivity.Section.BACKUP.name, it.getStringExtra(DataLogsActivity.EXTRA_SECTION))
        }
        open("Diagnostics").let {
            assertEquals(DataLogsActivity.Section.DIAGNOSTICS.name, it.getStringExtra(DataLogsActivity.EXTRA_SECTION))
        }
        open("Privacy").let {
            assertEquals(BehaviorActivity.Section.PRIVACY.name, it.getStringExtra(BehaviorActivity.EXTRA_SECTION))
        }
    }

    @Test fun `picking a dictation mode applies the preset and updates the main row on resume`() {
        val app = RuntimeEnvironment.getApplication()
        val mainController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val main = mainController.get()
        val row = main.window.decorView.row("Dictation mode")
        row.performClick()

        val started = shadowOf(main).nextStartedActivity
        assertEquals(DictationModeActivity::class.java.name, started.component?.className)

        val modeActivity = Robolectric.buildActivity(DictationModeActivity::class.java).setup().get()
        modeActivity.window.decorView.row("Local").performClick()

        assertEquals(DictationMode.LOCAL, DictationModePresets.current(app))
        mainController.pause().resume()
        assertEquals(
            DictationModePresets.summary(DictationMode.LOCAL, cleanupOn = PostProcessingToggle.isEnabled(app)),
            row.findViewWithTag<TextView>("subtitle").text.toString(),
        )
    }

    @Test fun `closing history opened from the main screen leaves the save toggle reachable`() {
        val intent = DataLogsActivity.intent(RuntimeEnvironment.getApplication(), DataLogsActivity.Section.HISTORY)
            .putExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, true)
        val activity = Robolectric.buildActivity(DataLogsActivity::class.java, intent).setup().get()

        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        assertFalse(activity.isFinishing)
        assertTrue(activity.window.decorView.row("Save dictation history").isShownInTree())
    }

    @Test fun `main screen groups its rows under five task headings and has no search box`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        assertFalse("settings search was dropped", root.all().any { it is EditText && it.hint?.toString() == "Search settings" })

        val groups = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        val headings = setOf("Speech and text", "Starting dictation", "Your recordings", "Privacy and data", "More")
        for (v in root.all()) {
            if (v is TextView && v.text.toString() in headings && v.tag == null) {
                current = v.text.toString(); groups[current] = mutableListOf()
            } else if (v is TextView && v.tag == "title" && current != null) {
                groups[current]!!.add(v.text.toString())
            }
        }
        assertEquals(headings.toList(), groups.keys.toList())
        assertEquals(
            listOf(
                "Dictation mode",
                "Live transcription",
                "Local speech recognition",
                "Local AI cleanup",
                "Cleanup styles",
                "Cloud providers",
                "Fallback",
                "Words and snippets",
            ),
            groups["Speech and text"],
        )
        assertEquals(listOf("Floating icon", "Voice keyboard", "Other ways to start", "Recording"), groups["Starting dictation"])
        assertEquals(listOf("Notifications", "History", "Audio files"), groups["Your recordings"])
        assertEquals(listOf("Privacy", "Backup & restore"), groups["Privacy and data"])
        // "Updates" is github-flavor only, so assert the shared prefix.
        assertEquals(listOf("Setup", "Diagnostics"), groups["More"]!!.take(2))
        assertFalse("the Advanced screen is gone", root.all().any { it is TextView && it.text.toString() == "Advanced" })
    }

    @Test fun `floating icon row opens the screen that holds Show the icon`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val started = activity.window.decorView.row("Floating icon").performClick().let { shadowOf(activity).nextStartedActivity }
        assertEquals(BehaviorActivity.Section.ICON.name, started.getStringExtra(BehaviorActivity.EXTRA_SECTION))

        val icon = Robolectric.buildActivity(BehaviorActivity::class.java, started).setup().get()
        val titles = icon.window.decorView.all().filterIsInstance<TextView>().filter { it.tag == "title" }.map { it.text.toString() }
        assertTrue("Show the icon" in titles)
        assertTrue("Floating ring" in titles)
        assertTrue("Size and colors" in titles)
    }

    @Test fun `Local speech recognition holds Live preview and shows Cloud Live when keyed and enabled`() {
        val app = RuntimeEnvironment.getApplication()
        val geminiVaultId = ProviderCapabilityRegistry.canonicalVaultKeyId("gemini")
        try {
            ApiKeyVaultStore.upsert(
                app,
                ApiKeyVaultEntry(geminiVaultId, "gemini", "Gemini", locked = true),
                secret = "AIzaTestGeminiKeyForLivePreview",
            )
            LiveTranscriptionConfigStore.save(
                app,
                LiveTranscriptionConfig(enabled = true, providerLocked = true, modelLocked = true, vaultKeyId = geminiVaultId),
            )
            app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
                .putBoolean("streaming_preview_enabled", true)
                .putBoolean(CloudLiveToggle.KEY, true)
                .apply()

            val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val started = main.window.decorView.row("Local speech recognition").performClick().let { shadowOf(main).nextStartedActivity }
            assertEquals(TranscriptionActivity::class.java.name, started.component?.className)

            val recognition = Robolectric.buildActivity(TranscriptionActivity::class.java).setup().get()
            val live = recognition.window.decorView.row("Live preview")
            assertEquals("On · Cloud Live", live.findViewWithTag<TextView>("subtitle").text.toString())
            live.performClick()
            assertEquals(LivePreviewActivity::class.java.name, shadowOf(recognition).nextStartedActivity.component?.className)
        } finally {
            ProviderCredentialStore.clearVaultSecret(app, geminiVaultId)
        }
    }

    @Test fun `Cloud providers is dedicated to batch routing without Dictation mode or Fallback clutter`() {
        val cloud = Robolectric.buildActivity(CloudProviderActivity::class.java).setup().get()
        val texts = cloud.window.decorView.all().filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.any { it == "API keys" })
        assertTrue(texts.any { it == "Execution order" })
        assertTrue(texts.any { it == "Batch providers" })
        assertTrue(texts.any { it == "Audio upload" })
        assertFalse(texts.any { it == "Dictation mode" })
        assertFalse(texts.any { it == "Fall back to on-device if cloud fails" })
        assertFalse(texts.any { it.contains("(below)") })
    }
}
