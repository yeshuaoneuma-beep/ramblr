package com.trevornk.ramblr

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives [DictationModeActivity]'s "Dictation mode" presets (using circular selectionDots with
 * zero RadioButtons/RadioGroups): pick a preset, flip one per-feature override (mode becomes
 * Custom, so no preset is selected), then tap the previous or a different preset and verify it
 * stays selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudProviderModeSelectionTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun startInCloudMode() {
        val prefs = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_local", false).apply()
        CloudFeatureToggle.setCleanupEnabled(app, true)
    }

    private fun View.allDescendants(): Sequence<View> = sequence {
        yield(this@allDescendants)
        if (this@allDescendants is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).allDescendants())
        }
    }

    private fun View.rowTitled(title: String): View =
        allDescendants().first { (it.findViewWithTag<TextView>("title")?.text?.toString() == title) && it.isClickable }

    @Test fun `re-tapping the previous preset after a per-feature override keeps it checked`() {
        startInCloudMode()
        val activity = Robolectric.buildActivity(DictationModeActivity::class.java).setup().get()
        val root = activity.window.decorView
        assertTrue("zero RadioButtons", root.allDescendants().none { it is RadioButton || it is RadioGroup })
        assertEquals(DictationMode.CLOUD, DictationModePresets.current(app))

        // Flip cloud cleanup off: transcription stays cloud, cleanup goes local -> Custom.
        root.rowTitled("Cleanup").performClick()
        assertEquals(DictationMode.CUSTOM, DictationModePresets.current(app))

        root.rowTitled("Cloud").performClick()
        assertEquals(DictationMode.CLOUD, DictationModePresets.current(app))
    }

    @Test fun `picking a different preset after a per-feature override checks exactly that preset`() {
        startInCloudMode()
        val activity = Robolectric.buildActivity(DictationModeActivity::class.java).setup().get()
        val root = activity.window.decorView
        assertTrue("zero RadioButtons", root.allDescendants().none { it is RadioButton || it is RadioGroup })

        root.rowTitled("Cleanup").performClick()
        assertEquals(DictationMode.CUSTOM, DictationModePresets.current(app))

        root.rowTitled("Fastest").performClick()
        assertEquals(DictationMode.FASTEST, DictationModePresets.current(app))
    }
}
