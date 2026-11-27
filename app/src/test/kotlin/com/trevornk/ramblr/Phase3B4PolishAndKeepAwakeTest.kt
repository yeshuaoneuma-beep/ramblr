package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 3B.4 verification tests:
 * - Concentric ring-with-core lockCircle / selectionDot indicators & zero RadioButtons
 * - KeepScreenAwakePrefs, BehaviorActivity UI + custom timer sheet, and FLAG_KEEP_SCREEN_ON lifecycle
 * - App-wide UiTapHaptics wiring (without altering bubble recording haptics)
 * - Draggable recording capsule + edge snap persistence + cancel button
 * - ApiKeysVaultActivity Show/Hide clean reveal without false dirty state
 * - BaseSettingsActivity top status-bar spacer + 150ms debounced onPause foreground tracking
 * - Merged manifest FileProvider authority audit
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3B4PolishAndKeepAwakeTest {

    class TestService : WhisperAccessibilityService()

    private class FakeRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean =
            stateMachine.tryStartRecording()

        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false
    }

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        BaseSettingsActivity.cancelPendingForegroundClear()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ActiveDictationHostRegistry.resetForTest()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        ApiKeyVaultStore.save(app, emptyList())
        ProviderChainStore.save(app, ProviderChain(emptyList()))
        UiTapHaptics.onTickForTest = null
        ShadowAlertDialog.reset()
    }

    @After fun tearDown() {
        UiTapHaptics.onTickForTest = null
        BaseSettingsActivity.cancelPendingForegroundClear()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ActiveDictationHostRegistry.resetForTest()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlertDialog.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildService(): TestService {
        val service = Robolectric.buildService(TestService::class.java, null).create().get()
        val runtimeListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener
        val runtime = DictationRuntime(
            app,
            runtimeListener,
            InMemoryDictationSessionLeaseRegistry(),
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }.set(service, runtime)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }.invoke(service)
        idle()
        return service
    }

    // --- 1. Concentric Ring-With-Core Indicators ---

    @Test fun `concentric lockCircle and selectionDot match ring-with-core geometry and colors`() {
        val activity = Robolectric.buildActivity(BehaviorActivity::class.java).setup().get()
        val dp44 = OneUiComponents.dp(activity, 44)
        val dp20 = OneUiComponents.dp(activity, 20)
        val dp9 = OneUiComponents.dp(activity, 9)
        val lockedGreen = OneUiComponents.resColor(activity, R.color.oneui_locked_green)
        val primaryCobalt = OneUiComponents.attrColor(activity, com.google.android.material.R.attr.colorPrimary)

        val unlocked = OneUiComponents.lockCircle(activity, locked = false, contentDescription = "Lock test") {}
        assertEquals(dp44, unlocked.layoutParams.width)
        assertEquals(dp44, unlocked.layoutParams.height)
        assertEquals("unlocked", unlocked.tag)
        assertFalse(unlocked.isSelected)
        val unlockedRing = unlocked.findViewWithTag<View>(OneUiComponents.TAG_DOT_RING)
        val unlockedCore = unlocked.findViewWithTag<View>(OneUiComponents.TAG_DOT_CORE)
        assertNotNull(unlockedRing)
        assertNotNull(unlockedCore)
        assertEquals(dp20, unlockedRing.layoutParams.width)
        assertEquals(dp20, unlockedRing.layoutParams.height)
        assertEquals(dp9, unlockedCore.layoutParams.width)
        assertEquals(dp9, unlockedCore.layoutParams.height)
        assertEquals(View.GONE, unlockedCore.visibility)
        assertEquals(Color.TRANSPARENT, (unlockedRing.background as GradientDrawable).color?.defaultColor)

        val locked = OneUiComponents.lockCircle(activity, locked = true, contentDescription = "Lock test") {}
        assertEquals("locked", locked.tag)
        assertTrue(locked.isSelected)
        val lockedCore = locked.findViewWithTag<View>(OneUiComponents.TAG_DOT_CORE)
        assertEquals(View.VISIBLE, lockedCore.visibility)
        assertEquals(lockedGreen, (lockedCore.background as GradientDrawable).color?.defaultColor)

        val dot = OneUiComponents.selectionDot(activity, selected = false, contentDescription = "Choice")
        assertEquals(dp44, dot.layoutParams.width)
        assertEquals(dp44, dot.layoutParams.height)
        assertEquals("unselected", dot.tag)
        assertFalse(dot.isSelected)
        assertEquals(View.GONE, dot.findViewWithTag<View>(OneUiComponents.TAG_DOT_CORE).visibility)

        OneUiComponents.setSelectionDotChecked(dot, true)
        assertEquals("selected", dot.tag)
        assertTrue(dot.isSelected)
        val selectedCore = dot.findViewWithTag<View>(OneUiComponents.TAG_DOT_CORE)
        assertEquals(View.VISIBLE, selectedCore.visibility)
        assertEquals(primaryCobalt, (selectedCore.background as GradientDrawable).color?.defaultColor)

        assertNoRadioWidgets(activity.window.decorView)
    }

    // --- 2. KeepScreenAwakePrefs + BehaviorActivity + Service FLAG_KEEP_SCREEN_ON ---

    @Test fun `KeepScreenAwakePrefs round-trips modes, clamps bounds, and formats durations`() {
        assertEquals(KeepScreenAwakeMode.OFF, KeepScreenAwakePrefs.loadMode(app))
        assertEquals(300_000L, KeepScreenAwakePrefs.customDurationMs(app))

        for (mode in KeepScreenAwakeMode.entries) {
            KeepScreenAwakePrefs.saveMode(app, mode)
            assertEquals(mode, KeepScreenAwakePrefs.loadMode(app))
        }

        assertEquals(60_000L, KeepScreenAwakePrefs.clampCustomMs(1_000L))
        assertEquals(7_200_000L, KeepScreenAwakePrefs.clampCustomMs(9_000_000L))
        KeepScreenAwakePrefs.saveCustomDurationMs(app, 5_000L)
        assertEquals(60_000L, KeepScreenAwakePrefs.customDurationMs(app))
        KeepScreenAwakePrefs.saveCustomDurationMs(app, 10_000_000L)
        assertEquals(7_200_000L, KeepScreenAwakePrefs.customDurationMs(app))

        assertEquals("1 min", KeepScreenAwakePrefs.formatDuration(60_000L))
        assertEquals("5 min", KeepScreenAwakePrefs.formatDuration(300_000L))
        assertEquals("1 hr", KeepScreenAwakePrefs.formatDuration(3_600_000L))
        assertEquals("1 hr 30 min", KeepScreenAwakePrefs.formatDuration(5_400_000L))
        assertEquals("2 hr", KeepScreenAwakePrefs.formatDuration(7_200_000L))
    }

    @Test fun `BehaviorActivity Recording section shows 4 Keep screen awake rows and custom timer picker`() {
        val controller = Robolectric.buildActivity(
            BehaviorActivity::class.java,
            BehaviorActivity.intent(app, BehaviorActivity.Section.DICTATION),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        assertNoRadioWidgets(root)

        val texts = collectTexts(root)
        assertTrue("Subsection header missing: $texts", texts.contains("Keep screen awake"))
        assertTrue(texts.contains("Off"))
        assertTrue(texts.contains("While recording"))
        assertTrue(texts.contains("Auto (until inserted)"))
        assertTrue(texts.contains("Custom timer"))

        findRowByTitle(root, "While recording")!!.performClick()
        assertEquals(KeepScreenAwakeMode.WHILE_RECORDING, KeepScreenAwakePrefs.loadMode(app))
        assertTrue(activity.keepAwakeDots[KeepScreenAwakeMode.WHILE_RECORDING]!!.isSelected)
        assertFalse(activity.keepAwakeDots[KeepScreenAwakeMode.OFF]!!.isSelected)

        // Tapping Custom timer selects CUSTOM_TIMER and opens the bottom sheet picker.
        val customRow = findRowByTitle(root, "Custom timer")!!
        customRow.performClick()
        idle()
        val sheet = activity.keepAwakeTimerSheet!!
        assertTrue(sheet.isShowing)
        assertEquals(KeepScreenAwakeMode.CUSTOM_TIMER, KeepScreenAwakePrefs.loadMode(app))

        // Tap 15 min preset -> saves 900_000L, dismisses sheet, updates subtitle.
        val sheetDecor = sheet.window!!.decorView
        val preset15 = findViewByText(sheetDecor, "15 min")!!
        (preset15.parent as View).performClick()
        idle()
        assertFalse(sheet.isShowing)
        assertEquals(900_000L, KeepScreenAwakePrefs.customDurationMs(app))
        assertTrue(customRow.findViewWithTag<TextView>("subtitle").text.toString().contains("15 min"))

        // Reopen sheet and test NumberPicker hours/minutes clamping + Save.
        customRow.performClick()
        idle()
        val sheet2 = activity.keepAwakeTimerSheet!!
        val sheet2Decor = sheet2.window!!.decorView
        val hours = sheet2Decor.findViewWithTag<NumberPicker>(OneUiComponents.TAG_KEEP_AWAKE_HOURS)
        val minutes = sheet2Decor.findViewWithTag<NumberPicker>(OneUiComponents.TAG_KEEP_AWAKE_MINUTES)
        hours.value = 1
        minutes.value = 30
        sheet2Decor.findViewWithTag<View>(OneUiComponents.TAG_KEEP_AWAKE_SAVE).performClick()
        idle()
        assertFalse(sheet2.isShowing)
        assertEquals(5_400_000L, KeepScreenAwakePrefs.customDurationMs(app))
        assertTrue(customRow.findViewWithTag<TextView>("subtitle").text.toString().contains("1 hr 30 min"))
        controller.pause().stop().destroy()
    }

    @Test fun `WhisperAccessibilityService manages FLAG_KEEP_SCREEN_ON across all 4 KeepScreenAwake modes`() {
        val service = buildService()
        val listener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }.get(service) as RuntimeListener
        try {
            // 1. OFF: never sets FLAG_KEEP_SCREEN_ON.
            KeepScreenAwakePrefs.saveMode(app, KeepScreenAwakeMode.OFF)
            listener.onRecordingStarted()
            idle()
            assertFalse(service.isOverlayKeepScreenOnForTest())

            // 2. WHILE_RECORDING: sets on recording start, clears on entering transcribing UI.
            KeepScreenAwakePrefs.saveMode(app, KeepScreenAwakeMode.WHILE_RECORDING)
            listener.onRecordingStarted()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            listener.onEnterTranscribingUi()
            idle()
            assertFalse(service.isOverlayKeepScreenOnForTest())

            // 3. AUTO_UNTIL_INSERTED: stays on through transcribing, clears on onIdleUi().
            KeepScreenAwakePrefs.saveMode(app, KeepScreenAwakeMode.AUTO_UNTIL_INSERTED)
            listener.onRecordingStarted()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            listener.onEnterTranscribingUi()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            listener.onIdleUi()
            idle()
            assertFalse(service.isOverlayKeepScreenOnForTest())

            // 4. CUSTOM_TIMER: stays on through transcribing until timer expires, and clears on cancel/onDestroy.
            KeepScreenAwakePrefs.saveMode(app, KeepScreenAwakeMode.CUSTOM_TIMER)
            KeepScreenAwakePrefs.saveCustomDurationMs(app, 60_000L)
            listener.onRecordingStarted()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            listener.onEnterTranscribingUi()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            shadowOf(Looper.getMainLooper()).idleFor(60_000L, TimeUnit.MILLISECONDS)
            assertFalse(service.isOverlayKeepScreenOnForTest())

            // Re-arm and verify cancel via onIdleUi clears immediately.
            listener.onRecordingStarted()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            listener.onIdleUi()
            idle()
            assertFalse(service.isOverlayKeepScreenOnForTest())

            // Re-arm and verify onDestroy cleans up flag.
            listener.onRecordingStarted()
            idle()
            assertTrue(service.isOverlayKeepScreenOnForTest())
            service.onDestroy()
            idle()
            assertFalse(service.isOverlayKeepScreenOnForTest())
        } finally {
            service.onDestroy()
        }
    }

    // --- 3. App-wide UiTapHaptics ---

    @Test fun `UiTapHaptics fires on in-app controls when enabled and stays silent when disabled`() {
        var tickCount = 0
        UiTapHaptics.onTickForTest = { tickCount++ }

        ApiKeyVaultStore.upsert(
            app,
            ApiKeyVaultEntry("vault_gemini", ProviderCapabilityRegistry.ID_GEMINI, "Gemini", locked = true),
            secret = "AIzaSyValidTestVaultKey987654",
        )
        ProviderChainStore.save(
            app,
            ProviderChain(
                listOf(
                    ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", id = "c1"),
                    ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.6-luna", id = "c2"),
                )
            ),
        )

        fun exerciseControls() {
            val notifController = Robolectric.buildActivity(NotificationsActivity::class.java).setup()
            findViewByContentDesc(notifController.get().window.decorView, "Navigate up")!!.performClick()
            notifController.pause().stop().destroy()

            val vaultController = Robolectric.buildActivity(ApiKeysVaultActivity::class.java).setup()
            val vaultRoot = vaultController.get().window.decorView
            findViewByText(vaultRoot, "Show")!!.performClick()
            findViewByText(vaultRoot, "Hide")!!.performClick()
            findViewByText(vaultRoot, "Save")!!.performClick()
            vaultController.pause().stop().destroy()

            val cloudController = Robolectric.buildActivity(CloudProviderActivity::class.java).setup()
            val cloudRoot = cloudController.get().window.decorView
            findViewByContentDesc(cloudRoot, "Move down")!!.performClick()
            cloudController.pause().stop().destroy()

            val liveController = Robolectric.buildActivity(LiveTranscriptionActivity::class.java).setup()
            val liveRoot = liveController.get().window.decorView
            (findViewByText(liveRoot, "Smart")!!.parent as View).performClick()
            liveController.pause().stop().destroy()
        }

        HapticPrefs.setUiTapsEnabled(app, true)
        tickCount = 0
        exerciseControls()
        assertEquals("Expected ticks from backBtn, Show, Hide, Save, Move down, Smart tab", 6, tickCount)

        HapticPrefs.setUiTapsEnabled(app, false)
        tickCount = 0
        exerciseControls()
        assertEquals(0, tickCount)
    }

    // --- 4. Draggable Recording Capsule ---

    @Test fun `recording capsule drags to opposite edge without stopping recording and collapses on dragged edge`() {
        ActiveRecordingCapsuleToggle.setEnabled(app, true)
        val service = buildService()
        try {
            val overlay = WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
                .apply { isAccessible = true }.get(service) as FrameLayout
            val params = WhisperAccessibilityService::class.java.getDeclaredField("layoutParams")
                .apply { isAccessible = true }.get(service) as WindowManager.LayoutParams
            val capsule = service.recordingCapsuleView!!
            val initialRingSize = params.width

            service.runtime.onTap()
            idle()
            assertEquals(RecordingStateMachine.State.RECORDING, service.runtime.currentState())
            assertEquals(View.VISIBLE, capsule.visibility)
            assertFalse("Capsule body must not intercept touches", capsule.isClickable)
            val cancelBtn = findViewByContentDesc(capsule, "Cancel recording")!!
            assertTrue("Cancel button must stay clickable", cancelBtn.isClickable)
            assertTrue(params.width > initialRingSize)

            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY),
            )
            overlay.layout(0, 0, params.width, params.height)

            val startX = params.x
            val startY = params.y
            // Touch near left side of capsule (away from trailing ✕ button) and drag past slop to left edge.
            val down = MotionEvent.obtain(1000L, 1000L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
            val move = MotionEvent.obtain(1000L, 1040L, MotionEvent.ACTION_MOVE, -140f, 70f, 0)
            val up = MotionEvent.obtain(1000L, 1080L, MotionEvent.ACTION_UP, -140f, 70f, 0)
            try {
                overlay.dispatchTouchEvent(down)
                overlay.dispatchTouchEvent(move)
                overlay.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                move.recycle()
                up.recycle()
            }
            idle()

            assertEquals("Dragging capsule must NOT stop recording", RecordingStateMachine.State.RECORDING, service.runtime.currentState())
            assertEquals(startY + 60, params.y)
            val margin = OneUiComponents.dp(app, 8)
            assertEquals("Capsule must snap to left edge", margin, params.x)
            assertTrue("Should have moved away from right-edge startX=$startX", params.x != startX)

            val preCapsuleDockedX = WhisperAccessibilityService::class.java.getDeclaredField("preCapsuleDockedX")
                .apply { isAccessible = true }.getInt(service)
            assertEquals(margin, preCapsuleDockedX)
            val prefs = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            assertEquals(margin, prefs.getInt("overlay_x", -1))
            assertEquals(params.y, prefs.getInt("overlay_y", -1))

            // Tapping cancel ✕ inside capsule still cancels recording and collapses on the newly dragged left edge.
            cancelBtn.performClick()
            idle()
            assertEquals(RecordingStateMachine.State.IDLE, service.runtime.currentState())
            assertEquals(View.GONE, capsule.visibility)
            assertEquals(initialRingSize, params.width)
            assertEquals(margin, params.x)
        } finally {
            service.onDestroy()
        }
    }

    // --- 5. ApiKeysVaultActivity Show/Hide Clean Reveal & No False Dirty Flag ---

    @Test fun `ApiKeysVaultActivity Show reveals secret without dirty flag and Hide restores blank hint`() {
        val secret = "AIzaSyTestVaultRevealSecret12345"
        ApiKeyVaultStore.upsert(
            app,
            ApiKeyVaultEntry("vault_gemini", ProviderCapabilityRegistry.ID_GEMINI, "Gemini", locked = true),
            secret = secret,
        )

        val controller = Robolectric.buildActivity(ApiKeysVaultActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView

        val dirtyField = ApiKeysVaultActivity::class.java.getDeclaredField("dirtyCardIds")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val dirtyCardIds = dirtyField.get(activity) as Set<String>

        val keyInput = findFirstEditText(root)!!
        val showBtn = findViewByText(root, "Show") as TextView
        assertEquals("", keyInput.text.toString())
        assertTrue(dirtyCardIds.isEmpty())

        showBtn.performClick()
        assertEquals(secret, keyInput.text.toString())
        assertEquals("Hide", showBtn.text.toString())
        assertEquals(
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            keyInput.inputType and InputType.TYPE_MASK_VARIATION,
        )
        assertTrue("Revealing saved secret must NOT mark card dirty", dirtyCardIds.isEmpty())

        showBtn.performClick()
        assertEquals("", keyInput.text.toString())
        assertEquals("Show", showBtn.text.toString())
        assertEquals(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            keyInput.inputType and InputType.TYPE_MASK_VARIATION,
        )
        assertTrue("Hiding saved secret must NOT mark card dirty", dirtyCardIds.isEmpty())

        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull("Must NOT show Unsaved API key changes dialog", ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    // --- 6. BaseSettingsActivity Status-Bar Spacer & 150ms Debounced onPause ---

    @Test fun `BaseSettingsActivity applies opaque top status-bar spacer while keeping bottom edge-to-edge`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val spacer = contentRoot.findViewWithTag<View>(BaseSettingsActivity.STATUS_BAR_SPACER_TAG)
        val scroll = findFirstView(contentRoot, ScrollView::class.java)!!

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 80, 0, 48))
            .build()
        ViewCompat.dispatchApplyWindowInsets(contentRoot, insets)

        assertNotNull(spacer)
        assertEquals(80, spacer.layoutParams.height)
        assertFalse(scroll.clipToPadding)
        assertEquals(0, scroll.paddingTop)
        val expectedBottom = 48 + (BaseSettingsActivity.BOTTOM_SPACE_DP * activity.resources.displayMetrics.density).toInt()
        assertEquals(expectedBottom, scroll.paddingBottom)
        controller.pause().stop().destroy()
    }

    @Test fun `BaseSettingsActivity debounces onPause by 150ms so in-app navigation never flickers foreground`() {
        val mainController = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertTrue(WhisperAccessibilityService.isMainActivityForegroundForTest())

        // Pausing MainActivity must not immediately drop foreground.
        mainController.pause()
        assertTrue(WhisperAccessibilityService.isMainActivityForegroundForTest())

        // Resuming another BaseSettingsActivity within 150ms cancels the pending clear.
        shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
        val behaviorController = Robolectric.buildActivity(
            BehaviorActivity::class.java,
            BehaviorActivity.intent(app, BehaviorActivity.Section.DICTATION),
        ).create().start().resume()
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
        assertTrue("Foreground must stay true across Activity-to-Activity transition", WhisperAccessibilityService.isMainActivityForegroundForTest())

        // Leaving Settings completely clears foreground after 150ms.
        behaviorController.pause()
        assertTrue(WhisperAccessibilityService.isMainActivityForegroundForTest())
        shadowOf(Looper.getMainLooper()).idleFor(BaseSettingsActivity.PAUSE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        assertFalse(WhisperAccessibilityService.isMainActivityForegroundForTest())

        mainController.stop().destroy()
        behaviorController.stop().destroy()
    }

    // --- 7. Manifest Authority Audit ---

    @Test fun `merged manifest providers use applicationId and never hardcode com_trevornk_ramblr`() {
        assertEquals("com.yeshua.rhemacadence", app.packageName)
        @Suppress("DEPRECATION")
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
        val providers = packageInfo.providers.orEmpty().toList()
        assertTrue("Expected at least one FileProvider in merged manifest", providers.isNotEmpty())
        val authorities = providers.mapNotNull { it.authority }
        assertTrue("Expected com.yeshua.rhemacadence.fileprovider in $authorities", authorities.contains("com.yeshua.rhemacadence.fileprovider"))
        assertFalse(
            "Found hardcoded com.trevornk.ramblr authority in $authorities",
            authorities.any { it.contains("com.trevornk.ramblr") },
        )
    }

    // --- Helpers ---

    private fun assertNoRadioWidgets(root: View) {
        assertFalse("RadioButton must never appear", containsViewType(root, RadioButton::class.java))
        assertFalse("RadioGroup must never appear", containsViewType(root, RadioGroup::class.java))
    }

    private fun <T : View> containsViewType(root: View, cls: Class<T>): Boolean {
        if (cls.isInstance(root)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsViewType(root.getChildAt(i), cls)) return true
            }
        }
        return false
    }

    private fun <T : View> findFirstView(root: View, cls: Class<T>): T? {
        if (cls.isInstance(root)) return cls.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstView(root.getChildAt(i), cls)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstEditText(root: View): EditText? = findFirstView(root, EditText::class.java)

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        if (root is TextView && root.visibility == View.VISIBLE && root.text.isNotBlank()) {
            out.add(root.text.toString())
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) out += collectTexts(root.getChildAt(i))
        }
        return out
    }

    private fun findRowByTitle(root: View, title: String): View? {
        if (root.isClickable && root.findViewWithTag<TextView>("title")?.text?.toString() == title) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findRowByTitle(root.getChildAt(i), title)?.let { return it }
        }
        return null
    }

    private fun findViewByText(root: View, text: String): View? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findViewByText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun findViewByContentDesc(root: View, desc: String): View? {
        if (root.contentDescription?.toString() == desc) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findViewByContentDesc(root.getChildAt(i), desc)?.let { return it }
        }
        return null
    }
}
