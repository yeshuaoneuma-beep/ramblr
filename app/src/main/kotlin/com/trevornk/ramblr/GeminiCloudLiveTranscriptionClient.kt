package com.trevornk.ramblr

import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Production Gemini Live WebSocket factory covering both:
 * - Dedicated `TEXT`-protocol live transcription models (`gemini-3.5-transcribe-live` / `*-transcribe*`),
 *   which use manual `activityStart`/`activityEnd` boundaries with `automaticActivityDetection.disabled = true`.
 * - `AUDIO`-modality live models (`gemini-3.8-live`, `gemini-2.5-flash-native-audio-*`,
 *   `gemini-3.1-flash-live-preview`, `gemini-3.8-live-extended-thinking`), which use server-side
 *   automatic VAD (omitting `realtimeInputConfig`) and end capture via `realtimeInput.audioStreamEnd = true`.
 *   Note: server-side chunking cadence on `AUDIO`-modality models is controlled by Google's backend
 *   and cannot be forced faster client-side.
 *
 * Authentication uses the existing device-resident Gemini API key in an HTTP header, never a URL.
 * OkHttp invokes socket methods off-main; listener callbacks are serialized on [callbackExecutor]
 * (also off-main by default). The runtime is responsible for explicitly hopping UI work to main.
 */
