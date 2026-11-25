package com.trevornk.ramblr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Multimodal audio-in client for Gemini transcription (#96): sends the recorded PCM as an
 * inline base64 `audio/wav` blob inside a `generateContent` call, prompting the model to
 * transcribe it, rather than a dedicated ASR endpoint (Gemini has none) -- see [ProviderChain]'s
 * kdoc for why this is the documented capability shape. Mirrors [TranscriberClient]'s Result/
 * callback contract so [WhisperAccessibilityService.transcribeApi] can treat a Gemini candidate
 * exactly like an OpenAI one.
 *
 * No streaming (per capability facts) and no file-upload API: `inline_data` keeps this a single
 * request/response round trip instead of an upload-then-generate two-step flow, which is
 * adequate for the short dictations this app records (see [NetworkClients] timeouts).
 */
object GeminiTranscriberClient {
    data class Result(
        val text: String?,
        val error: String?,
        val httpCode: Int? = null,
        val isTransientIo: Boolean = false,
        val isCancelled: Boolean = false,
        val retryAfterMs: Long? = null,
        val dropReason: String? = null,
    ) {
        val errorKind: CloudErrorKind
            get() = CloudErrorClassifier.classify(httpCode, error, isTransientIo)
    }

    private val client = NetworkClients.shared

    private fun clientFor(callTimeoutMs: Long?): OkHttpClient {
        if (callTimeoutMs == null) return client
        val boundedMs = callTimeoutMs.coerceAtLeast(MIN_STEP_CALL_BUDGET_MS)
        return client.newBuilder()
            .connectTimeout(minOf(NetworkClients.CONNECT_TIMEOUT_SECONDS * 1000L, boundedMs), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(NetworkClients.READ_TIMEOUT_SECONDS * 1000L, boundedMs), TimeUnit.MILLISECONDS)
            .writeTimeout(minOf(NetworkClients.WRITE_TIMEOUT_SECONDS * 1000L, boundedMs), TimeUnit.MILLISECONDS)
            .callTimeout(boundedMs, TimeUnit.MILLISECONDS)
            .build()
    }

    // gemini-2.5-flash -> gemini-3.1-flash-lite: 2.5-flash is on Google's deprecation path
    // (shutdown Oct 16, 2026). 3.1-flash-lite's own model card documents explicit "improved
    // audio input... for ASR" gains and lists transcription as a first-class use case, so it's
    // safe for this multimodal audio-in path too (2026-07-10). DELIBERATELY NOT bumped to
    // gemini-3.5-flash-lite alongside the 2026-08-25 cleanup-default refresh: 3.5-flash-lite
    // measures ~3x slower on audio input, so the transcription path keeps the faster model.
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
    const val DEDICATED_TRANSCRIBE_MODEL = "gemini-3.5-transcribe"

    fun isDedicatedTranscribeModel(model: String?): Boolean {
        val normalized = model?.trim()?.removePrefix("models/")?.lowercase().orEmpty()
        return normalized == DEDICATED_TRANSCRIBE_MODEL || normalized.startsWith("$DEDICATED_TRANSCRIBE_MODEL-")
    }

    private val AUDIO_CAPABLE_GEMMA4_VARIANT = Regex("(^|[-_/])(e2b|e4b|12b)($|[-_/])")

    /**
     * Whether [model] supports multimodal audio input for Stage 1 STT.
     *
     * Text/image-only Gemma 4 models (`gemma-4-31b-it`, `gemma-4-26b-a4b-it`, and any `gemma-4-*`
     * other than `e2b`/`e4b`/`12b`) are cleanup-only and must be excluded from audio STT dispatch.
     */
    fun supportsAudioTranscription(model: String?): Boolean {
        val effective = model?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL
        if (!GeminiCleanupProvider.isGemma4(effective)) return true
        val normalized = effective.removePrefix("models/").lowercase()
        return AUDIO_CAPABLE_GEMMA4_VARIANT.containsMatchIn(normalized)
    }
    // "If the audio contains no speech, return an empty transcript" (#306): without an explicit
    // empty answer, a model given silence fills the void with its prompt context.
    const val TRANSCRIBE_PROMPT = "Transcribe this audio exactly as spoken. Return only the transcript text, with no commentary, labels, or extra formatting. If the audio contains no speech, return an empty transcript."

    /**
     * Interpolates the user's personal vocabulary into [TRANSCRIBE_PROMPT] (#114 part 2): the
     * same terms already biasing cleanup-stage prompts (see [PostProcessor.vocabularyClause])
     * appended as a short clause so Gemini's transcription decoding is nudged toward them too.
     * Always on when [terms] is non-empty; [TRANSCRIBE_PROMPT] is returned unchanged otherwise.
     */
    fun transcribePrompt(terms: List<String>, language: String? = null): String {
        val base = if (language.isNullOrBlank()) TRANSCRIBE_PROMPT else "$TRANSCRIBE_PROMPT ${languageClause(language)}"
        return if (terms.isEmpty()) base
        // #306: framed as spelling hints that are NOT part of the audio. The earlier "watch for
        // these terms" wording read as content to produce, and on near-silent audio the model
        // returned the whole list as the transcript.
        else "$base These are spelling hints only and are not part of the audio: if you hear one of these names or terms spoken, write it as spelled here. Never output a hint unless it was actually spoken, and never output the list itself: ${terms.joinToString(", ")}."
    }

    /**
     * A parsed batch response with the prompt-echo guard applied (#306): a transcript that is
     * just the vocabulary list or the prompt text coming back becomes an empty transcript, which
     * callers treat as "no speech".
     */
    fun resultFor(responseBody: String, terms: List<String>, prompt: String, model: String = DEFAULT_MODEL): Result {
        val parsed = parseResponse(responseBody, model)
        return if (VocabularyEchoGuard.isEcho(parsed.text, terms, prompt)) {
            parsed.copy(text = "", error = null, dropReason = "vocabulary_echo_guard")
        } else {
            parsed
        }
    }

    /**
     * The spoken-language hint (#290). Gemini has no language field on generateContent, so the
     * language rides in the prompt. Measured 2026-10-05: on a German clip that Gemini 3.1
     * Flash-Lite rendered as English-phonetic text on 9 of 9 runs, with or without a "never
     * translate" instruction, adding this clause got German back on 3 of 3 runs.
     */
    fun languageClause(language: String): String =
        "The speaker is speaking ${DictationLanguage.englishName(language)} ($language). " +
            "Write the transcript in ${DictationLanguage.englishName(language)}; never translate it."

    /** Max PCM file size this inline-audio path will accept (M6): base64 + JSON string + request
     *  body copy buffers the recording ~4x in memory (a max-length 19.2MB PCM ≈ ~100MB transient),
     *  stacked on the resident STT/cleanup models -- a plausible OOM exactly when memory is tight.
     *  ~10MB PCM (~5 min at 16kHz mono 16-bit) bounds the transient to a safer band; above it the
     *  caller falls through to the next transcription candidate rather than risk the OOM. */
    const val MAX_INLINE_PCM_BYTES = 10L * 1024 * 1024

    /** True when the audio payload to be inlined ([compressedBytes] if present, otherwise [pcmBytes])
     *  is within [MAX_INLINE_PCM_BYTES]. */
    fun canInlineAudio(pcmBytes: Long, compressedBytes: Long? = null): Boolean =
        (compressedBytes ?: pcmBytes) <= MAX_INLINE_PCM_BYTES

    fun canInlineAudio(pcmFile: File, compressedFile: File?): Boolean =
        canInlineAudio(pcmBytes = pcmFile.length(), compressedBytes = compressedFile?.length())

    /**
     * Waits before each retry of a "busy, try again" answer. Seen 2026-10 as a burst of 503
     * UNAVAILABLE "This model is currently experiencing high demand..." on gemini-3.1-flash-lite:
     * without a retry each one lost the dictation, or fell through to an on-device model that a
     * fresh install doesn't have yet. Two short retries (~6s added worst case) ride out a brief
     * spike; a longer outage still fails fast enough for the user to switch models.
     */
    val OVERLOAD_RETRY_DELAYS_MS: List<Long> = listOf(1_500L, 4_000L)

    /** Daemon so a pending retry never keeps the process alive. */
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gemini-overload-retry").apply { isDaemon = true }
    }

    /**
     * True for a server-side "temporarily unable" answer worth retrying unchanged, delegated to
     * [CloudErrorClassifier] so 429/401/402/403/400 never trigger same-provider retries.
     */
    fun isOverloaded(httpCode: Int, error: String?): Boolean =
        CloudErrorClassifier.isTemporaryBusy(httpCode = httpCode, errorText = error)

    /** The error shown once every retry hit the overload, naming the model so the user knows
     *  what to switch away from. */
    fun overloadedMessage(model: String, attempts: Int): String =
        "Gemini model $model is overloaded right now (tried $attempts times). Try again in a minute or pick another transcription model."

    /**
     * Builds the generateContent request body with the audio bytes inlined as base64
     * `inline_data` (see https://ai.google.dev/gemini-api/docs/audio#inline-audio). [audioBytes]
     * must already be in the container/encoding described by [mimeType] -- callers building from
     * a raw PCM file should use [WavWriter] first for the WAV path, same as [PcmWavRequestBody]
     * does for [TranscriberClient]. [mimeType] defaults to `audio/wav`; #109's compressed-upload
     * path passes `audio/aac` for a finished `.m4a` file instead (AAC is one of Gemini's
     * documented supported inline audio MIME types).
     */
    fun buildRequestBody(
        audioBytes: ByteArray,
        prompt: String = TRANSCRIBE_PROMPT,
        mimeType: String = "audio/wav",
        model: String = DEFAULT_MODEL,
        languageCodes: List<String> = emptyList(),
        customVocabulary: List<String> = emptyList(),
    ): JSONObject {
        val effectiveModel = model.ifBlank { DEFAULT_MODEL }
        val audioPart = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("data", audioBytes.toByteString().base64())
            })
        }
        if (isDedicatedTranscribeModel(effectiveModel)) {
            val contents = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(audioPart))
            })
            val cleanLanguages = languageCodes.filter { it.isNotBlank() }
            val cleanVocabulary = customVocabulary.filter { it.isNotBlank() }.take(1000)
            return JSONObject().apply {
                put("contents", contents)
                if (cleanLanguages.isNotEmpty() || cleanVocabulary.isNotEmpty()) {
                    val audioConfig = JSONObject().apply {
                        if (cleanLanguages.isNotEmpty()) put("languageCodes", JSONArray(cleanLanguages))
                        if (cleanVocabulary.isNotEmpty()) put("customVocabulary", JSONArray(cleanVocabulary))
                    }
                    put("generationConfig", JSONObject().put("audioTranscriptionConfig", audioConfig))
                }
            }
        }
        val textPart = JSONObject().apply { put("text", prompt) }
        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(textPart).put(audioPart))
        })
        return JSONObject().apply {
            put("contents", contents)
            GeminiCleanupProvider.thinkingLevelFor(effectiveModel)?.let { level ->
                put(
                    "generationConfig",
                    JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", level)),
                )
            }
        }
    }

    private val REJECTED_FINISH_REASONS = setOf("MAX_TOKENS", "SAFETY", "RECITATION")

    /** Same `candidates[0].content.parts[].text` shape as [GeminiCleanupProvider.parseResponse],
     *  extracted independently so the two providers' response contracts (transcript text vs.
     *  cleaned-up text) stay decoupled even though the wire format happens to match today. */
    fun parseResponse(json: String, model: String = DEFAULT_MODEL): Result = try {
        val obj = JSONObject(json)
        if (obj.has("candidates")) {
            val candidates = obj.getJSONArray("candidates")
            if (candidates.length() == 0) {
                Result(null, "No candidates in response")
            } else {
                val candidate = candidates.getJSONObject(0)
                val finishReason = if (candidate.isNull("finishReason")) "" else candidate.optString("finishReason", "").trim()
                if (finishReason in REJECTED_FINISH_REASONS) {
                    return Result(null, "Gemini finished with $finishReason")
                }
                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                var thoughtCount = 0
                val audioTranscriptionText = StringBuilder()
                val plainText = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        if (part.optBoolean("thought", false)) {
                            thoughtCount++
                            continue
                        }
                        part.optJSONObject("audioTranscription")
                            ?.takeIf { !it.isNull("text") }
                            ?.optString("text", "")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { audioTranscriptionText.append(it) }
                        if (!part.isNull("text")) {
                            plainText.append(part.optString("text", ""))
                        }
                    }
                }
                val text = audioTranscriptionText.toString().ifBlank { plainText.toString() }
                if (text.isNotBlank()) Result(text.trim(), null)
                else if (finishReason == "STOP" && isDedicatedTranscribeModel(model)) {
                    Result(null, "Gemini $model returned empty STOP transcription", dropReason = "empty_stop(thoughtParts=$thoughtCount)")
                }
                // A model that finished normally with no text is answering "there was no speech"
                // (the prompt asks for exactly that, #306): an empty transcript, not a provider
                // failure. Any other finish reason (SAFETY, MAX_TOKENS, ...) stays an error.
                else if (finishReason == "STOP") Result("", null, dropReason = "empty_stop(thoughtParts=$thoughtCount)")
                else Result(null, "No text content in response (finishReason=${finishReason.ifBlank { "unknown" }})")
            }
        } else if (obj.has("error")) {
            Result(null, obj.getJSONObject("error").optString("message", "Unknown Gemini error"))
        } else {
            Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /**
     * Streams [pcmFile] into a WAV byte array (44-byte header + PCM body, same convention as
     * [PcmWavRequestBody]) and sends it as inline base64 audio data. Unlike
     * [TranscriberClient.transcribe]'s multipart streaming upload, Gemini's inline-data JSON
     * body requires the full base64 payload assembled in memory -- acceptable for this app's
     * short dictation recordings, same tradeoff [NetworkClients]' generous timeouts already
     * budget for.
     *
     * When [compressedFile] is non-null (#109: compressed-upload toggle on and the AAC encode
     * succeeded), that finished `.m4a` is embedded instead as `audio/aac` -- read directly rather
     * than WAV-wrapped, since it's already a complete, playable audio container. Falls back to
     * the WAV path built from [pcmFile] exactly as before whenever it's null.
     *
     * An overloaded answer (see [isOverloaded]) is retried after each of [retryDelaysMs] before
     * it reports [overloadedMessage]; [endpoint] and [retryDelaysMs] are only overridden by tests
     * or centralized Stage 1 orchestration ([DictationRuntime], [AudioJobRunner]).
     */
    fun transcribe(
        pcmFile: File,
        apiKey: String,
        model: String = DEFAULT_MODEL,
        cancelHolder: InFlightCall,
        vocabularyTerms: List<String> = emptyList(),
        compressedFile: File? = null,
        language: String? = null,
        endpoint: String = GeminiCleanupProvider.endpointUrl(model),
        retryDelaysMs: List<Long> = OVERLOAD_RETRY_DELAYS_MS,
        callTimeoutMs: Long? = null,
        callback: (Result) -> Unit,
    ) {
        val effectiveModel = model.ifBlank { DEFAULT_MODEL }
        if (!supportsAudioTranscription(effectiveModel)) {
            callback(Result(null, "Model $effectiveModel does not support audio transcription"))
            return
        }
        val (audioBytes, mimeType) = try {
            if (compressedFile != null) {
                compressedFile.readBytes() to "audio/aac"
            } else {
                (WavWriter.header(pcmFile.length(), 16000, 1, 16) + pcmFile.readBytes()) to "audio/wav"
            }
        } catch (e: IOException) {
            callback(Result(null, e.message ?: "Failed to read audio file"))
            return
        }

        // #114 part 2: interpolate the user's vocabulary into the transcription prompt itself
        // (previously only cleanup-stage prompts got it), always on when terms exist.
        val prompt = transcribePrompt(vocabularyTerms, language)
        val languageCodes = listOfNotNull(DictationLanguage.geminiLiveCode(language))
        val cleanVocabulary = vocabularyTerms.filter { it.isNotBlank() }.take(1000)
        val body = buildRequestBody(
            audioBytes,
            prompt = prompt,
            mimeType = mimeType,
            model = effectiveModel,
            languageCodes = languageCodes,
            customVocabulary = cleanVocabulary,
        ).toString().toRequestBody("application/json".toMediaType())

        // A malformed key/model (or one OkHttp otherwise rejects) throws IllegalArgumentException
        // from Request.Builder().url() rather than failing the call -- caught so it reports
        // through the same Result/callback path as a network failure, instead of crashing (see
        // PostProcessor.process's identical handling of a bad custom base URL, #4). The key rides
        // in the x-goog-api-key header (M-3), not the URL, and the message is query-redacted for
        // defense in depth so nothing echoed from the URL can leak a secret.
        val request = try {
            Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey)
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid Gemini endpoint: ${UrlRedaction.redact(e.message)}"))
            return
        }

        val activeClient = clientFor(callTimeoutMs)
        val cancelsAtStart = cancelHolder.cancelCount
        fun send(attempt: Int) {
            val call = activeClient.newCall(request)
            cancelHolder.set(call)
            if (cancelHolder.isCancelled) call.cancel()
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cancelHolder.clear(call)
                    val cancelled = call.isCanceled() && cancelHolder.isCancelled
                    val error = if (!cancelled && call.isCanceled()) normalizedTimeoutError(e) else e.message
                    callback(
                        Result(
                            null,
                            error,
                            isTransientIo = !cancelled && CloudErrorClassifier.isTransientIoException(e),
                            isCancelled = cancelled,
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    cancelHolder.clear(call)
                    val httpCode = response.code
                    val successful = response.isSuccessful
                    val retryAfterMs = CloudErrorClassifier.parseRetryAfterMs(response.header("Retry-After"))
                    // Read via HttpBodyReader (#62): a body read that throws inside onResponse is
                    // swallowed by OkHttp (onFailure never fires), so this callback would otherwise
                    // never be invoked and the dictation would hang until the watchdog.
                    HttpBodyReader.read(response).fold(
                        onSuccess = { responseBody ->
                            val parsed = if (successful) {
                                resultFor(responseBody, vocabularyTerms, prompt, model = effectiveModel).copy(httpCode = httpCode)
                            } else {
                                val envelope = resultFor(responseBody, vocabularyTerms, prompt, model = effectiveModel)
                                val errorMsg = envelope.error
                                    ?: responseBody.trim().replace(Regex("\\s+"), " ").take(200).ifEmpty { "HTTP $httpCode" }
                                Result(null, errorMsg, httpCode = httpCode, retryAfterMs = retryAfterMs)
                            }
                            val overloaded = parsed.text == null && isOverloaded(httpCode, parsed.error)
                            when {
                                !overloaded -> callback(parsed)
                                attempt < retryDelaysMs.size -> retryScheduler.schedule({
                                    // A cancel during the backoff has no Call to abort, so it is
                                    // checked here instead of sending a request nobody waits for.
                                    if (cancelHolder.cancelCount != cancelsAtStart || cancelHolder.isCancelled) {
                                        callback(Result(null, "Canceled", isCancelled = true))
                                    } else {
                                        send(attempt + 1)
                                    }
                                }, retryDelaysMs[attempt], TimeUnit.MILLISECONDS)
                                retryDelaysMs.isEmpty() -> callback(parsed)
                                else -> callback(Result(null, overloadedMessage(model, attempt + 1), httpCode = httpCode))
                            }
                        },
                        onFailure = { e ->
                            val cancelled = call.isCanceled() && cancelHolder.isCancelled
                            val error = if (!cancelled && call.isCanceled()) normalizedTimeoutError(e) else e.message
                            callback(
                                Result(
                                    null,
                                    error,
                                    isTransientIo = !cancelled && CloudErrorClassifier.isTransientIoException(e),
                                    isCancelled = cancelled,
                                )
                            )
                        },
                    )
                }
            })
        }
        send(0)
    }

    private fun normalizedTimeoutError(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() && !it.equals("Canceled", ignoreCase = true) } ?: "timeout"
}
