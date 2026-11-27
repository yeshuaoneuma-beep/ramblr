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

/** Production Gemini 3.5 Transcribe Live WebSocket factory.
 *
 * Authentication uses the existing device-resident Gemini API key in an HTTP header, never a URL.
 * OkHttp invokes socket methods off-main; listener callbacks are serialized on [callbackExecutor]
 * (also off-main by default). The runtime is responsible for explicitly hopping UI work to main.
 */
class GeminiCloudLiveTranscriptionClient(
    private val httpClient: OkHttpClient = NetworkClients.shared,
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
) : CloudLiveTranscriptionSessionFactory {
    private val callbacks = SerialCallbackExecutor(callbackExecutor)
    private val inboundLimitBytes = if (!usesTranscribeTextProtocol(model)) MAX_INBOUND_AUDIO_MODEL_BYTES else MAX_INBOUND_BYTES

    init {
        require(endpoint.scheme == "https" || (allowTestEndpoint && endpoint.scheme == "http"))
        require(endpoint.encodedPath == ENDPOINT_PATH && endpoint.query == null && endpoint.fragment == null) { "Unexpected Gemini Live endpoint" }
        require(allowTestEndpoint || endpoint.host == GEMINI_HOST) { "Unexpected Gemini Live host" }
        require(apiKey.isNotBlank()) { "Gemini API key is blank" }
        require(isSupportedLiveModel(model)) { "Invalid Gemini Live model id" }
        require(languageCodes.all(LANGUAGE_CODE::matches)) { "Invalid language code" }
        require(customVocabulary.size <= MAX_CUSTOM_VOCABULARY && customVocabulary.none { it.isBlank() }) { "Invalid custom vocabulary" }
        require(setupTimeoutMs > 0 && finalTimeoutMs > 0 && maxSessionMs > 0 && keepaliveIntervalMs > 0)
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
        private var lastAudioSentAtMs = 0L
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

        override fun startActivity(): Boolean = enqueueControl(activityStartJson(), isStart = true)

        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            if (terminal.get()) return false
            if (length < 0 || length > buffer.size) {
                fail(CloudLiveFailureReason.INVALID_REQUEST, "Invalid PCM length")
                return false
            }
            val copy = buffer.copyOf(length)
            val encoded = Base64.getEncoder().encodeToString(copy)
            val message = audioJson(encoded)
            synchronized(lock) {
                if (terminal.get()) return false
                lastAudioSentAtMs = nowMs()
            }
            return enqueue(message)
        }

        override fun endActivity(): Boolean {
            val accepted = enqueueControl(activityEndJson(), isStart = false)
            if (accepted) synchronized(lock) {
                keepaliveTask?.cancel(false)
                keepaliveTask = null
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
         *
         * Nothing is ever handed straight to the socket, even after the handshake lands. A direct
         * send would let a message enqueued *later* reach the wire ahead of one still sitting in
         * the queue: the recorder thread's next PCM chunk used to overtake the not-yet-flushed
         * `activityStart` while [onSetupComplete] was mid-flush, and with
         * `automaticActivityDetection.disabled = true` the server drops audio that arrives before
         * activityStart. Routing every message through the queue makes append order == wire order
         * by construction, which is exactly what [CloudLiveTranscriptionSession] promises.
         *
         * The monitor is never held across a send: OkHttp's [WebSocket.send] enqueues onto its own
         * writer rather than blocking on the network, but keeping it outside the lock also means a
         * slow writer can never stall the recorder thread's next `sendPcm`.
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

        /**
         * Sends every queued message in FIFO order. At most one thread drains at a time; whoever
         * loses the race simply returns, because the winner is guaranteed to pick up its message
         * (the emptiness check that ends a drain and the `draining = false` that reopens it happen
         * in the same critical section, so no enqueue can be stranded).
         */
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
                // A missing socket after setup is a released/never-opened connection, not a full
                // outbound buffer -- report what actually happened.
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

        /** Pops the next queued message, or ends this drain (in the same critical section as the
         *  emptiness check) and returns null. */
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
            synchronized(lock) {
                if (terminal.get() || setupComplete) return
                setupComplete = true
                val now = nowMs()
                lastAudioSentAtMs = now
                timing = timing.copy(setupCompletedAtMs = now)
                setupTimeout?.cancel(false)
                setupTimeout = null
                if (!endRequested && !terminal.get() && keepaliveTask == null) {
                    keepaliveTask = scheduler.scheduleAtFixedRate(
                        { maybeSendKeepalive() },
                        keepaliveIntervalMs,
                        keepaliveIntervalMs,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
            drainPending()
            synchronized(lock) { if (endRequested && !terminal.get()) armFinalTimeoutLocked() }
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
            if (root.has("setupComplete")) {
                onSetupComplete()
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
            if (!usesTranscribeTextProtocol(model)) {
                // gemini-3.8-live / audio-modality live models: ignore modelTurn (audio output) and outputTranscription.
                val inputTx = content.optJSONObject("inputTranscription")
                var interimToEmit: String? = null
                var finalToDeliver: String? = null
                synchronized(lock) {
                    if (terminal.get()) return
                    if (inputTx != null && !inputTx.isNull("text")) {
                        val fragment = inputTx.optString("text", "")
                        if (fragment.isNotEmpty()) {
                            accumulatedInputTranscript.append(fragment)
                            val trimmed = accumulatedInputTranscript.toString().trim()
                            if (trimmed.isNotBlank()) {
                                interimToEmit = trimmed
                            }
                        }
                    }
                    val turnComplete = content.optBoolean("turnComplete", false)
                    val generationComplete = content.optBoolean("generationComplete", false)
                    val txFinished = inputTx?.optBoolean("finished", false) == true
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

            // gemini-3.5-transcribe-live: emit interimInputTranscription, never wait for turnComplete.
            content.optJSONObject("interimInputTranscription")?.takeIf { !it.isNull("text") }
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let(::emitInterim)

            content.optJSONObject("inputTranscription")?.takeIf { !it.isNull("text") }
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { segment ->
                    var finalToDeliver: String? = null
                    var interimToEmit: String? = null
                    synchronized(lock) {
                        if (terminal.get()) return@let
                        val piece = segment.trim()
                        if (mode == LiveTranscriptionMode.SMART) {
                            accumulatedInputTranscript.setLength(0)
                            accumulatedInputTranscript.append(piece)
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

            /**
             * Gemini Live delivers its JSON events as binary frames as well as text
             * ones; the frame opcode is not part of the protocol contract. Decode and
             * parse them identically instead of treating binary as fatal. Malformed
             * bytes still fail closed via [onText]'s JSON parse.
             */
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
            /**
             * OkHttp's default onClosing does not echo the close frame, so a
             * server-initiated close never advances to onClosed -- verified: a
             * 6s wait yields onClosing(1011) and nothing else. Handling only
             * onClosed left a mid-stream server close with no terminal at all,
             * stalling the session until the final timeout expired. Treat the
             * close frame itself as the terminal.
             */
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
        const val DEFAULT_FINAL_TIMEOUT_MS = 2_000L
        const val DEFAULT_KEEPALIVE_INTERVAL_MS = 25_000L
        /** Proactive 9:00 cutoff ahead of Gemini Live's 10-minute WebSocket limit. */
        const val DEFAULT_MAX_SESSION_MS = 540_000L
        const val MAX_PENDING_BYTES = 2L * 1024 * 1024
        const val MAX_WEBSOCKET_QUEUE_BYTES = 4L * 1024 * 1024
        const val MAX_INBOUND_BYTES = 256 * 1024
        const val MAX_INBOUND_AUDIO_MODEL_BYTES = 2 * 1024 * 1024
        private val SILENT_KEEPALIVE_PCM_BASE64: String = Base64.getEncoder().encodeToString(ByteArray(3200))
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val NORMAL_CLOSE = 1000
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val LANGUAGE_CODE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")

        internal fun isSupportedLiveModel(model: String): Boolean =
            MODEL_ID.matches(model) &&
                ModelCapabilityClassifier.supportsLane(
                    ProviderCapabilityRegistry.ID_GEMINI,
                    model,
                    CapabilityLane.LIVE_STT,
                )

        internal fun usesTranscribeTextProtocol(model: String): Boolean =
            model == DEFAULT_MODEL || "transcribe" in model.lowercase()

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
                val transcription = JSONObject()
                if (languageCodes.isNotEmpty()) transcription.put("languageCodes", JSONArray(languageCodes))
                if (customVocabulary.isNotEmpty()) transcription.put("customVocabulary", JSONArray(customVocabulary))
                setup.put("inputAudioTranscription", transcription)
            } else {
                setup.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                setup.put(
                    "inputAudioTranscription",
                    JSONObject()
                        .put("languageCodes", JSONArray(languageCodes))
                        .put("customVocabulary", JSONArray(customVocabulary))
                        .put("mode", mode.name),
                )
            }
            setup.put(
                "realtimeInputConfig",
                JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)),
            )
            return JSONObject().put("setup", setup).toString()
        }

        private fun activityStartJson() = JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString()
        private fun activityEndJson() = JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString()
        private fun audioJson(base64: String) = JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", base64)
            .put("mimeType", "audio/pcm;rate=16000"))).toString()
    }
}
