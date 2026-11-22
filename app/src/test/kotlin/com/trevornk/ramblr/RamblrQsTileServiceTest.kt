package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Phase 2C Quick Settings tile tests covering:
 * - [QsTileCollapseActivity] manifest contract, intent flags, and immediate no-UI finish.
 * - [startActivityAndCollapseCompat] API 30-33 Intent path.
 * - Host-agnostic [RamblrQsTileService] routing across Accessibility and IME hosts
 *   (disconnected, IDLE, RECORDING, attached/detached TRANSCRIBING cancel, and lock-screen
 *   unlockAndRun handling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RamblrQsTileServiceTest {

    class RecordingTileService : RamblrQsTileService() {
        val launched = mutableListOf<Pair<Intent, Int>>()
        override fun collapseAndStart(intent: Intent, requestCode: Int) {
            launched += intent to requestCode
        }
    }

    class FakeAccessibilityService : WhisperAccessibilityService()

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
    private var a11yService: FakeAccessibilityService? = null
    private var imeService: RamblrImeService? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        setLiveAccessibilityInstance(null)
        ActiveDictationHostRegistry.resetForTest()
    }

    @After fun tearDown() {
        a11yService?.onDestroy()
        a11yService = null
        imeService?.onDestroy()
        imeService = null
        setLiveAccessibilityInstance(null)
        ActiveDictationHostRegistry.resetForTest()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun setLiveAccessibilityInstance(service: WhisperAccessibilityService?) {
        WhisperAccessibilityService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, service)
    }

    private fun attachLiveAccessibilityService(
        leaseRegistry: DictationSessionLeaseRegistry = InMemoryDictationSessionLeaseRegistry(),
    ): FakeAccessibilityService {
        val service = Robolectric.buildService(FakeAccessibilityService::class.java, null).create().get()
        val runtimeListener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
            .apply { isAccessible = true }
            .get(service) as RuntimeListener
        val runtime = DictationRuntime(
            app,
            runtimeListener,
            leaseRegistry,
        ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
        WhisperAccessibilityService::class.java.getDeclaredField("runtimeInstance")
            .apply { isAccessible = true }
            .set(service, runtime)
        setLiveAccessibilityInstance(service)
        a11yService = service
        return service
    }

    private fun attachLiveImeService(
        leaseRegistry: DictationSessionLeaseRegistry = InMemoryDictationSessionLeaseRegistry(),
    ): Pair<RamblrImeService, DictationRuntime> {
        lateinit var builtRuntime: DictationRuntime
        val service = Robolectric.buildService(RamblrImeService::class.java).create().get()
        service.runtimeFactoryForTest = { ctx, listener ->
            DictationRuntime(
                ctx,
                listener,
                leaseRegistry,
            ) { cacheDir, stateMachine -> FakeRecordingEngine(cacheDir, stateMachine) }
                .also { builtRuntime = it }
        }
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "com.example.ime"
            fieldId = 7
        }
        service.onCreateInputView()
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        imeService = service
        return service to builtRuntime
    }

    private fun buildTileService(): RecordingTileService =
        Robolectric.buildService(RecordingTileService::class.java).create().get()

    // --- manifest & QsTileCollapseActivity ---

    @Test fun `QsTileCollapseActivity is registered as non-exported translucent no-history no-recents trampoline`() {
        val pm = app.packageManager
        val info = pm.getActivityInfo(ComponentName(app, QsTileCollapseActivity::class.java), 0)
        assertFalse("trampoline must not be exported", info.exported)
        assertEquals(android.R.style.Theme_Translucent_NoTitleBar, info.theme)
        assertTrue(
            "must exclude from Recents",
            info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0,
        )
        assertTrue(
            "must set noHistory",
            info.flags and ActivityInfo.FLAG_NO_HISTORY != 0,
        )
        assertTrue(
            "must have no taskAffinity",
            info.taskAffinity.isNullOrEmpty(),
        )
        assertNotEquals(
            "must not inherit default package taskAffinity",
            app.packageName,
            info.taskAffinity,
        )
    }

    @Test fun `QsTileCollapseActivity intent sets new-task and no-animation and activity finishes immediately`() {
        val intent = QsTileCollapseActivity.intent(app)
        assertEquals(QsTileCollapseActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)

        val controller = Robolectric.buildActivity(QsTileCollapseActivity::class.java, intent).create()
        assertTrue("trampoline must finish immediately inside onCreate", controller.get().isFinishing)
    }

    @Test
    @Config(sdk = [33])
    fun `startActivityAndCollapseCompat on API 33 uses Intent overload and starts collapse activity`() {
        val tileService = Robolectric.buildService(RamblrQsTileService::class.java).create().get()
        val intent = QsTileCollapseActivity.intent(tileService)
        startActivityAndCollapseCompat(tileService, intent, RamblrQsTileService.REQUEST_COLLAPSE_SHADE)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(QsTileCollapseActivity::class.java.name, started.component?.className)
    }

    // --- Accessibility click routing ---

    @Test fun `clicking tile while no host is active opens Accessibility Settings and collapses shade`() {
        val tile = buildTileService()
        tile.onClick()

        assertEquals(1, tile.launched.size)
        val (intent, requestCode) = tile.launched.single()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(RamblrQsTileService.REQUEST_ACCESSIBILITY_SETTINGS, requestCode)
    }

    @Test fun `clicking tile while IDLE starts recording, restores hidden icon, collapses shade, and updates tile`() {
        val a11y = attachLiveAccessibilityService()
        IconHiddenState.setHidden(app, true)
        val tile = buildTileService()

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RecordingStateMachine.State.RECORDING, a11y.runtime.currentState())
        assertFalse("starting from tile must restore a hidden floating icon", IconHiddenState.isHidden(app))
        val (intent, requestCode) = tile.launched.single()
        assertEquals(QsTileCollapseActivity::class.java.name, intent.component?.className)
        assertEquals(RamblrQsTileService.REQUEST_COLLAPSE_SHADE, requestCode)

        // Delayed tile state update (150ms) reflects RECORDING state.
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_recording), tile.qsTile.subtitle)
    }

    @Test fun `clicking tile while RECORDING stops recording into TRANSCRIBING and collapses shade`() {
        val a11y = attachLiveAccessibilityService()
        a11y.runtime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, a11y.runtime.currentState())
        val tile = buildTileService()

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RecordingStateMachine.State.TRANSCRIBING, a11y.runtime.currentState())
        val (intent, requestCode) = tile.launched.single()
        assertEquals(QsTileCollapseActivity::class.java.name, intent.component?.className)
        assertEquals(RamblrQsTileService.REQUEST_COLLAPSE_SHADE, requestCode)
    }

    @Test fun `clicking tile while Accessibility is TRANSCRIBING cancels transcription to IDLE and collapses shade`() {
        val a11y = attachLiveAccessibilityService()
        a11y.runtime.onTap()
        a11y.runtime.onTap()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, a11y.runtime.currentState())
        val tile = buildTileService()

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)

        assertEquals(RecordingStateMachine.State.IDLE, a11y.runtime.currentState())
        val (intent, requestCode) = tile.launched.single()
        assertEquals(QsTileCollapseActivity::class.java.name, intent.component?.className)
        assertEquals(RamblrQsTileService.REQUEST_COLLAPSE_SHADE, requestCode)
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        assertNull(tile.qsTile.subtitle)
    }

    @Test fun `clicking tile while locked delegates through unlockAndRun before routing`() {
        val a11y = attachLiveAccessibilityService()
        val tile = buildTileService()
        shadowOf(tile).setLocked(true)

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RecordingStateMachine.State.RECORDING, a11y.runtime.currentState())
        assertEquals(1, tile.launched.size)
        assertEquals(QsTileCollapseActivity::class.java.name, tile.launched.single().first.component?.className)
    }

    // --- Host-agnostic IME routing ---

    @Test fun `tile reflects and stops active IME recording even while Accessibility is IDLE or disconnected`() {
        val sharedLeases = InMemoryDictationSessionLeaseRegistry()
        val a11y = attachLiveAccessibilityService(sharedLeases)
        val (_, imeRuntime) = attachLiveImeService(sharedLeases)

        imeRuntime.onTap()
        assertEquals(RecordingStateMachine.State.RECORDING, imeRuntime.currentState())
        assertEquals(RecordingStateMachine.State.IDLE, a11y.runtime.currentState())

        val tile = buildTileService()
        tile.updateTileState()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_recording), tile.qsTile.subtitle)

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RecordingStateMachine.State.TRANSCRIBING, imeRuntime.currentState())
        assertEquals(RecordingStateMachine.State.IDLE, a11y.runtime.currentState())
        assertEquals(QsTileCollapseActivity::class.java.name, tile.launched.single().first.component?.className)
        assertEquals(RamblrQsTileService.REQUEST_COLLAPSE_SHADE, tile.launched.single().second)
    }

    @Test fun `tile reflects and cancels active attached or detached IME transcription`() {
        val (ime, imeRuntime) = attachLiveImeService()
        imeRuntime.onTap()
        imeRuntime.onTap()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, imeRuntime.currentState())

        // Detach IME window (user left field / pulled down shade); transcription remains active.
        ime.onWindowHidden()
        assertEquals(RecordingStateMachine.State.TRANSCRIBING, imeRuntime.currentState())

        val tile = buildTileService()
        tile.updateTileState()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_transcribing), tile.qsTile.subtitle)

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)

        assertEquals(RecordingStateMachine.State.IDLE, imeRuntime.currentState())
        assertEquals(QsTileCollapseActivity::class.java.name, tile.launched.single().first.component?.className)
        assertEquals(RamblrQsTileService.REQUEST_COLLAPSE_SHADE, tile.launched.single().second)

        // Detached IME with no bound editor and Accessibility disconnected reports disconnected & opens Settings.
        ime.onFinishInput()
        tile.updateTileState()
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_service_disabled), tile.qsTile.subtitle)

        tile.onClick()
        assertEquals(2, tile.launched.size)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, tile.launched[1].first.action)
        assertEquals(RamblrQsTileService.REQUEST_ACCESSIBILITY_SETTINGS, tile.launched[1].second)
    }

    // --- tile state presentation ---

    @Test fun `updateTileState reflects disconnected, turned-off, idle, recording, and transcribing states`() {
        val tile = buildTileService()

        // Disconnected on fresh install -> disabled subtitle, INACTIVE.
        tile.updateTileState()
        assertEquals(app.getString(R.string.tile_label), tile.qsTile.label)
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_service_disabled), tile.qsTile.subtitle)

        // Previously enabled then turned off -> turned-off subtitle, INACTIVE.
        InvocationGuardRail.recordServiceConnected(app)
        tile.updateTileState()
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_service_turned_off), tile.qsTile.subtitle)

        // Connected IDLE -> INACTIVE, no subtitle.
        val a11y = attachLiveAccessibilityService()
        tile.updateTileState()
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        assertNull(tile.qsTile.subtitle)

        // RECORDING -> ACTIVE, recording subtitle.
        a11y.runtime.onTap()
        tile.updateTileState()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_recording), tile.qsTile.subtitle)

        // TRANSCRIBING -> ACTIVE, transcribing cancel subtitle.
        a11y.runtime.onTap()
        tile.updateTileState()
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        assertEquals(app.getString(R.string.tile_subtitle_transcribing), tile.qsTile.subtitle)
    }
}
