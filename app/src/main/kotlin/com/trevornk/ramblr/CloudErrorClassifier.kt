package com.trevornk.ramblr

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

enum class CloudErrorKind {
    TEMPORARY_BUSY,
    PERMANENT_OR_QUOTA,
}

/**
 * Shared failure classifier across all cloud transcription and cleanup providers (OpenAI, Groq,
 * OpenRouter, Gemini, Anthropic, OmniRoute).
 */
object CloudErrorClassifier {
    val TEMPORARY_BUSY_HTTP_CODES: Set<Int> = setOf(408, 498, 500, 502, 503, 504, 529)
    val PERMANENT_OR_QUOTA_HTTP_CODES: Set<Int> = setOf(400, 401, 402, 403, 404, 413, 422, 429)

    const val QUICK_429_MAX_RETRY_AFTER_MS = 3_000L
    const val QUICK_429_DEFAULT_BACKOFF_MS = 1_500L

    /**
     * Parses a numeric HTTP `Retry-After` header value (seconds) into milliseconds.
     * Returns null when absent, blank, negative, or non-numeric (e.g. HTTP-date).
     */
    fun parseRetryAfterMs(headerValue: String?): Long? {
        val trimmed = headerValue?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val seconds = trimmed.toLongOrNull() ?: return null
        if (seconds < 0L) return null
        return seconds * 1_000L
    }

    /**
     * True when an HTTP 429 response is eligible for a single quick retry:
     * `retryAfterMs == null || retryAfterMs <= 3_000L`.
     */
    fun isQuickRetryable429(httpCode: Int?, retryAfterMs: Long? = null): Boolean =
        httpCode == 429 && (retryAfterMs == null || retryAfterMs <= QUICK_429_MAX_RETRY_AFTER_MS)

    /** Backoff in ms for a quick HTTP 429 retry (`retryAfterMs ?: 1_500L`). */
    fun quick429BackoffMs(retryAfterMs: Long?): Long =
        retryAfterMs ?: QUICK_429_DEFAULT_BACKOFF_MS

    fun classify(
        httpCode: Int? = null,
        errorText: String? = null,
        isTransientIo: Boolean = false,
    ): CloudErrorKind {
        if (httpCode != null) {
            if (httpCode in TEMPORARY_BUSY_HTTP_CODES) return CloudErrorKind.TEMPORARY_BUSY
            // Quota/auth/client codes fail over immediately even if the body mentions demand/load.
            if (httpCode in PERMANENT_OR_QUOTA_HTTP_CODES) return CloudErrorKind.PERMANENT_OR_QUOTA
        }
        val lowerError = errorText?.lowercase()
        if (
            httpCode == null && lowerError != null && (
                "unable to resolve host" in lowerError ||
                    "no address associated with hostname" in lowerError ||
                    "network is unreachable" in lowerError
                )
        ) {
            return CloudErrorKind.PERMANENT_OR_QUOTA
        }
        if (isTransientIo) return CloudErrorKind.TEMPORARY_BUSY
        if (lowerError != null) {
            if ("overloaded" in lowerError || "high demand" in lowerError) return CloudErrorKind.TEMPORARY_BUSY
            if (
                httpCode == null && (
                    "timed out" in lowerError ||
                        "timeout" in lowerError ||
                        "connection reset" in lowerError ||
                        "unexpected end of stream" in lowerError
                    )
            ) {
                return CloudErrorKind.TEMPORARY_BUSY
            }
        }
        return CloudErrorKind.PERMANENT_OR_QUOTA
    }

    fun classify(error: Throwable): CloudErrorKind =
        classify(httpCode = null, errorText = error.message, isTransientIo = isTransientIoException(error))

    fun isTemporaryBusy(
        httpCode: Int? = null,
        errorText: String? = null,
        isTransientIo: Boolean = false,
    ): Boolean = classify(httpCode, errorText, isTransientIo) == CloudErrorKind.TEMPORARY_BUSY

    fun isTemporaryBusy(error: Throwable): Boolean = classify(error) == CloudErrorKind.TEMPORARY_BUSY

    fun isNonTransientNetworkException(error: Throwable): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 16 && visited.add(current)) {
            if (current is UnknownHostException || current is NoRouteToHostException) return true
            current = current.cause
            depth++
        }
        return false
    }

    fun isTransientIoException(error: Throwable): Boolean =
        error is IOException && !isNonTransientNetworkException(error)
}