class GeminiCloudLiveTranscriptionClient(
    private val httpClient: OkHttpClient = NetworkClients.liveWebSocket,
    // OkHttp's WebSocket API accepts an HTTPS HttpUrl and performs the WSS upgrade itself.
    private val endpoint: HttpUrl = DEFAULT_HTTP_ENDPOINT.toHttpUrl(),
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    internal val languageCodes: List<String> = emptyList(),
    private val customVocabulary: List<String> = emptyList(),
    callbackExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeminiLive-callback").apply { isDaemon = true }
    },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GeminiLive-timeout").apply { isDaemon = true }
    },
    private val setupTimeoutMs: Long = DEFAULT_SETUP_TIMEOUT_MS,
    private val finalTimeoutMs: Long = DEFAULT_FINAL_TIMEOUT_MS,
    internal val maxSessionMs: Long = DEFAULT_MAX_SESSION_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    internal val allowTestEndpoint: Boolean = false,
    internal val mode: LiveTranscriptionMode = LiveTranscriptionMode.VERBATIM,
    private val keepaliveIntervalMs: Long = DEFAULT_KEEPALIVE_INTERVAL_MS,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val stallCheckIntervalMs: Long = DEFAULT_STALL_CHECK_INTERVAL_MS,
) : CloudLiveTranscriptionSessionFactory {
    private val callbacks = SerialCallbackExecutor(callbackExecutor)
    private val isTextProtocol = usesTranscribeTextProtocol(model)
    private val inboundLimitBytes = if (!isTextProtocol) MAX_INBOUND_AUDIO_MODEL_BYTES else MAX_INBOUND_BYTES

    init {
        require(endpoint.scheme == "https" || (allowTestEndpoint && endpoint.scheme == "http"))
        require(endpoint.encodedPath == ENDPOINT_PATH && endpoint.query == null && endpoint.fragment == null) { "Unexpected Gemini Live endpoint" }
        require(allowTestEndpoint || endpoint.host == GEMINI_HOST) { "Unexpected Gemini Live host" }
        require(apiKey.isNotBlank()) { "Gemini API key is blank" }
        require(isSupportedLiveModel(model)) { "Invalid Gemini Live model id" }
        require(languageCodes.all(LANGUAGE_CODE::matches)) { "Invalid language code" }
        require(customVocabulary.size <= MAX_CUSTOM_VOCABULARY && customVocabulary.none { it.isBlank() }) { "Invalid custom vocabulary" }
        require(
            setupTimeoutMs > 0 &&
                finalTimeoutMs > 0 &&
                maxSessionMs > 0 &&
                keepaliveIntervalMs > 0 &&
                stallTimeoutMs > 0 &&
                stallCheckIntervalMs > 0
        )
    }

    override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession = Session(listener)

    private inner class Session(private val listener: CloudLiveTranscriptionListener) : CloudLiveTranscriptionSession {
        private val lock = Any()
        private val terminal = AtomicBoolean(false)
        private val pending = ArrayDeque<String>()
        private var pendingBytes = 0L
        private var socket: WebSocket? = null
        private var connected = false
        private var setupComplete = false
        private var draining = false
        private var startRequested = false
        private var endRequested = false
        private var setupTimeout: ScheduledFuture<*>? = null
        private var finalTimeout: ScheduledFuture<*>? = null
        private var sessionTimeout: ScheduledFuture<*>? = null
        private var keepaliveTask: ScheduledFuture<*>? = null
        private var stallWatchdogTask: ScheduledFuture<*>? = null
        private var lastAudioSentAtMs = 0L
        private var lastPcmAtMs = 0L
        private var lastPcmHadSpeech = false
        private var activeSpeechSinceTranscriptMs = 0L
        private var congestedSinceMs = 0L
        private var turnCompletedSinceLastSpeech = false
        private var turnBoundaryPending = false
        private val accumulatedInputTranscript = StringBuilder()
        private var timing = CloudLiveTiming(connectStartedAtMs = nowMs())

        override fun connect() {
            synchronized(lock) {
                if (connected || terminal.get()) return
                connected = true
                timing = timing.copy(connectStartedAtMs = nowMs())
                setupTimeout = scheduler.schedule(
                    { fail(CloudLiveFailureReason.SETUP_TIMEOUT, "Live transcription setup timed out") },
                    setupTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
                sessionTimeout = scheduler.schedule(
                    { fail(CloudLiveFailureReason.SESSION_LIMIT, "Live transcription session limit reached (9:00)") },
                    maxSessionMs,
                    TimeUnit.MILLISECONDS,
                )
                val request = Request.Builder().url(endpoint).header(API_KEY_HEADER, apiKey).build()
                socket = httpClient.newWebSocket(request, SocketListener())
            }
        }

        override fun startActivity(): Boolean {
            if (isTextProtocol) {
                return enqueueControl(activityStartJson(), isStart = true)
            }
            // AUDIO-modality models use server-side automatic VAD: no activityStart frame on the wire.
            synchronized(lock) {
                if (terminal.get() || startRequested || endRequested) return false
                startRequested = true
            }
            return true
        }

        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            if (terminal.get()) return false
            if (length < 0 || length > buffer.size) {
                fail(CloudLiveFailureReason.INVALID_REQUEST, "Invalid PCM length")
                return false
            }
            val copy = buffer.copyOf(length)
            val speech = hasSpeechEnergy(copy, copy.size)
            val encoded = Base64.getEncoder().encodeToString(copy)
            val message = audioJson(encoded)
            synchronized(lock) {
                if (terminal.get()) return false
                val now = nowMs()
                lastAudioSentAtMs = now
                if (speech) {
                    turnCompletedSinceLastSpeech = false
                    val deltaMs = if (lastPcmAtMs > 0L) (now - lastPcmAtMs).coerceAtLeast(0L) else 0L
                    val chunkDurationMs = (copy.size / 32L).coerceAtLeast(1L)
                    activeSpeechSinceTranscriptMs += if (lastPcmHadSpeech) {
                        maxOf(deltaMs, chunkDurationMs)
                    } else {
                        chunkDurationMs
                    }
                }
                lastPcmAtMs = now
                lastPcmHadSpeech = speech
            }
            return enqueue(message)
        }

        override fun endActivity(): Boolean {
            val endFrame = if (isTextProtocol) activityEndJson() else audioStreamEndJson()
            val accepted = enqueueControl(endFrame, isStart = false)
            if (accepted) synchronized(lock) {
                keepaliveTask?.cancel(false)
                keepaliveTask = null
                stallWatchdogTask?.cancel(false)
                stallWatchdogTask = null
                timing = timing.copy(activityEndedAtMs = nowMs())
                if (setupComplete) armFinalTimeoutLocked()
            }
            return accepted
        }

        private fun enqueueControl(message: String, isStart: Boolean): Boolean {
            synchronized(lock) {
                if (terminal.get()) return false
                if (isStart) {
                    if (startRequested || endRequested) return false
                    startRequested = true
                } else {
                    if (!startRequested || endRequested) return false
                    endRequested = true
                }
            }
            return enqueue(message)
        }

        /**
         * Appends [message] to [pending] -- the single ordering authority -- then drains the queue
         * if no other thread already owns the drain.
         */
        private fun enqueue(message: String): Boolean {
            val buffered = synchronized(lock) {
                if (terminal.get()) return false
                val bytes = message.toByteArray(Charsets.UTF_8).size.toLong()
                if (pendingBytes + bytes > MAX_PENDING_BYTES) {
                    false // Failure is completed outside this monitor.
                } else {
                    pending.addLast(message)
                    pendingBytes += bytes
                    true
                }
            }
            if (!buffered) {
                fail(CloudLiveFailureReason.BUFFER_LIMIT, "Live transcription outbound buffer limit reached")
                return false
            }
            drainPending()
            return true
        }

        private fun drainPending() {
            val owned = synchronized(lock) {
                if (terminal.get() || !setupComplete || draining) return
                draining = true
                true
            }
            if (!owned) return
            var failure: String? = null
            while (true) {
                val message = takeNextPendingOrEndDrain() ?: break
                val ws = synchronized(lock) { socket }
                if (ws == null) { failure = "Live transcription socket unavailable"; break }
                if (ws.queueSize() > MAX_WEBSOCKET_QUEUE_BYTES || !ws.send(message)) {
                    failure = "Live transcription send failed"
                    break
                }
            }
            if (failure != null) {
                synchronized(lock) { draining = false }
                fail(CloudLiveFailureReason.SEND_FAILED, failure)
            }
        }

        private fun takeNextPendingOrEndDrain(): String? = synchronized(lock) {
            if (terminal.get() || pending.isEmpty()) {
                draining = false
                return null
            }
            val next = pending.removeFirst()
            pendingBytes -= next.toByteArray(Charsets.UTF_8).size.toLong()
            next
        }

        override fun cancel() = fail(CloudLiveFailureReason.CANCELLED, "Live transcription cancelled")

        override fun close() {
            if (!terminal.get()) cancel()
            synchronized(lock) { socket?.close(NORMAL_CLOSE, "done") }
        }

        private fun onSetupComplete() {
            val shouldNotifyConnected: Boolean
            synchronized(lock) {
                if (terminal.get() || setupComplete) return
                setupComplete = true
                shouldNotifyConnected = true
                val now = nowMs()
                lastAudioSentAtMs = now
                timing = timing.copy(setupCompletedAtMs = now)
                setupTimeout?.cancel(false)
                setupTimeout = null
                if (!endRequested && !terminal.get()) {
                    if (keepaliveTask == null) {
                        keepaliveTask = scheduler.scheduleAtFixedRate(
                            { maybeSendKeepalive() },
                            keepaliveIntervalMs,
                            keepaliveIntervalMs,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                    if (stallWatchdogTask == null) {
                        stallWatchdogTask = scheduler.scheduleAtFixedRate(
                            { checkForStall() },
                            stallCheckIntervalMs,
                            stallCheckIntervalMs,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            }
            if (shouldNotifyConnected) {
                callbacks.execute { if (!terminal.get()) listener.onConnected() }
            }
            drainPending()
            synchronized(lock) { if (endRequested && !terminal.get()) armFinalTimeoutLocked() }
        }

        private fun checkForStall() {
            val stallMessage: String? = synchronized(lock) {
                if (terminal.get() || !setupComplete || endRequested) return@synchronized null
                val now = nowMs()
                if (activeSpeechSinceTranscriptMs >= stallTimeoutMs) {
                    return@synchronized "Live transcription stream stalled (no response)"
                }
                val qBytes = socket?.queueSize() ?: 0L
                if (qBytes > CONGESTED_QUEUE_BYTES) {
                    if (congestedSinceMs == 0L) {
                        congestedSinceMs = now
                    } else if (now - congestedSinceMs >= stallTimeoutMs) {
                        return@synchronized "Live transcription outbound queue stalled"
                    }
                } else {
                    congestedSinceMs = 0L
                }
                null
            }
            if (stallMessage != null) {
                fail(CloudLiveFailureReason.STREAM_STALLED, stallMessage)
            }
        }

        private fun maybeSendKeepalive() {
            val shouldSend = synchronized(lock) {
                if (terminal.get() || !setupComplete || !startRequested || endRequested) return@synchronized false
                val now = nowMs()
                if (now - lastAudioSentAtMs < keepaliveIntervalMs) return@synchronized false
                lastAudioSentAtMs = now
                true
            }
            if (shouldSend) {
                enqueue(audioJson(SILENT_KEEPALIVE_PCM_BASE64))
            }
        }

        private fun armFinalTimeoutLocked() {
            if (finalTimeout != null) return
            // Server-VAD Stop guard: for AUDIO-modality models, if server VAD already finalized
            // the user's last words before Stop, flush cleanly after a short grace window instead
            // of hanging for 10s waiting for a second turnComplete that will never arrive.
            val canGraceFlushPostVad = !isTextProtocol &&
                turnCompletedSinceLastSpeech &&
                accumulatedInputTranscript.toString().trim().isNotBlank()
            if (canGraceFlushPostVad) {
                val graceMs = minOf(POST_VAD_STOP_GRACE_MS, finalTimeoutMs)
                finalTimeout = scheduler.schedule(
                    {
                        val buffered = synchronized(lock) {
                            if (terminal.get()) "" else accumulatedInputTranscript.toString().trim()
                        }
                        if (buffered.isNotBlank()) {
                            succeed(buffered)
                        } else {
                            fail(CloudLiveFailureReason.FINAL_TIMEOUT, "Live transcription final timed out")
                        }
                    },
                    graceMs,
                    TimeUnit.MILLISECONDS,
                )
                return
            }
            // Never silently promote buffered text on timeout for text-protocol or unfinalized
            // server-VAD turns: fail with FINAL_TIMEOUT so DictationRuntime runs its configured
            // recovery policy instead of falsely deleting parallel PCM and truncating the ending.
            finalTimeout = scheduler.schedule(
                { fail(CloudLiveFailureReason.FINAL_TIMEOUT, "Live transcription final timed out") },
                finalTimeoutMs,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun emitInterim(text: String) {
            val snapshot = synchronized(lock) {
                if (terminal.get()) return
                if (timing.firstInterimAtMs == null) timing = timing.copy(firstInterimAtMs = nowMs())
                timing
            }
            callbacks.execute { if (!terminal.get()) listener.onInterim(text, snapshot) }
        }

        private fun onText(text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > inboundLimitBytes) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Live transcription message too large")
                return
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Malformed live transcription event")
                return
            }
            root.optJSONObject("error")?.let { errorObj ->
                val code = errorObj.optInt("code", -1).takeIf { it > 0 }
                fail(
                    CloudLiveFailureReason.PROTOCOL_ERROR,
                    errorObj.optString("message").ifBlank { "Gemini Live error" },
                    errorCode = code,
                )
                return
            }
            if (root.has("goAway")) {
                fail(
                    CloudLiveFailureReason.SESSION_LIMIT,
                    "Live transcription server requested reconnect (goAway)",
                )
                return
            }
            if (root.has("setupComplete")) {
                onSetupComplete()
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
            if (!isTextProtocol) {
                // AUDIO-modality live models: ignore modelTurn (audio output) and outputTranscription.
                val inputTx = content.optJSONObject("inputTranscription")
                val interimTx = content.optJSONObject("interimInputTranscription")
                var interimToEmit: String? = null
                var finalToDeliver: String? = null
                synchronized(lock) {
                    if (terminal.get()) return
                    val inputFragment = if (inputTx != null && !inputTx.isNull("text")) {
                        inputTx.optString("text", "")
                    } else {
                        ""
                    }
                    val interimFragment = if (interimTx != null && !interimTx.isNull("text")) {
                        interimTx.optString("text", "")
                    } else {
                        ""
                    }
                    if (inputFragment.isNotEmpty() || interimFragment.isNotEmpty()) {
                        activeSpeechSinceTranscriptMs = 0L
                        congestedSinceMs = 0L
                    }
                    if (inputFragment.isNotEmpty()) {
                        if (turnBoundaryPending && accumulatedInputTranscript.isNotEmpty()) {
                            val needsSpace = !accumulatedInputTranscript.last().isWhitespace() &&
                                !inputFragment.first().isWhitespace() &&
                                inputFragment.first() !in LEADING_PUNCTUATION
                            if (needsSpace) accumulatedInputTranscript.append(' ')
                            turnBoundaryPending = false
                        }
                        accumulatedInputTranscript.append(inputFragment)
                        val trimmed = accumulatedInputTranscript.toString().trim()
                        if (trimmed.isNotBlank()) {
                            interimToEmit = trimmed
                        }
                    } else if (interimFragment.isNotBlank()) {
                        val acc = accumulatedInputTranscript.toString().trim()
                        val piece = interimFragment.trim()
                        interimToEmit = if (acc.isEmpty()) {
                            piece
                        } else {
                            val accHead = acc.lowercase().take(minOf(acc.length, 16))
                            if (piece.lowercase().startsWith(accHead)) {
                                piece
                            } else if (piece.first() in LEADING_PUNCTUATION) {
                                "$acc$piece"
                            } else {
                                "$acc $piece"
                            }
                        }
                    }
                    val turnComplete = content.optBoolean("turnComplete", false)
                    val generationComplete = content.optBoolean("generationComplete", false)
                    val txFinished = inputTx?.optBoolean("finished", false) == true
                    if (turnComplete || generationComplete) {
                        turnCompletedSinceLastSpeech = true
                        if (accumulatedInputTranscript.isNotEmpty()) {
                            turnBoundaryPending = true
                        }
                    }
                    if (endRequested && (turnComplete || generationComplete || txFinished)) {
                        val combined = accumulatedInputTranscript.toString().trim()
                        if (combined.isNotBlank()) {
                            finalToDeliver = combined
                        }
                    }
                }
                finalToDeliver?.let {
                    succeed(it)
                    return
                }
                interimToEmit?.let(::emitInterim)
                return
            }

            // gemini-3.5-transcribe-live: emit interimInputTranscription (preserving earlier committed text),
            // never wait for turnComplete.
            content.optJSONObject("interimInputTranscription")?.takeIf { !it.isNull("text") }
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { interimRaw ->
                    val display = synchronized(lock) {
                        if (terminal.get()) return@let
                        activeSpeechSinceTranscriptMs = 0L
                        congestedSinceMs = 0L
                        val acc = accumulatedInputTranscript.toString().trim()
                        val interim = interimRaw.trim()
                        if (acc.isEmpty()) {
                            interim
                        } else {
                            val accHead = acc.lowercase().take(minOf(acc.length, 16))
                            if (interim.lowercase().startsWith(accHead)) interim else "$acc $interim"
                        }
                    }
                    emitInterim(display)
                }

            content.optJSONObject("inputTranscription")?.takeIf { !it.isNull("text") }
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { segment ->
                    var finalToDeliver: String? = null
                    var interimToEmit: String? = null
                    synchronized(lock) {
                        if (terminal.get()) return@let
                        activeSpeechSinceTranscriptMs = 0L
                        congestedSinceMs = 0L
                        val piece = segment.trim()
                        if (mode == LiveTranscriptionMode.SMART) {
                            val merged = mergeSmartSegment(accumulatedInputTranscript.toString(), piece)
                            accumulatedInputTranscript.setLength(0)
                            accumulatedInputTranscript.append(merged)
                        } else if (piece.isNotEmpty()) {
                            if (accumulatedInputTranscript.isNotEmpty() &&
                                !accumulatedInputTranscript.last().isWhitespace() &&
                                !piece.first().isWhitespace()
                            ) {
                                accumulatedInputTranscript.append(' ')
                            }
                            accumulatedInputTranscript.append(piece)
                        }
                        val combined = accumulatedInputTranscript.toString().trim()
                        if (combined.isNotBlank()) {
                            if (endRequested) {
                                finalToDeliver = combined
                            } else {
                                interimToEmit = combined
                            }
                        }
                    }
                    finalToDeliver?.let(::succeed) ?: interimToEmit?.let(::emitInterim)
                }
        }

        private fun succeed(text: String) {
            val result = synchronized(lock) {
                timing = timing.copy(finalAtMs = nowMs())
                CloudLiveTerminal.Success(text, timing)
            }
            complete(result)
        }

        private fun fail(reason: CloudLiveFailureReason, message: String, errorCode: Int? = null) {
            val safe = UrlRedaction.redact(message.replace(apiKey, "[REDACTED]"))
                ?: "Live transcription failed"
            val result = synchronized(lock) { CloudLiveTerminal.Failure(reason, safe, timing, errorCode) }
            complete(result)
        }

        private fun complete(result: CloudLiveTerminal) {
            if (!terminal.compareAndSet(false, true)) return
            synchronized(lock) {
                setupTimeout?.cancel(false); setupTimeout = null
                finalTimeout?.cancel(false); finalTimeout = null
                sessionTimeout?.cancel(false); sessionTimeout = null
                keepaliveTask?.cancel(false); keepaliveTask = null
                stallWatchdogTask?.cancel(false); stallWatchdogTask = null
                pending.clear(); pendingBytes = 0
                socket?.close(NORMAL_CLOSE, "done")
            }
            callbacks.execute { listener.onTerminal(result) }
        }

        private inner class SocketListener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) { timing = timing.copy(socketOpenedAtMs = nowMs()) }
                if (!webSocket.send(setupJson(model, languageCodes, customVocabulary, mode))) {
                    fail(CloudLiveFailureReason.SEND_FAILED, "Live transcription setup send failed")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > inboundLimitBytes) {
                    fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Live transcription message too large")
                    return
                }
                onText(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(
                    CloudLiveFailureReason.NETWORK_ERROR,
                    t.message ?: "Live transcription network failure",
                    errorCode = response?.code,
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get()) {
                    fail(
                        CloudLiveFailureReason.NETWORK_ERROR,
                        "Live transcription connection closed ($code)",
                        errorCode = code,
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get()) {
                    fail(
                        CloudLiveFailureReason.NETWORK_ERROR,
                        "Live transcription connection closed ($code)",
                        errorCode = code,
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = LiveTranscriptionConfig.DEFAULT_LIVE_MODEL_ID
        const val SECONDARY_MODEL = LiveTranscriptionConfig.SECONDARY_LIVE_MODEL_ID
        val SUPPORTED_MODELS: Set<String> = setOf(DEFAULT_MODEL, SECONDARY_MODEL)
        const val ENDPOINT_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val GEMINI_HOST = "generativelanguage.googleapis.com"
        const val DEFAULT_WSS_ENDPOINT = "wss://$GEMINI_HOST$ENDPOINT_PATH"
        internal const val DEFAULT_HTTP_ENDPOINT = "https://$GEMINI_HOST$ENDPOINT_PATH"
        const val MAX_CUSTOM_VOCABULARY = 1000
        const val DEFAULT_SETUP_TIMEOUT_MS = 10_000L
        const val DEFAULT_FINAL_TIMEOUT_MS = 10_000L
        const val POST_VAD_STOP_GRACE_MS = 1_500L
        const val DEFAULT_KEEPALIVE_INTERVAL_MS = 25_000L
        const val DEFAULT_STALL_TIMEOUT_MS = 18_000L
        const val DEFAULT_STALL_CHECK_INTERVAL_MS = 1_000L
        const val CONGESTED_QUEUE_BYTES = 256 * 1024L
        internal const val SPEECH_ENERGY_RMS_THRESHOLD = 450.0
        /** Proactive 9:00 cutoff ahead of Gemini Live's 10-minute WebSocket limit. */
        const val DEFAULT_MAX_SESSION_MS = 540_000L
        const val MAX_PENDING_BYTES = 2L * 1024 * 1024
        const val MAX_WEBSOCKET_QUEUE_BYTES = 4L * 1024 * 1024
        const val MAX_INBOUND_BYTES = 256 * 1024
        const val MAX_INBOUND_AUDIO_MODEL_BYTES = 2 * 1024 * 1024
        private const val LEADING_PUNCTUATION = ".,!?;:"
        private val SILENT_KEEPALIVE_PCM_BASE64: String = Base64.getEncoder().encodeToString(ByteArray(3200))
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val NORMAL_CLOSE = 1000
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val LANGUAGE_CODE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        private val SMART_WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val SMART_STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "for", "with", "is",
            "are", "was", "were", "be", "it", "this", "that", "i", "you", "we", "they", "he", "she",
        )

        internal fun isSupportedLiveModel(model: String): Boolean =
            MODEL_ID.matches(model) &&
                ModelCapabilityClassifier.supportsLane(
                    ProviderCapabilityRegistry.ID_GEMINI,
                    model,
                    CapabilityLane.LIVE_STT,
                )

        internal fun usesTranscribeTextProtocol(model: String): Boolean =
            model == DEFAULT_MODEL || "transcribe" in model.lowercase()

        /**
         * Computes 16-bit little-endian PCM RMS amplitude and returns true when the frame contains
         * speech-like energy (`rms >= 450.0`). Pure silence returns false.
         */
        internal fun hasSpeechEnergy(buffer: ByteArray, length: Int): Boolean {
            val samples = minOf(length, buffer.size) / 2
            if (samples <= 0) return false
            var sumSq = 0.0
            for (i in 0 until samples) {
                val sample = ((buffer[2 * i + 1].toInt() shl 8) or (buffer[2 * i].toInt() and 0xFF))
                    .toShort()
                    .toDouble()
                sumSq += sample * sample
            }
            val rms = sqrt(sumSq / samples)
            return rms >= SPEECH_ENERGY_RMS_THRESHOLD
        }

        /**
         * Merges an incoming `SMART` mode `inputTranscription` segment with [accumulated]:
         * replaces [accumulated] only when [incoming] is a cumulative re-edit of it; otherwise
         * appends [incoming] with a single space so earlier paragraphs are never wiped.
         */
        internal fun mergeSmartSegment(accumulated: String, incoming: String): String {
            val acc = accumulated.trim()
            val inc = incoming.trim()
            if (inc.isEmpty()) return acc
            if (acc.isEmpty()) return inc
            val accLower = acc.lowercase()
            val incLower = inc.lowercase()
            val accFirst3 = accLower.split(Regex("\\s+")).take(3).joinToString(" ")
            val incFirst3 = incLower.split(Regex("\\s+")).take(3).joinToString(" ")
            val prefixMatch = (accFirst3.isNotEmpty() && accFirst3 == incFirst3) ||
                (accLower.length >= 16 && incLower.startsWith(accLower.take(16))) ||
                (accLower.length < 16 && incLower.startsWith(accLower))
            if (prefixMatch) return inc
            if (inc.length >= (acc.length * 0.6).toInt()) {
                val accWords = smartContentWords(accLower)
                val incWords = smartContentWords(incLower)
                if (accWords.size >= 3) {
                    val shared = accWords.count { it in incWords }
                    if (shared >= 2 && shared.toDouble() / accWords.size >= 0.4) {
                        return inc
                    }
                }
            }
            return "$acc $inc"
        }

        private fun smartContentWords(lower: String): Set<String> =
            SMART_WORD_REGEX.findAll(lower)
                .map { it.value }
                .filter { it.length > 1 && it !in SMART_STOP_WORDS }
                .toSet()

        fun setupJson(model: String, languageCodes: List<String>, customVocabulary: List<String>): String =
            setupJson(model, languageCodes, customVocabulary, mode = LiveTranscriptionMode.VERBATIM)

        fun setupJson(
            model: String,
            languageCodes: List<String>,
            customVocabulary: List<String>,
            mode: LiveTranscriptionMode = LiveTranscriptionMode.VERBATIM,
        ): String {
            val setup = JSONObject().put("model", "models/$model")
            if (!usesTranscribeTextProtocol(model)) {
                setup.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                // AUDIO-modality models take clean inputAudioTranscription: {} and omit realtimeInputConfig
                // so server-side automatic VAD streams progressive inputTranscription fragments mid-speech.
                setup.put("inputAudioTranscription", JSONObject())
            } else {
                setup.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                setup.put(
                    "inputAudioTranscription",
                    JSONObject()
                        .put("languageCodes", JSONArray(languageCodes))
                        .put("customVocabulary", JSONArray(customVocabulary))
                        .put("mode", mode.name),
                )
                setup.put(
                    "realtimeInputConfig",
                    JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)),
                )
            }
            return JSONObject().put("setup", setup).toString()
        }

        private fun activityStartJson() = JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString()
        private fun activityEndJson() = JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString()
        private fun audioStreamEndJson() = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()
        private fun audioJson(base64: String) = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", base64)
            .put("mimeType", "audio/pcm;rate=16000"))).toString()
    }
}
