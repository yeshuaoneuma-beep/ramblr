package com.trevornk.ramblr

/**
 * Builds the floating-bubble notice shown when cleanup fails and Ramblr injects the raw
 * transcript instead (#175).
 *
 * The bubble itself already existed -- `injectText(feedback = "Cleanup failed (...)")` has been
 * wired since #98 -- so #175's premise that the fall-through is *silent* is wrong. What was
 * actually broken is what the bubble said:
 *
 *  1. **It claimed the clipboard held the raw text when usually it didn't.** The message was
 *     hardcoded as "raw copied to clipboard" at the call site, which runs *before* the injection
 *     method is known. For a DIRECT (ACTION_SET_TEXT) injection -- the common case --
 *     [clipboardClearActionFor] wipes the clipboard immediately, so the user was told to paste
 *     from a clipboard that no longer had it. This is exactly the #118 bug class, which fixed the
 *     same false clipboard claim for the *success* message and left the failure message behind.
 *
 *  2. **It leaked raw executor diagnostics into a floating overlay.** The reason string was
 *     `result.error` verbatim, which is built for logcat: nested prefixes like
 *     `"All cleanup steps failed: Local cleanup output rejected (PROMPT_ECHO)"`, or -- for a cloud
 *     step -- up to 200 characters of the provider's own error body via
 *     `CleanupWaterfallExecutor.errorDetail`. Measured, a local PROMPT_ECHO produced a 112-char
 *     bubble; a verbose provider envelope would be far longer, in a WRAP_CONTENT pill with no
 *     `maxLines`, anchored beside the icon.
 *
 * So this resolves the message at the injection seam, where `method` is known, and maps the
 * internal reason onto a short plain-language phrase.
 *
 * The phrasing deliberately describes *what the model did* ("echoed its instructions") rather than
 * naming the validator constant (`PROMPT_ECHO`). #175 asks whether the reason should be visible at
 * all; it should, because these failures are currently indistinguishable from cleanup that ran and
 * changed nothing -- but the internal enum name is for the issue tracker, not the user.
 *
 * Content safety: every branch returns a fixed literal. No transcript, prompt, model output, or
 * provider body ever reaches the bubble -- the same boundary [LocalCleanupOutputValidator] holds
 * when it logs reason/detail but never the text.
 */
object CleanupFailureNotice {

    /** Fallback when the executor gave no usable error at all. */
    const val UNKNOWN_REASON = "unknown error"

    /**
     * Maps a raw [CleanupWaterfallExecutor] error string onto a short user-facing phrase.
     *
     * Matched against the *whole* string rather than an exact equality, because the executor
     * wraps the terminal step's message in an `"All cleanup steps failed: "` prefix (and cloud
     * steps prepend `"HTTP <code>: "`), so the distinguishing token appears mid-string.
     */
    fun summarize(error: String?): String {
        val raw = error?.trim().orEmpty()
        if (raw.isEmpty()) return UNKNOWN_REASON
        return when {
            // Validator rejections (#155/#181). Described by effect, not by enum name.
            raw.contains("PROMPT_ECHO") -> "model repeated its instructions"
            raw.contains("LENGTH_COLLAPSE") -> "model dropped most of the text"
            raw.contains("LENGTH_EXPANSION") -> "model added text you didn't say"
            raw.contains("NUMERIC_DIVERGENCE") -> "model changed a number"
            raw.contains("QUESTION_ANSWERED") -> "model answered instead of cleaning up"
            // Cloud fidelity guard (CloudCleanupFidelityGuard).
            raw.contains("META_REPLY") -> "model replied instead of cleaning up"
            raw.contains("ENTITY_LOST") -> "model changed a name"
            raw.contains("WORDS_INSERTED") -> "model added words you didn't say"
            // Local & pipeline engine outcomes.
            // Model-missing carries the full `/data/user/0/...` path in one branch, so keep this a fixed literal.
            raw.contains("model not found", ignoreCase = true) ||
                raw.contains("model not downloaded", ignoreCase = true) ->
                "cleanup model isn't installed"
            raw.contains("No credential configured", ignoreCase = true) ->
                "no API key configured"
            raw.contains("exceeded time budget", ignoreCase = true) ||
                raw.contains("wall-clock deadline", ignoreCase = true) ||
                raw.contains("inference budget exceeded", ignoreCase = true) ->
                "cleanup took too long"
            raw.contains("cannot fit chunk", ignoreCase = true) ||
                raw.contains("context size reached", ignoreCase = true) ->
                "text too long for model"
            raw.contains("max response length", ignoreCase = true) ->
                "response hit length limit"
            raw.contains("repeating loop", ignoreCase = true) ->
                "model got stuck repeating"
            raw.contains("empty response", ignoreCase = true) ||
                raw.contains("No candidates", ignoreCase = true) ||
                raw.contains("No choices", ignoreCase = true) ||
                raw.contains("No text content", ignoreCase = true) ->
                "model returned nothing"
            // HTTP status code before generic timeout/connection phrases so HTTP 400/503/504 bodies
            // mentioning "timeout" or "network" still report their actionable status code.
            httpStatusIn(raw) != null -> "server error ${httpStatusIn(raw)}"
            raw.contains("timed out", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "model timed out"
            isConnectionError(raw) -> "connection error"
            else -> UNKNOWN_REASON
        }
    }

    private fun isConnectionError(raw: String): Boolean {
        val lower = raw.lowercase()
        return lower.contains("connection failed") ||
            lower.contains("unable to resolve host") ||
            lower.contains("unknownhostexception") ||
            lower.contains("failed to connect") ||
            lower.contains("connectexception") ||
            lower.contains("socketexception") ||
            lower.contains("sockettimeoutexception") ||
            lower.contains("eofexception") ||
            lower.contains("ssl") ||
            lower.contains("tls") ||
            lower.contains("handshake") ||
            lower.contains("certificate") ||
            lower.contains("dns") ||
            lower.contains("network")
    }

    /** Extracts the `NNN` from an `"HTTP NNN: ..."` step failure, if this was one. */
    private fun httpStatusIn(raw: String): String? =
        Regex("HTTP (\\d{3})").find(raw)?.groupValues?.get(1)

    /**
     * The full bubble text for a failed cleanup that fell through to raw text.
     *
     * [method] decides the clipboard clause, and it must, because it decides the clipboard's
     * actual contents:
     *  - [InjectMethod.DIRECT] -- clipboard wiped immediately after injection, so promising a
     *    copy would be a lie.
     *  - [InjectMethod.FROM_CLIPBOARD] -- the copy is cleared after a short grace delay, so it is
     *    not something to send the user to either.
     *  - [InjectMethod.NONE] -- nothing was injected and the clipboard *is* the delivery path, so
     *    it is the one case worth mentioning. The injection seam already appends its own
     *    "· tap to copy again" affordance for this case, so this stays terse to avoid saying it
     *    twice.
     */
    fun messageFor(method: InjectMethod, error: String?, translating: Boolean = false): String {
        val reason = summarize(error)
        // A translating dictation that failed names the translation, not "cleanup": the user asked
        // for another language and got the spoken one.
        val what = if (translating) "Translation" else "Cleanup"
        val inserted = if (translating) "inserted untranslated text" else "inserted raw text"
        return when (method) {
            InjectMethod.NONE -> "$what failed ($reason)"
            else -> "$what failed ($reason) — $inserted"
        }
    }
}
