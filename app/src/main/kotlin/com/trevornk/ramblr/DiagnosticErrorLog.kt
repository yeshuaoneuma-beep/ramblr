package com.trevornk.ramblr

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Scrubbed, capped ring buffer of recent pipeline / API failure lines stored in app-private
 * `files/diagnostic_errors.log`, inspectable and shareable under Settings > Diagnostics (#Phase2D).
 *
 * Strictly stores error metadata and provider error envelopes only (never user transcript text),
 * with URLs, auth headers, and API keys scrubbed before writing.
 */
object DiagnosticErrorLog {
    private const val TAG = "DiagnosticErrorLog"
    private const val FILE_NAME = "diagnostic_errors.log"
    internal const val MAX_ENTRIES = 100
    internal const val MAX_DETAIL_CHARS = 500

    private val lock = Any()

    private val BEARER_REGEX = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val TOKEN_KEY_REGEX = Regex(
        "\\b(?:sk-ant-[A-Za-z0-9_-]{8,}|sk-(?:proj-)?[A-Za-z0-9_-]{12,}|AIza[0-9A-Za-z_-]{16,}|gsk_[A-Za-z0-9_-]{12,}|xai-[A-Za-z0-9_-]{12,})\\b"
    )
    private val HEADER_OR_PARAM_SECRET_REGEX = Regex(
        "(?i)((?:api[_-]?key|x-api-key|x-goog-api-key|authorization|access_token|secret)\\s*[:=]\\s*[\"']?)[^\\s\"',;\\}\\]&]+"
    )
    private val WHITESPACE_RUN = Regex("\\s+")

    fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Redacts URL query credentials, bearer tokens, API keys, and collapses multiline noise. */
    fun scrub(raw: String?): String {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return ""
        var scrubbed = UrlRedaction.redact(input) ?: input
        scrubbed = BEARER_REGEX.replace(scrubbed, "Bearer [redacted]")
        scrubbed = TOKEN_KEY_REGEX.replace(scrubbed, "[redacted]")
        scrubbed = HEADER_OR_PARAM_SECRET_REGEX.replace(scrubbed, "$1[redacted]")
        return scrubbed.replace(WHITESPACE_RUN, " ").trim().take(MAX_DETAIL_CHARS)
    }

    fun record(
        context: Context,
        stage: String,
        provider: String? = null,
        model: String? = null,
        httpCode: Int? = null,
        detail: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val scrubbed = scrub(detail)
        if (scrubbed.isEmpty()) return
        val formatted = formatEntry(
            stage = stage,
            provider = provider,
            model = model,
            httpCode = httpCode,
            scrubbedDetail = scrubbed,
            nowMs = nowMs,
        )
        runCatching {
            recordToFile(logFile(context.applicationContext ?: context), formatted)
        }.onFailure {
            runCatching { Log.w(TAG, "Failed to write diagnostic error log", it) }
        }
    }

    fun readRecent(context: Context): List<String> = readFromFile(logFile(context.applicationContext ?: context))

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { logFile(context.applicationContext ?: context).delete() }
        }
    }

    internal fun formatEntry(
        stage: String,
        provider: String? = null,
        model: String? = null,
        httpCode: Int? = null,
        scrubbedDetail: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))
        val target = listOfNotNull(
            provider?.takeIf { it.isNotBlank() },
            model?.takeIf { it.isNotBlank() },
        ).joinToString("/")
        val httpTag = httpCode?.let { " HTTP $it" }.orEmpty()
        val scope = if (target.isNotEmpty()) "$stage $target$httpTag" else "$stage$httpTag"
        return "$timestamp [$scope] $scrubbedDetail"
    }

    internal fun recordToFile(file: File, line: String, maxEntries: Int = MAX_ENTRIES) {
        val cleanLine = line.replace(WHITESPACE_RUN, " ").trim()
        if (cleanLine.isEmpty()) return
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val existing = if (file.exists()) {
                file.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val updated = (existing + cleanLine).takeLast(maxEntries.coerceAtLeast(1))
            file.writeText(updated.joinToString("\n") + "\n")
        }
    }

    internal fun readFromFile(file: File): List<String> = synchronized(lock) {
        if (!file.exists()) emptyList()
        else runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }
}
