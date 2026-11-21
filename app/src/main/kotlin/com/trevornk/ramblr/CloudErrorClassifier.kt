package com.trevornk.ramblr

import java.io.IOException

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
        if (isTransientIo) return CloudErrorKind.TEMPORARY_BUSY
        if (errorText != null) {
            val lower = errorText.lowercase()
            if ("overloaded" in lower || "high demand" in lower) return CloudErrorKind.TEMPORARY_BUSY
            if (
                httpCode == null && (
                    "timed out" in lower ||
                        "timeout" in lower ||
                        "connection reset" in lower ||
                        "unexpected end of stream" in lower
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

    fun isTransientIoException(error: Throwable): Boolean = error is IOException
}
