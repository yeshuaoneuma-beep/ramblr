package com.trevornk.ramblr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Quick Settings tile (#127, Phase 2C): an alternative dictation trigger for users who want to hide
 * the floating ring entirely (swipe down, tap, instead of a screen-obstructing overlay). This is
 * purely a trigger surface -- it never touches the recording state machine directly, only
 * [WhisperAccessibilityService.requestToggleRecording] (start-if-idle / stop-if-recording, the
 * same toggle semantics as the ring's own [WhisperAccessibilityService.onTap]).
 *
 * Tapping while unlocked (or after [unlockAndRun] when tapped over a lock screen) routes cleanly
 * via [resolveQsTileClickAction] and collapses the Quick Settings shade via
 * [startActivityAndCollapseCompat] (API 34+ `PendingIntent` overload, API 30-33 `Intent` overload)
 * so the user immediately sees the underlying app and floating recording bubble.
 */
open class RamblrQsTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    /** While the QS panel is open, [onStartListening]/[onStopListening] bracket its visible
     *  lifetime -- polling on a short interval here is how the tile picks up a recording that
     *  finishes on its own (silence auto-stop (#108), watchdog timeout, max-duration auto-stop)
     *  while the panel is still showing, without WhisperAccessibilityService needing to know
     *  this tile exists at all. Never runs while the panel is closed. */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateTileState()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onStopListening() {
        handler.removeCallbacks(refreshRunnable)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleTileClick() }
        } else {
            handleTileClick()
        }
    }

    internal fun handleTileClick() {
        val active = ActiveDictationHostRegistry.resolveActiveHost()
        when (resolveQsTileClickAction(active?.second)) {
            QsTileClickAction.OPEN_ACCESSIBILITY_SETTINGS -> {
                // Accessibility service isn't enabled -- deep-link to system Accessibility settings
                // and collapse the shade via the SDK-appropriate overload.
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                collapseAndStart(intent, REQUEST_ACCESSIBILITY_SETTINGS)
            }
            QsTileClickAction.TOGGLE_DICTATION_AND_COLLAPSE -> {
                active?.first?.requestToggleRecording()
                // Toggle takes effect on the host's handler asynchronously (RECORDING/TRANSCRIBING
                // engine start has real I/O in it), so reflect the immediately-known intent right away
                // and let the next onStartListening/refresh tick reconcile with the real state.
                handler.postDelayed({ updateTileState() }, TOGGLE_REFLECT_DELAY_MS)
                collapseAndStart(QsTileCollapseActivity.intent(this), REQUEST_COLLAPSE_SHADE)
            }
            QsTileClickAction.CANCEL_TRANSCRIPTION_AND_COLLAPSE -> {
                active?.first?.requestCancelActive()
                handler.postDelayed({ updateTileState() }, TOGGLE_REFLECT_DELAY_MS)
                collapseAndStart(QsTileCollapseActivity.intent(this), REQUEST_COLLAPSE_SHADE)
            }
        }
    }

    internal open fun collapseAndStart(intent: Intent, requestCode: Int) {
        startActivityAndCollapseCompat(this, intent, requestCode)
    }

    internal fun updateTileState() {
        val tile = qsTile ?: return
        val state = ActiveDictationHostRegistry.currentState()
        val display = resolveQsTileDisplayState(
            state = state,
            serviceTurnedOff = state == null &&
                InvocationGuardRail.staleComponentAction(this) != StaleComponentAction.NONE,
        )
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_mic)
        tile.label = getString(R.string.tile_label)
        tile.state = display.tileState
        tile.subtitle = display.subtitleRes?.let { getString(it) }
        tile.updateTile()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val TOGGLE_REFLECT_DELAY_MS = 150L
        internal const val REQUEST_ACCESSIBILITY_SETTINGS = 0
        internal const val REQUEST_COLLAPSE_SHADE = 1
    }
}

/**
 * No-UI translucent trampoline Activity used solely by [RamblrQsTileService] to collapse the Quick
 * Settings shade via `TileService.startActivityAndCollapse` across API 30-36 without touching
 * [IconHiddenState] or leaving a Recents entry.
 */
class QsTileCollapseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, QsTileCollapseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
