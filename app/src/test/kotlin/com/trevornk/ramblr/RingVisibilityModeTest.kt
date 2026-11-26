package com.trevornk.ramblr

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingVisibilityModeTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    class AppOnlyWindowService : WhisperAccessibilityService() {
        var fakeWindows: MutableList<AccessibilityWindowInfo> = mutableListOf()
        override fun getWindows(): MutableList<AccessibilityWindowInfo> = fakeWindows
    }

    private var activeService: AppOnlyWindowService? = null

    @Before fun setUp() {
        prefs.edit().clear().commit()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun tearDown() {
        activeService?.onDestroy()
        activeService = null
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        prefs.edit().clear().commit()
    }

    private fun startLiveServiceWithoutKeyboard(): AppOnlyWindowService {
        val service = Robolectric.buildService(AppOnlyWindowService::class.java, null).create().get()
        val appWindow = AccessibilityWindowInfo.obtain()
        shadowOf(appWindow).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        service.fakeWindows = mutableListOf(appWindow)
        // Register as the live singleton instance and attach its overlay without kicking off background model threads.
        val instanceField = WhisperAccessibilityService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
        instanceField.set(null, service)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }
            .invoke(service)
        activeService = service
        return service
    }

    private fun overlayAlpha(service: WhisperAccessibilityService): Float {
        val overlay = WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }
            .get(service) as android.view.View
        return overlay.alpha
    }

    @Test fun `defaults to always`() {
        assertEquals(RingVisibilityMode.ALWAYS, RingVisibilityMode.read(prefs))
    }

    @Test fun `writing a mode round-trips`() {
        for (mode in RingVisibilityMode.entries) {
            RingVisibilityMode.write(prefs, mode)
            assertEquals(mode, RingVisibilityMode.read(prefs))
        }
    }

    @Test fun `an unknown stored value falls back to always`() {
        prefs.edit().putString(RingVisibilityMode.KEY, "SOMETHING_NEW").apply()
        assertEquals(RingVisibilityMode.ALWAYS, RingVisibilityMode.read(prefs))
    }

    @Test fun `each mode exposes valid label and summary string resources`() {
        val labels = RingVisibilityMode.entries.map { app.getString(it.labelRes) }
        val summaries = RingVisibilityMode.entries.map { app.getString(it.summaryRes) }
        assertTrue(labels.all { it.isNotBlank() })
        assertTrue(summaries.all { it.isNotBlank() })
        assertEquals(RingVisibilityMode.entries.size, labels.toSet().size)
        assertEquals(RingVisibilityMode.entries.size, summaries.toSet().size)
        assertNotEquals(RingVisibilityMode.TEXT_FIELD.labelRes, RingVisibilityMode.TEXT_FIELD.summaryRes)
    }

    @Test fun `write with context notifies live accessibility service immediately`() {
        val service = startLiveServiceWithoutKeyboard()
        assertEquals(1f, overlayAlpha(service), 0.001f)

        // Switching to KEYBOARD with only an application window hides the overlay immediately
        // via WhisperAccessibilityService.notifyRingVisibilityChanged() -- even without a prefs listener.
        RingVisibilityMode.write(app, RingVisibilityMode.KEYBOARD)
        assertEquals(RingVisibilityMode.KEYBOARD, RingVisibilityMode.read(app))
        assertEquals(0f, overlayAlpha(service), 0.001f)

        // Switching back to ALWAYS makes the overlay visible immediately.
        RingVisibilityMode.write(app, RingVisibilityMode.ALWAYS)
        assertEquals(RingVisibilityMode.ALWAYS, RingVisibilityMode.read(app))
        assertEquals(1f, overlayAlpha(service), 0.001f)
    }

    @Test fun `BehaviorActivity picker persists choice, refreshes summary, and updates live overlay`() {
        val service = startLiveServiceWithoutKeyboard()
        val controller = Robolectric.buildActivity(BehaviorActivity::class.java).setup()
        val activity = controller.get()
        try {
            // BaseSettingsActivity.onResume marks MainActivityForeground=true; clear it so we can
            // observe the visibility-mode gate itself toggling the live overlay alpha.
            WhisperAccessibilityService.setMainActivityForeground(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1f, overlayAlpha(service), 0.001f)

            val promptMethod = BehaviorActivity::class.java.getDeclaredMethod("promptRingVisibility")
                .apply { isAccessible = true }
            val summaryMethod = BehaviorActivity::class.java.getDeclaredMethod("ringVisibilitySummary")
                .apply { isAccessible = true }

            fun dialogOptionRows(dialog: AlertDialog): List<Pair<String, android.view.View>> {
                val out = mutableListOf<Pair<String, android.view.View>>()
                fun walk(v: android.view.View) {
                    if (v is android.view.ViewGroup && v.isClickable && v.hasOnClickListeners()) {
                        val tv = (0 until v.childCount).map { v.getChildAt(it) }
                            .filterIsInstance<android.widget.TextView>()
                            .firstOrNull { !it.text.isNullOrBlank() }
                        if (tv != null) out += tv.text.toString() to v
                    }
                    if (v is android.view.ViewGroup) {
                        for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                }
                walk(dialog.window!!.decorView)
                return out
            }

            promptMethod.invoke(activity)
            val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
            val expectedLabels = RingVisibilityMode.entries.map { activity.getString(it.labelRes) }
            val firstRows = dialogOptionRows(dialog).filter { it.first in expectedLabels }
            assertEquals(expectedLabels, firstRows.map { it.first })

            // Pick KEYBOARD.
            firstRows.first { it.first == activity.getString(RingVisibilityMode.KEYBOARD.labelRes) }.second.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(RingVisibilityMode.KEYBOARD, RingVisibilityMode.read(activity))
            assertEquals(
                activity.getString(R.string.ring_visibility_summary, activity.getString(RingVisibilityMode.KEYBOARD.summaryRes)),
                summaryMethod.invoke(activity),
            )
            assertEquals(0f, overlayAlpha(service), 0.001f)

            // Pick ALWAYS and confirm live overlay reappears immediately.
            promptMethod.invoke(activity)
            val secondDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
            val secondRows = dialogOptionRows(secondDialog).filter { it.first in expectedLabels }
            secondRows.first { it.first == activity.getString(RingVisibilityMode.ALWAYS.labelRes) }.second.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(RingVisibilityMode.ALWAYS, RingVisibilityMode.read(activity))
            assertEquals(
                activity.getString(R.string.ring_visibility_summary, activity.getString(RingVisibilityMode.ALWAYS.summaryRes)),
                summaryMethod.invoke(activity),
            )
            assertEquals(1f, overlayAlpha(service), 0.001f)
        } finally {
            controller.destroy()
        }
    }
}
