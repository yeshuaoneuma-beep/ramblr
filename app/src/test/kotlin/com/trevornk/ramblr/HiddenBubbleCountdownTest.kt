package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 2C tests for 7:00 countdown fallback on [WhisperAccessibilityService]:
 * - Unlocked / screen-on with hidden ring ([IconHiddenState] or [RingVisibilityMode]):
 *   non-touchable overlay pill is shown without touching [IconHiddenState], leaving overlayView
 *   at alpha 0 and both roots permanently [View.VISIBLE] (BLASTSyncEngine invariant).
 * - Locked / screen-off: overlay pill is hidden (alpha 0, never [View.GONE]) and an ongoing
 *   low-importance notification is posted/updated on [BackgroundDictationNotifications.WORK_CHANNEL_ID].
 * - Immediate notification cancellation on stop, cancel, unlock, and destroy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenBubbleCountdownTest {

    class TestService : WhisperAccessibilityService() {
        var fakeWindows: MutableList<AccessibilityWindowInfo> = mutableListOf()
        override fun getWindows(): MutableList<AccessibilityWindowInfo> = fakeWindows
    }

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
    private lateinit var service: TestService
    private lateinit var overlayView: View
    private lateinit var feedbackView: TextView
    private lateinit var feedbackParams: WindowManager.LayoutParams

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ActiveDictationHostRegistry.resetForTest()

        service = Robolectric.buildService(TestService::class.java, null).create().get()
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(service.getSystemService(PowerManager::class.java)).turnScreenOn(true)

        val runtimeListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }
            .get(service) as RuntimeListener
        val runtime = DictationRuntime(
            app,
            runtimeListener,
            InMemoryDictationSessionLeaseRegistry(),
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }
            .set(service, runtime)
        WhisperAccessibilityService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, service)

        WhisperAccessibilityService::class.java.getDeclaredMethod("registerScreenStateReceiver")
            .apply { isAccessible = true }
            .invoke(service)
        WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
            .apply { isAccessible = true }
            .invoke(service)

        overlayView = WhisperAccessibilityService::class.java.getDeclaredField("overlayView")
            .apply { isAccessible = true }
            .get(service) as View
        feedbackView = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
            .apply { isAccessible = true }
            .get(service) as TextView
        feedbackParams = WhisperAccessibilityService::class.java.getDeclaredField("feedbackLayoutParams")
            .apply { isAccessible = true }
            .get(service) as WindowManager.LayoutParams
    }

    @After fun tearDown() {
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        ActiveDictationHostRegistry.resetForTest()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun notificationManager() = app.getSystemService(NotificationManager::class.java)

    private fun countdownNotification(): Notification? =
        shadowOf(notificationManager()).getNotification(BackgroundDictationNotifications.RECORDING_COUNTDOWN_ID)

    @Test fun `accessibilitySurface routes unlocked screen-on to pill and locked or screen-off to notification`() {
        assertEquals(
            RecordingCountdownSurface.OVERLAY_PILL,
            RecordingCountdown.accessibilitySurface(lockedByKeyguard = false, screenOff = false),
        )
        assertEquals(
            RecordingCountdownSurface.LOCKSCREEN_NOTIFICATION,
            RecordingCountdown.accessibilitySurface(lockedByKeyguard = true, screenOff = false),
        )
        assertEquals(
            RecordingCountdownSurface.LOCKSCREEN_NOTIFICATION,
            RecordingCountdown.accessibilitySurface(lockedByKeyguard = false, screenOff = true),
        )
        assertEquals(
            RecordingCountdownSurface.LOCKSCREEN_NOTIFICATION,
            RecordingCountdown.accessibilitySurface(lockedByKeyguard = true, screenOff = true),
        )
    }

    @Test fun `unlocked with IconHiddenState shows non-touchable overlay pill without unhiding icon`() {
        IconHiddenState.setHidden(app, true)
        service.applyOverlayVisibility()
        assertEquals(0f, overlayView.alpha, 0.001f)
        assertEquals(View.VISIBLE, overlayView.visibility)

        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)

        assertTrue("IconHiddenState must stay hidden", IconHiddenState.isHidden(app))
        assertEquals(0f, overlayView.alpha, 0.001f)
        assertEquals(View.VISIBLE, overlayView.visibility)
        assertEquals(View.VISIBLE, feedbackView.visibility)
        assertEquals(1f, feedbackView.alpha, 0.001f)
        assertTrue(feedbackView.text.toString().contains("3:00"))
        assertTrue(
            "countdown pill must remain non-touchable",
            feedbackParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
        assertNull("unlocked screen-on must not post countdown notification", countdownNotification())

        // Re-applying visibility keeps the countdown pill visible (never GONE) and ring hidden.
        service.applyOverlayVisibility()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, feedbackView.visibility)
        assertEquals(1f, feedbackView.alpha, 0.001f)
        assertEquals(0f, overlayView.alpha, 0.001f)
    }

    @Test fun `unlocked with RingVisibilityMode KEYBOARD keeps non-touchable countdown pill visible`() {
        val appWindow = AccessibilityWindowInfo.obtain()
        shadowOf(appWindow).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        service.fakeWindows = mutableListOf(appWindow)
        RingVisibilityMode.write(app, RingVisibilityMode.KEYBOARD)

        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)
        service.applyOverlayVisibility()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, feedbackView.visibility)
        assertEquals(1f, feedbackView.alpha, 0.001f)
        assertTrue(feedbackView.text.toString().contains("3:00"))
        assertTrue(feedbackParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertNull(countdownNotification())
    }

    @Test fun `locked or screen-off never draws overlay bubble and posts ongoing low-importance notification`() {
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        service.applyOverlayVisibility()

        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)

        assertEquals(View.VISIBLE, overlayView.visibility)
        assertEquals(View.VISIBLE, feedbackView.visibility)
        assertEquals(0f, feedbackView.alpha, 0.001f)

        val initial = countdownNotification()
        assertNotNull("locked recording at 7:00 must post ongoing notification", initial)
        assertTrue(initial!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(BackgroundDictationNotifications.WORK_CHANNEL_ID, initial.channelId)
        val channel = notificationManager().getNotificationChannel(BackgroundDictationNotifications.WORK_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue(initial.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("3:00"))

        // Next tick updates the notification and never shows the overlay bubble.
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        val updated = countdownNotification()!!
        assertTrue(updated.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("2:59"))
        assertEquals(0f, feedbackView.alpha, 0.001f)
        assertEquals(View.VISIBLE, feedbackView.visibility)

        // Screen-off while unlocked also suppresses overlay pill and updates notification.
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        val screenOffNotice = countdownNotification()!!
        assertTrue(screenOffNotice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("2:58"))
        assertEquals(0f, feedbackView.alpha, 0.001f)
        assertEquals(View.VISIBLE, feedbackView.visibility)
    }

    @Test fun `countdown notification cancels immediately on unlock, stop, cancel, and destroy`() {
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        service.applyOverlayVisibility()

        // 1. Unlock switches back to overlay pill and cancels notification immediately.
        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)
        assertNotNull(countdownNotification())

        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        service.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("unlocking must cancel the lockscreen countdown notification", countdownNotification())
        assertEquals(1f, feedbackView.alpha, 0.001f)

        // 2. Re-lock then stop -> cancels immediately.
        shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        service.applyOverlayVisibility()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(countdownNotification())
        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("stopping must cancel countdown notification", countdownNotification())

        // Reset back to IDLE via cancelTranscription.
        service.runtime.cancelTranscription()
        shadowOf(Looper.getMainLooper()).idle()

        // 3. Cancel recording while locked -> cancels immediately.
        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)
        assertNotNull(countdownNotification())
        assertTrue(service.runtime.cancelRecording())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("cancelRecording must cancel countdown notification", countdownNotification())

        // 4. Service destroy while locked -> cancels immediately.
        service.runtime.onTap()
        shadowOf(Looper.getMainLooper()).idleFor(420_000, TimeUnit.MILLISECONDS)
        assertNotNull(countdownNotification())
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("onDestroy must cancel countdown notification", countdownNotification())
    }
}
