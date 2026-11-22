package com.trevornk.ramblr

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.StringRes

/**
 * Which `TileService.startActivityAndCollapse` overload is safe to call on [sdkInt].
 *
 * Both overloads are traps, in opposite directions, and the safe window for each is disjoint:
 *
 *  - `startActivityAndCollapse(Intent)` exists from API 24 but was deprecated in API 34, where it
 *    throws [UnsupportedOperationException] *unconditionally*. Safe only below 34.
 *  - `startActivityAndCollapse(PendingIntent)` was *added* in API 34. Calling it below that throws
 *    [NoSuchMethodError]. Safe only from 34 up.
 *
 * With `minSdk 30` the app spans both regimes, so the branch is mandatory. #136 fixed the first
 * trap by switching wholesale to the PendingIntent overload, which silently introduced the second:
 * the quick-settings "tap to enable" affordance then crashed on Android 11-13 instead of Android
 * 14+. Lint only surfaced it once the project moved to a toolchain that could see the API-34
 * floor (`NewApi`, reported against the unguarded call).
 *
 * Extracted as a pure function of the SDK int -- rather than reading [Build.VERSION.SDK_INT]
 * inline -- purely so the boundary is unit-testable on the JVM without Robolectric, matching
 * [shouldRestoreIconBeforeToggle]'s approach in this same feature.
 *
 * [ChecksSdkIntAtLeast] is what keeps that extraction free: lint's `NewApi` check only recognises
 * an *inline* `SDK_INT` comparison as a guard, so without this annotation it cannot see through
 * the helper and re-reports the API-34 call as unguarded. The annotation tells lint that a `true`
 * return means "SDK_INT >= 34", restoring the analysis at the call site.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun shouldUsePendingIntentCollapse(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** What tapping the Quick Settings tile should do given the active host's current state. */
enum class QsTileClickAction {
    /** No active dictation host is available: deep-link to Accessibility Settings and collapse shade. */
    OPEN_ACCESSIBILITY_SETTINGS,
    /** Active host is IDLE or RECORDING: toggle dictation (restoring hidden bubble on start) and collapse shade. */
    TOGGLE_DICTATION_AND_COLLAPSE,
    /** Active host is TRANSCRIBING: cancel in-flight transcription and collapse shade. */
    CANCEL_TRANSCRIPTION_AND_COLLAPSE,
}

/** Pure routing decision for [RamblrQsTileService.onClick]. */
fun resolveQsTileClickAction(state: RecordingStateMachine.State?): QsTileClickAction = when (state) {
    null -> QsTileClickAction.OPEN_ACCESSIBILITY_SETTINGS
    RecordingStateMachine.State.IDLE,
    RecordingStateMachine.State.RECORDING -> QsTileClickAction.TOGGLE_DICTATION_AND_COLLAPSE
    RecordingStateMachine.State.TRANSCRIBING -> QsTileClickAction.CANCEL_TRANSCRIPTION_AND_COLLAPSE
}

/** Pure tile presentation state derived from the active host's current recording state. */
data class QsTileDisplayState(
    val tileState: Int,
    @StringRes val subtitleRes: Int?,
)

fun resolveQsTileDisplayState(
    state: RecordingStateMachine.State?,
    serviceTurnedOff: Boolean,
): QsTileDisplayState = when (state) {
    null -> QsTileDisplayState(
        tileState = Tile.STATE_INACTIVE,
        subtitleRes = if (serviceTurnedOff) R.string.tile_subtitle_service_turned_off
        else R.string.tile_subtitle_service_disabled,
    )
    RecordingStateMachine.State.RECORDING -> QsTileDisplayState(
        tileState = Tile.STATE_ACTIVE,
        subtitleRes = R.string.tile_subtitle_recording,
    )
    RecordingStateMachine.State.TRANSCRIBING -> QsTileDisplayState(
        tileState = Tile.STATE_ACTIVE,
        subtitleRes = R.string.tile_subtitle_transcribing,
    )
    RecordingStateMachine.State.IDLE -> QsTileDisplayState(
        tileState = Tile.STATE_INACTIVE,
        subtitleRes = null,
    )
}

/** Launches [intent] while collapsing the Quick Settings shade cleanly across API 30-36. */
fun startActivityAndCollapseCompat(
    service: TileService,
    intent: Intent,
    requestCode: Int = 0,
) {
    if (shouldUsePendingIntentCollapse(Build.VERSION.SDK_INT)) {
        val pending = PendingIntent.getActivity(
            service,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        service.startActivityAndCollapse(pending)
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        service.startActivityAndCollapse(intent)
    }
}
