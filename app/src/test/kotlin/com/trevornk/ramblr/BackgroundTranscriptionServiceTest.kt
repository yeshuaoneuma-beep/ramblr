package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * #284: wiring of the foreground service itself -- its declared type/permission, that it enters the
 * foreground with a dataSync notification, stops when nothing is held, and that the result
 * notifications live on their own channel and never leak transcript text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundTranscriptionServiceTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BackgroundTranscriptionService.resetForTest()
    }

    private fun startedServices(): List<Intent> {
        val out = mutableListOf<Intent>()
        while (true) out += shadowOf(app).nextStartedService ?: break
        return out
    }

    private fun nm() = app.getSystemService(NotificationManager::class.java)

    @Test fun `acquiring a hold requests exactly one service start, repeats do not duplicate it`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin(); work.begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, startedServices().size)
        work.end(); work.end()
    }

    @Test fun `stopping under a live hold lets the next acquire start a fresh service, with no duplicate`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin() // dictation A
        shadowOf(Looper.getMainLooper()).idle()
        val a = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
        val svc = a.create().startCommand(0, 1).get()
        startedServices() // drain A's start
        assertFalse(shadowOf(svc).isStoppedBySelf)

        svc.stopNow() // hard cap / platform timeout while A's hold is still counted
        assertEquals("A's hold is still counted, not stranded", 1, BackgroundTranscriptionService.heldCount())

        work.begin() // dictation B arrives
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("B must get a fresh start", 1, startedServices().size)
        svc.onDestroy() // the platform's late destroy of the OLD instance must not clear B's pending request
        work.begin() // dictation C while B's start is still pending
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no duplicate start while one is pending", 0, startedServices().size)

        work.end(); work.end(); work.end()
    }

    @Test fun `a hold taken on a live instance uses single non-resetting 840s cap instead of starting a second service`() {
        assertEquals(840_000L, BackgroundTranscriptionService.MAX_RUN_MS)
        assertEquals(30_000L, BackgroundTranscriptionService.MAX_RUN_MS - DictationRuntime.WATCHDOG_TIMEOUT_MS)

        var fakeElapsed = 10_000L
        BackgroundTranscriptionService.elapsedRealtimeMs = { fakeElapsed }
        val work = BackgroundTranscriptionService.work(app)
        work.begin()
        val expectedDeadline = 10_000L + 840_000L
        assertEquals(expectedDeadline, BackgroundTranscriptionService.activeRunDeadlineAtElapsedMs)

        val ctrl = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
        val svc = ctrl.create().startCommand(0, 1).get()
        startedServices()

        fakeElapsed += 500_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500_000L))
        assertFalse(shadowOf(svc).isStoppedBySelf)

        // Repeated acquire / startIfStillWanted / armSelfCap must not reset the 840s cap.
        work.begin()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, startedServices().size)
        assertEquals(expectedDeadline, BackgroundTranscriptionService.activeRunDeadlineAtElapsedMs)

        fakeElapsed += 339_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(339_000L))
        assertFalse("service must still be alive 1s before the original 840s deadline", shadowOf(svc).isStoppedBySelf)

        fakeElapsed += 2_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000L))
        assertTrue("repeated acquire must not extend self-cap past 840s from first hold", shadowOf(svc).isStoppedBySelf)

        work.end()
        assertEquals(expectedDeadline, BackgroundTranscriptionService.activeRunDeadlineAtElapsedMs)
        work.end()
        assertEquals(0L, BackgroundTranscriptionService.activeRunDeadlineAtElapsedMs)

        // After all holds release, a fresh run anchors a new 840s deadline.
        work.begin()
        assertEquals(fakeElapsed + 840_000L, BackgroundTranscriptionService.activeRunDeadlineAtElapsedMs)
        work.end()
    }

    @Test fun `ongoing notification has Cancel action wired to cancel the active dictation session`() {
        val n = BackgroundDictationNotifications.ongoing(app)
        val actions = n.actions.orEmpty()
        assertEquals(1, actions.size)
        assertEquals("Cancel", actions.single().title.toString())
        val savedIntent = shadowOf(actions.single().actionIntent).savedIntent
        assertEquals(BackgroundTranscriptionService::class.java.name, savedIntent.component?.className)
        assertEquals(BackgroundTranscriptionService.ACTION_CANCEL, savedIntent.action)
    }

    @Test fun `cancel notification action safely cancels the active runtime session and stops service`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val failures = mutableListOf<BackgroundFailure>()
        var idleCalls = 0
        val listener = object : RuntimeListener {
            override fun onRecordingStartRequested() {}
            override fun onRecordingStartFailed() {}
            override fun onRecordingStarted() {}
            override fun onEnterTranscribingUi() {}
            override fun onIdleUi() { idleCalls++ }
            override fun onStreamingTeardown() {}
            override fun onStreamingPartial(text: String) {}
            override fun deliverText(
                text: String,
                rawText: String?,
                paidFallbackGroup: CleanupStepGroup?,
                cleanupError: String?,
                feedbackDurationMs: Long,
            ) {}
            override fun foregroundPackageName(): String? = null
            override fun onDictationFailed(failure: BackgroundFailure) { failures += failure }
        }
        val leaseRegistry = InMemoryDictationSessionLeaseRegistry()
        val rt = DictationRuntime(
            app,
            listener,
            leaseRegistry,
            backgroundWork = BackgroundTranscriptionService.work(app),
            engineFactory = { dir, sm ->
                object : RecordingEngine(dir, sm) {
                    override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean =
                        sm.tryStartRecording()
                    override fun awaitTeardown(timeoutMs: Long): Boolean = true
                    override fun isReaderTeardownPending(): Boolean = false
                }
            },
        )
        rt.onTap()
        rt.onTap()
        rt.holdForLeavingHost()
        assertEquals(1, BackgroundTranscriptionService.heldCount())

        val startSvc = Robolectric.buildService(
            BackgroundTranscriptionService::class.java,
            Intent(app, BackgroundTranscriptionService::class.java),
        ).create().startCommand(0, 1).get()
        startedServices()

        val cancelIntent = Intent(app, BackgroundTranscriptionService::class.java)
            .setAction(BackgroundTranscriptionService.ACTION_CANCEL)
        startSvc.onStartCommand(cancelIntent, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RecordingStateMachine.State.IDLE, rt.currentState())
        assertEquals(0, BackgroundTranscriptionService.heldCount())
        assertEquals(0L, BackgroundTranscriptionService.activeRunDeadlineAtElapsedMs)
        assertTrue(failures.isEmpty())
        assertEquals(1, idleCalls)
        assertTrue(shadowOf(startSvc).isStoppedBySelf)
        // Confirm lease was released so a new recording can start immediately.
        rt.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, rt.currentState())
        rt.shutdown()
    }

    @Test fun `manifest declares the service non-exported with the dataSync type and its permission`() {
        val info = app.packageManager.getServiceInfo(
            android.content.ComponentName(app, BackgroundTranscriptionService::class.java),
            0,
        )
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val requested = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun `with a dictation held the service enters the foreground with an ongoing notification`() {
        val work = BackgroundTranscriptionService.work(app)
        work.begin()
        try {
            val ctrl = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
            val svc = ctrl.create().startCommand(0, 1).get()
            val n: Notification = shadowOf(svc).lastForegroundNotification
            assertNotNull(n)
            assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(BackgroundDictationNotifications.WORK_CHANNEL_ID, n.channelId)
            assertFalse("the ongoing notification must not contain transcript text",
                n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().isBlank())
            assertFalse(shadowOf(svc).isStoppedBySelf)
        } finally {
            work.end()
        }
    }

    @Test fun `a service that starts after everything already finished stops itself`() {
        // begin()/end() both happened before onStartCommand ran (a very short dictation).
        val ctrl = Robolectric.buildService(BackgroundTranscriptionService::class.java, Intent(app, BackgroundTranscriptionService::class.java))
        val svc = ctrl.create().startCommand(0, 1).get()
        assertTrue("no held work => it must not linger", shadowOf(svc).isStoppedBySelf)
    }

    @Test fun `result and work notifications use separate channels so the routine one can be muted alone`() {
        BackgroundDictationNotifications.ensureChannels(app)
        val work = nm().getNotificationChannel(BackgroundDictationNotifications.WORK_CHANNEL_ID)
        val result = nm().getNotificationChannel(BackgroundDictationNotifications.RESULT_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, work.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, result.importance)
    }

    @Test fun `posted result uses one fixed id so repeats replace instead of stacking`() {
        shadowOf(nm()).setNotificationsEnabled(true)
        val notice = failureNoticeFor(BackgroundFailure.FAILED)
        BackgroundDictationNotifications.postResult(app, notice)
        BackgroundDictationNotifications.postResult(app, failureNoticeFor(BackgroundFailure.TIMED_OUT))
        val all = shadowOf(nm()).allNotifications
        assertEquals(1, all.size)
        assertEquals(failureNoticeFor(BackgroundFailure.TIMED_OUT).title, all.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(all.single().contentIntent)
    }

    @Test fun `notifications disabled falls back to a toast instead of silence`() {
        shadowOf(nm()).setNotificationsEnabled(false)
        BackgroundDictationNotifications.postResult(app, failureNoticeFor(BackgroundFailure.FAILED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(nm()).allNotifications.isEmpty())
        assertEquals(
            "${failureNoticeFor(BackgroundFailure.FAILED).title}. ${failureNoticeFor(BackgroundFailure.FAILED).text}",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun `a muted result channel falls back to a toast even with app notifications enabled`() {
        shadowOf(nm()).setNotificationsEnabled(true)
        BackgroundDictationNotifications.ensureChannels(app)
        val muted = NotificationChannel(
            BackgroundDictationNotifications.RESULT_CHANNEL_ID, "Dictation results", NotificationManager.IMPORTANCE_NONE,
        )
        nm().createNotificationChannel(muted)
        BackgroundDictationNotifications.postResult(app, failureNoticeFor(BackgroundFailure.FAILED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(nm()).allNotifications.isEmpty())
        assertEquals(
            "${failureNoticeFor(BackgroundFailure.FAILED).title}. ${failureNoticeFor(BackgroundFailure.FAILED).text}",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test fun `history-opening notice targets the history viewer`() {
        val n = BackgroundDictationNotifications.result(
            app,
            deliveredNoticeFor(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, historySaved = true, cleanupFailed = false)!!,
        )
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(DataLogsActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, false))
    }

    @Test fun `notification ids are globally distinct across dictation and audio jobs`() {
        val ids = listOf(
            BackgroundDictationNotifications.ONGOING_ID,
            BackgroundDictationNotifications.RESULT_ID,
            BackgroundDictationNotifications.RECORDING_COUNTDOWN_ID,
            AudioJobNotifications.ONGOING_ID,
            AudioJobNotifications.RECORDING_ID,
            IconVisibilityNotifications.NOTIFICATION_ID,
            ServiceRecoveryNotifications.NOTIFICATION_ID,
        )
        assertEquals(0x1C0A, BackgroundDictationNotifications.RECORDING_COUNTDOWN_ID)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `updateStatus updates ongoing ONGOING_ID notification in place preserving invariants`() {
        shadowOf(nm()).setNotificationsEnabled(true)
        val work = BackgroundTranscriptionService.work(app)
        work.begin()
        try {
            Robolectric.buildService(
                BackgroundTranscriptionService::class.java,
                Intent(app, BackgroundTranscriptionService::class.java),
            ).create().startCommand(0, 1).get()

            work.updateStatus("OpenAI busy — retrying…")
            shadowOf(Looper.getMainLooper()).idle()

            val updated = shadowOf(nm()).getNotification(BackgroundDictationNotifications.ONGOING_ID)
            assertNotNull(updated)
            assertEquals(BackgroundDictationNotifications.WORK_CHANNEL_ID, updated.channelId)
            assertEquals("OpenAI busy — retrying…", updated.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(updated.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(Notification.COLOR_DEFAULT, updated.color)
            assertEquals(R.drawable.ic_mic, updated.smallIcon.resId)
            assertEquals("Cancel", updated.actions.single().title.toString())
        } finally {
            work.end()
        }
    }
}
