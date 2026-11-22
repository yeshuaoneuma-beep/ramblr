package com.trevornk.ramblr

/** Which in-process dictation host owns the active or default dictation session. */
enum class DictationHostKind { ACCESSIBILITY, IME }

/** Minimal host contract exposed to [ActiveDictationHostRegistry] for Quick Settings routing. */
internal interface DictationHostHandle {
    val kind: DictationHostKind
    fun currentState(): RecordingStateMachine.State
    fun canStartFromIdle(): Boolean
    fun requestToggleRecording(): Boolean
    fun requestCancelActive(): Boolean
}

/**
 * Lightweight process-wide registry connecting both [WhisperAccessibilityService] and
 * [RamblrImeService] to [RamblrQsTileService] so Quick Settings reflects and routes active
 * RECORDING / TRANSCRIBING sessions regardless of which host started them.
 */
internal object ActiveDictationHostRegistry {
    private val hosts = LinkedHashMap<DictationHostKind, DictationHostHandle>()

    @Synchronized
    fun register(handle: DictationHostHandle) {
        hosts[handle.kind] = handle
    }

    @Synchronized
    fun unregister(handle: DictationHostHandle) {
        if (hosts[handle.kind] === handle) {
            hosts.remove(handle.kind)
        }
    }

    @Synchronized
    fun resetForTest() {
        hosts.clear()
    }

    fun resolveActiveHost(): Pair<DictationHostHandle, RecordingStateMachine.State>? {
        val (a11y, ime) = synchronized(this) {
            (hosts[DictationHostKind.ACCESSIBILITY] ?: WhisperAccessibilityService.activeHostHandleOrNull()) to
                hosts[DictationHostKind.IME]
        }

        val imeState = ime?.currentState()
        if (ime != null && imeState != null && imeState != RecordingStateMachine.State.IDLE) {
            return ime to imeState
        }

        val a11yState = a11y?.currentState()
        if (a11y != null && a11yState != null && a11yState != RecordingStateMachine.State.IDLE) {
            return a11y to a11yState
        }

        if (a11y != null && a11y.canStartFromIdle()) {
            return a11y to RecordingStateMachine.State.IDLE
        }
        if (ime != null && ime.canStartFromIdle()) {
            return ime to RecordingStateMachine.State.IDLE
        }
        return null
    }

    fun currentState(): RecordingStateMachine.State? = resolveActiveHost()?.second

    fun requestToggleRecording(): Boolean = resolveActiveHost()?.first?.requestToggleRecording() == true

    fun requestCancelActive(): Boolean = resolveActiveHost()?.first?.requestCancelActive() == true
}
