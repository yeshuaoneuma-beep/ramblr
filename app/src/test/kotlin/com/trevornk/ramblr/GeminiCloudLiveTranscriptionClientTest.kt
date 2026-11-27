package com.trevornk.ramblr

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class GeminiCloudLiveTranscriptionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scheduler: ScheduledThreadPoolExecutor

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        scheduler = ScheduledThreadPoolExecutor(1)
    }

    @After fun tearDown() {
        scheduler.shutdownNow()
        server.close()
    }

    @Test fun `setup is exact verbatim live transcription shape`() {
        val setup = GeminiCloudLiveTranscriptionClient.setupJson(
            model = "gemini-3.5-transcribe-live",
            languageCodes = listOf("en-US"),
            customVocabulary = listOf("Ramblr", "HitSnooze"),
        )
        val root = JSONObject(setup)
        assertEquals(setOf("setup"), root.keys().asSequence().toSet())
        val body = root.getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", body.getString("model"))
        assertEquals(listOf("TEXT"), body.getJSONObject("generationConfig").getJSONArray("responseModalities").toStringList())
        val transcription = body.getJSONObject("inputAudioTranscription")
        assertEquals(listOf("en-US"), transcription.getJSONArray("languageCodes").toStringList())
        assertEquals(listOf("Ramblr", "HitSnooze"), transcription.getJSONArray("customVocabulary").toStringList())
        assertEquals("VERBATIM", transcription.getString("mode"))
        assertTrue(body.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
    }

    @Test fun `endpoint model language and vocabulary validation rejects unsafe values`() {
        assertThrows(IllegalArgumentException::class.java) { factory(endpoint = server.url("/wrong")) }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiCloudLiveTranscriptionClient(
                endpoint = "https://evil.example${GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH}".toHttpUrl(),
                apiKey = "key",
            )
        }
        assertThrows(IllegalArgumentException::class.java) { factory(model = "models/bad?key=secret") }
        assertThrows(IllegalArgumentException::class.java) { factory(languages = listOf("not a language!")) }
        assertThrows(IllegalArgumentException::class.java) { factory(vocabulary = listOf("")) }
        assertThrows(IllegalArgumentException::class.java) { factory(vocabulary = (1..1001).map { "term-$it" }) }
    }

    @Test fun `handshake gates activity and copied pcm then preserves wire order`() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val allMessages = CountDownLatch(4)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (received.size == 1) webSocket.send("{\"setupComplete\":{}}")
                allMessages.countDown()
            }
        }).build())
        val terminal = RecordingListener()
        val session = factory().create(terminal)
        val pcm = byteArrayOf(1, 2, 3, 4)

        session.connect()
        session.startActivity()
        assertTrue(session.sendPcm(pcm, pcm.size))
        pcm.fill(9)
        session.endActivity()

        assertTrue(allMessages.await(5, TimeUnit.SECONDS))
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent", upgrade.target)
        assertEquals("test-key", upgrade.headers["x-goog-api-key"])
        assertFalse(upgrade.target.contains("test-key"))
        assertEquals(setOf("setup"), JSONObject(received[0]).keys().asSequence().toSet())
        assertTrue(JSONObject(received[1]).getJSONObject("realtimeInput").has("activityStart"))
        val audio = JSONObject(received[2]).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals("AQIDBA==", audio.getString("data"))
        assertTrue(JSONObject(received[3]).getJSONObject("realtimeInput").has("activityEnd"))
        assertNull(terminal.result)
        session.cancel()
    }

    @Test fun `interim and authoritative final parse and terminal callback exactly once`() {
        val listener = RecordingListener()
        val socket = scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"hel\"}}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"hello\"}}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"late\"}}}",
        )
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(listOf("hel"), listener.interims)
        assertEquals("hello", (listener.result as CloudLiveTerminal.Success).text)
        assertEquals(1, listener.terminals)
        assertTrue((listener.result as CloudLiveTerminal.Success).timing.setupCompletedAtMs != null)
        assertTrue((listener.result as CloudLiveTerminal.Success).timing.finalAtMs != null)
        socket.awaitMessages()
    }

    @Test fun `server error malformed event close and send failure are terminal once and redact key`() {
        val secret = "credential-value"
        val cases = listOf(
            "{\"error\":{\"message\":\"bad key $secret\"}}",
            "not-json",
        )
        cases.forEach { event ->
            val listener = RecordingListener()
            scriptedSocket("{\"setupComplete\":{}}", event)
            val session = factory(apiKey = secret).create(listener)
            session.connect(); session.startActivity()
            assertTrue(listener.done.await(5, TimeUnit.SECONDS))
            val failure = listener.result as CloudLiveTerminal.Failure
            assertFalse(failure.message.contains(secret))
            assertEquals(1, listener.terminals)
        }
    }

    @Test fun `cancel is terminal once and late messages cannot escape`() {
        val listener = RecordingListener()
        scriptedSocket("{\"setupComplete\":{}}", "{\"serverContent\":{\"inputTranscription\":{\"text\":\"late\"}}}", delayMs = 150)
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.cancel(); session.close()
        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(250)
        assertEquals(CloudLiveFailureReason.CANCELLED, (listener.result as CloudLiveTerminal.Failure).reason)
        assertEquals(1, listener.terminals)
    }

    @Test fun `setup and final timeouts fail with distinct reasons`() {
        val setupListener = RecordingListener()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : ClosingWebSocketListener() {}).build())
        factory(setupTimeoutMs = 40).create(setupListener).connect()
        assertTrue(setupListener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.SETUP_TIMEOUT, (setupListener.result as CloudLiveTerminal.Failure).reason)

        val finalListener = RecordingListener()
        scriptedSocket("{\"setupComplete\":{}}")
        val session = factory(finalTimeoutMs = 40).create(finalListener)
        session.connect(); session.startActivity(); session.endActivity()
        assertTrue(finalListener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.FINAL_TIMEOUT, (finalListener.result as CloudLiveTerminal.Failure).reason)
    }

    /**
     * The blocker this covers: `onSetupComplete` used to flip `setupComplete` and snapshot the
     * buffered queue inside the lock, then flush OUTSIDE it. A recorder-thread `sendPcm` landing
     * in that window saw `setupComplete == true`, took the direct-send branch, and reached the
     * socket AHEAD of the still-unflushed `activityStart`. With
     * `automaticActivityDetection.disabled = true` that audio is dropped by the server, so the
     * ordering contract in [CloudLiveTranscriptionSession] is load-bearing, not cosmetic.
     *
     * The existing wire-order test drives everything from one thread and cannot see this; this
     * one holds the flush open inside the fake socket's `send` and pumps a second chunk from
     * another thread while the drain is suspended mid-flight.
     */
    @Test fun `pcm enqueued while the handshake flush is mid drain cannot overtake activity start`() {
        val socket = GatedWebSocket(gateOn = "activityStart")
        val client = FakeWebSocketClient(socket)
        val listener = RecordingListener()
        val session = factory(httpClient = client, setupTimeoutMs = 30_000).create(listener)

        session.connect()
        val socketListener = requireNotNull(client.socketListener) { "connect() must open a socket" }
        socketListener.onOpen(socket, upgradeResponse())
        assertTrue(session.startActivity())
        assertTrue(session.sendPcm(byteArrayOf(1), 1)) // "AQ=="

        // OkHttp's reader thread delivers setupComplete; the fake socket suspends the drain
        // exactly where the real one would be waiting on the network for activityStart.
        val flusher = Thread({ socketListener.onMessage(socket, "{\"setupComplete\":{}}") }, "flush")
        flusher.start()
        assertTrue("flush must reach activityStart", socket.gateReached.await(5, TimeUnit.SECONDS))

        // ...and the recorder thread pumps its next chunk right into that window.
        val pump = Thread({ assertTrue(session.sendPcm(byteArrayOf(2), 1)) }, "recorder") // "Ag=="
        pump.start()
        pump.join(5_000)
        assertFalse("sendPcm must never block on the flush", pump.isAlive)

        socket.releaseGate.countDown()
        flusher.join(5_000)
        assertFalse(flusher.isAlive)
        socket.awaitSent(4)

        assertEquals(
            listOf("setup", "activityStart", "audio:AQ==", "audio:Ag=="),
            socket.sent.map(::wireKind),
        )
        session.cancel()
    }

    /**
     * BUFFER_LIMIT must mean what it says. It used to also be reported when the post-handshake
     * direct-send branch found a null socket -- a released/never-opened connection, not a full
     * outbound buffer -- which is now [CloudLiveFailureReason.SEND_FAILED] with its own message.
     * This pins the genuinely-reachable direction: pre-handshake buffering really does overflow.
     */
    @Test fun `outbound buffer overflow before the handshake reports buffer limit`() {
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val listener = RecordingListener()
        val session = factory(httpClient = client, setupTimeoutMs = 30_000).create(listener)

        session.connect()
        socket.let { client.socketListener!!.onOpen(it, upgradeResponse()) }
        assertTrue(session.startActivity())

        // Nothing has been flushed yet (no setupComplete), so these accumulate against the 2MB cap.
        val chunk = ByteArray(768 * 1024)
        assertTrue("first chunk fits under the cap", session.sendPcm(chunk, chunk.size))
        assertFalse("the second overflows it", session.sendPcm(chunk, chunk.size))

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.BUFFER_LIMIT, failure.reason)
        assertTrue(failure.message.contains("buffer limit"))
    }

    private fun wireKind(message: String): String {
        val root = JSONObject(message)
        if (root.has("setup")) return "setup"
        val realtime = root.getJSONObject("realtimeInput")
        return when {
            realtime.has("activityStart") -> "activityStart"
            realtime.has("activityEnd") -> "activityEnd"
            realtime.has("audioStreamEnd") -> "audioStreamEnd"
            else -> "audio:" + realtime.getJSONObject("audio").getString("data")
        }
    }

    private fun upgradeResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    /** Hands the session a socket the test fully controls, and captures its [WebSocketListener]. */
    private class FakeWebSocketClient(private val socket: WebSocket) : OkHttpClient() {
        @Volatile var socketListener: WebSocketListener? = null
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            socketListener = listener
            return socket
        }
    }

    /** Records wire order, and suspends inside `send` for the first message matching [gateOn]. */
    private class GatedWebSocket(private val gateOn: String) : WebSocket {
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val gateReached = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        private val sentSignal = Semaphore(0)

        override fun request(): Request = Request.Builder().url("https://example.com/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (text.contains(gateOn)) {
                gateReached.countDown()
                releaseGate.await(5, TimeUnit.SECONDS)
            }
            sent += text
            sentSignal.release()
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit

        fun awaitSent(count: Int) {
            assertTrue("expected $count sends, saw ${sent.size}", sentSignal.tryAcquire(count, 5, TimeUnit.SECONDS))
        }
    }

    private fun factory(
        apiKey: String = "test-key",
        endpoint: okhttp3.HttpUrl = server.url("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"),
        model: String = "gemini-3.5-transcribe-live",
        languages: List<String> = listOf("en-US"),
        vocabulary: List<String> = listOf("Ramblr"),
        setupTimeoutMs: Long = 2_000,
        finalTimeoutMs: Long = 2_000,
        maxSessionMs: Long = GeminiCloudLiveTranscriptionClient.DEFAULT_MAX_SESSION_MS,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = GeminiCloudLiveTranscriptionClient(
        httpClient = httpClient,
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        languageCodes = languages,
        customVocabulary = vocabulary,
        callbackExecutor = Executor { it.run() },
        scheduler = scheduler,
        setupTimeoutMs = setupTimeoutMs,
        finalTimeoutMs = finalTimeoutMs,
        maxSessionMs = maxSessionMs,
        allowTestEndpoint = true,
    )

    /**
     * Regression: Gemini Live delivers its JSON events as binary frames. Treating
     * binary as a protocol violation killed every real session before
     * setupComplete, so live transcription always fell back to batch. Every other
     * test in this class drives the String overload, which is why the field defect
     * survived a green suite.
     */
    @Test fun `binary json frames parse identically to text frames`() {
        val listener = RecordingListener()
        val socket = scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"hel\"}}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"hello\"}}}",
            binary = true,
        )
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(listOf("hel"), listener.interims)
        val success = listener.result as CloudLiveTerminal.Success
        assertEquals("hello", success.text)
        assertEquals(1, listener.terminals)
        assertTrue(success.timing.setupCompletedAtMs != null)
        socket.awaitMessages()
    }

    @Test fun `oversized binary frame fails closed without decoding`() {
        val listener = RecordingListener()
        scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"pad\":\"" + "x".repeat(GeminiCloudLiveTranscriptionClient.MAX_INBOUND_BYTES + 1) + "\"}",
            binary = true,
        )
        val session = factory().create(listener)
        session.connect(); session.startActivity(); session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.PROTOCOL_ERROR, failure.reason)
        assertEquals(1, listener.terminals)
    }

    /**
     * The real mid-stream drop: setup succeeded, interims were flowing, then the
     * server closes the socket without ever sending a final. This is the
     * BATCH_SERVED_NO_TERMINAL case, and the one path where a stale interim could
     * leak out as if it were an authoritative final.
     *
     * The server-initiated close arrives as onClosing, and OkHttp's default
     * onClosing does not echo the close frame, so onClosed never follows
     * (measured: 6s yields onClosing(1011) alone). Handling only onClosed left
     * this with no terminal at all until the final timeout expired. Must fail
     * closed: NETWORK_ERROR, exactly one terminal, no interim promoted.
     */
    @Test fun `mid stream server close fails closed without promoting an interim`() {
        val listener = RecordingListener()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Thread {
                    webSocket.send("{\"setupComplete\":{}}")
                    webSocket.send("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"partial words\"}}}")
                    Thread.sleep(50)
                    webSocket.close(1011, "server gone")
                }.start()
            }
        }).build())
        // Long final timeout: if the timeout were what rescued this, the test
        // would take 30s. It must terminate from the close itself, in well under that.
        val session = factory(finalTimeoutMs = 30_000).create(listener)
        val started = System.currentTimeMillis()
        session.connect(); session.startActivity()

        assertTrue(listener.done.await(10, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - started
        assertTrue("terminal came from the close, not the final timeout (took ${elapsed}ms)", elapsed < 10_000)
        Thread.sleep(50)
        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.NETWORK_ERROR, failure.reason)
        assertEquals(1, listener.terminals)
        assertEquals("an interim must never be promoted to a final", listOf("partial words"), listener.interims)
    }

    @Test fun `default maxSessionMs is 9 minutes and non-positive values are rejected`() {
        assertEquals(540_000L, GeminiCloudLiveTranscriptionClient.DEFAULT_MAX_SESSION_MS)
        assertEquals(540_000L, factory().maxSessionMs)
        assertThrows(IllegalArgumentException::class.java) { factory(maxSessionMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { factory(maxSessionMs = -1L) }
    }

    @Test fun `session limit cutoff fails once with SESSION_LIMIT and rejects subsequent pcm`() {
        val listener = RecordingListener()
        scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"still speaking\"}}}",
        )
        val session = factory(setupTimeoutMs = 5_000, finalTimeoutMs = 5_000, maxSessionMs = 120).create(listener)
        session.connect()
        session.startActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)

        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.SESSION_LIMIT, failure.reason)
        assertEquals(1, listener.terminals)
        assertFalse("subsequent PCM after 9:00 cutoff must be rejected cleanly", session.sendPcm(byteArrayOf(1, 2, 3, 4), 4))
        assertEquals("rejected PCM must not emit a second terminal", 1, listener.terminals)
    }

    @Test fun `normal completion before maxSessionMs cancels the session limit timer`() {
        val listener = RecordingListener()
        scriptedSocket(
            "{\"setupComplete\":{}}",
            "{\"serverContent\":{\"inputTranscription\":{\"text\":\"finished in time\"}}}",
        )
        val session = factory(maxSessionMs = 120).create(listener)
        session.connect()
        session.startActivity()
        session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals("finished in time", (listener.result as CloudLiveTerminal.Success).text)
        assertEquals(1, listener.terminals)
    }

    @Test fun `setupJson for gemini-3_5-transcribe-live supports SMART mode and VERBATIM default`() {
        val smart = JSONObject(
            GeminiCloudLiveTranscriptionClient.setupJson(
                model = GeminiCloudLiveTranscriptionClient.DEFAULT_MODEL,
                languageCodes = listOf("en-US"),
                customVocabulary = listOf("Ramblr"),
                mode = LiveTranscriptionMode.SMART,
            )
        ).getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", smart.getString("model"))
        assertEquals(listOf("TEXT"), smart.getJSONObject("generationConfig").getJSONArray("responseModalities").toStringList())
        assertEquals("SMART", smart.getJSONObject("inputAudioTranscription").getString("mode"))
        assertTrue(smart.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
    }

    @Test fun `setupJson for AUDIO-modality live models uses automatic VAD and clean inputAudioTranscription`() {
        val audioModels = listOf(
            "gemini-3.8-live",
            "gemini-2.5-flash-native-audio-latest",
            "gemini-2.5-flash-native-audio-preview-09-2025",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-3.1-flash-live-preview",
            "gemini-3.8-live-extended-thinking",
        )
        for (modelId in audioModels) {
            assertTrue("Expected supported live model: $modelId", GeminiCloudLiveTranscriptionClient.isSupportedLiveModel(modelId))
            assertFalse("Expected AUDIO protocol for $modelId", GeminiCloudLiveTranscriptionClient.usesTranscribeTextProtocol(modelId))
            val setup = JSONObject(
                GeminiCloudLiveTranscriptionClient.setupJson(
                    model = modelId,
                    languageCodes = listOf("en-US"),
                    customVocabulary = listOf("Ramblr"),
                    mode = LiveTranscriptionMode.SMART,
                )
            ).getJSONObject("setup")
            assertEquals("models/$modelId", setup.getString("model"))
            val genConfig = setup.getJSONObject("generationConfig")
            assertEquals(listOf("AUDIO"), genConfig.getJSONArray("responseModalities").toStringList())
            assertFalse(genConfig.has("thinkingConfig"))
            assertFalse("AUDIO-modality models must omit realtimeInputConfig for server VAD", setup.has("realtimeInputConfig"))
            val tx = setup.getJSONObject("inputAudioTranscription")
            assertEquals("inputAudioTranscription must be clean {}", 0, tx.length())
        }
    }

    @Test fun `gemini-3_8-live uses server VAD without activityStart, sends audioStreamEnd, and flushes cleanly post-VAD`() {
        val listener = RecordingListener()
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val session = GeminiCloudLiveTranscriptionClient(
            httpClient = client,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            model = GeminiCloudLiveTranscriptionClient.SECONDARY_MODEL,
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            finalTimeoutMs = 100,
            allowTestEndpoint = true,
        ).create(listener)

        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        assertTrue(session.startActivity())
        assertEquals("onConnected must not fire before setupComplete", 0, listener.connectedCount)
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")
        assertEquals("onConnected must fire once on setupComplete", 1, listener.connectedCount)
        // Ignore audio output and outputTranscription, preserve spaces across mid-recording turnComplete.
        wsListener.onMessage(socket, "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm\",\"data\":\"AAAA\"}}]},\"outputTranscription\":{\"text\":\"spoken reply\"}}}")
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Hello\"}}}")
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\" world\"}}}")
        // turnComplete while still recording must NOT finalize yet.
        wsListener.onMessage(socket, "{\"serverContent\":{\"turnComplete\":true}}")
        assertNull(listener.result)
        // New turn after turnComplete joins cleanly with a single space.
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Next sentence.\"}}} ")
        wsListener.onMessage(socket, "{\"serverContent\":{\"turnComplete\":true}}")
        assertEquals(listOf("Hello", "Hello world", "Hello world Next sentence."), listener.interims)

        // End activity: sends audioStreamEnd (not activityEnd) and flushes via short post-VAD grace timer.
        assertTrue(session.endActivity())
        socket.awaitSent(2) // setup + audioStreamEnd (no activityStart!)
        val kinds = socket.sent.map(::wireKind)
        assertFalse("AUDIO-modality model must not send activityStart", kinds.contains("activityStart"))
        assertTrue("AUDIO-modality model must send audioStreamEnd", kinds.contains("audioStreamEnd"))

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        assertEquals("Hello world Next sentence.", (listener.result as CloudLiveTerminal.Success).text)
        assertEquals(1, listener.terminals)
    }

    @Test fun `AUDIO-modality model fails with FINAL_TIMEOUT if speech arrived after last turnComplete and never finalized`() {
        val listener = RecordingListener()
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val session = GeminiCloudLiveTranscriptionClient(
            httpClient = client,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            model = GeminiCloudLiveTranscriptionClient.SECONDARY_MODEL,
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            finalTimeoutMs = 60,
            allowTestEndpoint = true,
        ).create(listener)

        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        session.startActivity()
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"partial words\"}}}")
        wsListener.onMessage(socket, "{\"serverContent\":{\"turnComplete\":true}}")
        // Send loud speech PCM after turnComplete so server VAD has unfinalized audio.
        val loudPcm = ByteArray(3200) { idx -> if (idx % 2 == 0) 0x00 else 0x10 }
        assertTrue(session.sendPcm(loudPcm, loudPcm.size))
        session.endActivity()

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.FINAL_TIMEOUT, (listener.result as CloudLiveTerminal.Failure).reason)
    }

    @Test fun `gemini-3_5-transcribe-live fails with FINAL_TIMEOUT instead of silently truncating buffered text`() {
        val listener = RecordingListener()
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val session = GeminiCloudLiveTranscriptionClient(
            httpClient = client,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            model = GeminiCloudLiveTranscriptionClient.DEFAULT_MODEL,
            mode = LiveTranscriptionMode.VERBATIM,
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            finalTimeoutMs = 60,
            allowTestEndpoint = true,
        ).create(listener)

        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        session.startActivity()
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"First sentence.\"}}}")
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Second sentence.\"}}}")
        assertNull("must not close early while still recording", listener.result)
        assertEquals(listOf("First sentence.", "First sentence. Second sentence."), listener.interims)

        session.endActivity()
        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.FINAL_TIMEOUT, (listener.result as CloudLiveTerminal.Failure).reason)
    }

    @Test fun `goAway fails cleanly with SESSION_LIMIT`() {
        val listener = RecordingListener()
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val session = factory(httpClient = client).create(listener)
        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        session.startActivity()
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")
        wsListener.onMessage(socket, "{\"goAway\":{\"timeLeft\":\"10s\"}}")

        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        val failure = listener.result as CloudLiveTerminal.Failure
        assertEquals(CloudLiveFailureReason.SESSION_LIMIT, failure.reason)
        assertTrue(failure.message.contains("goAway"))
    }

    @Test fun `stall watchdog triggers STREAM_STALLED on continuous speech with zero inbound frames and never on silence`() {
        // 1. Continuous loud speech with 0 inbound frames -> STREAM_STALLED.
        val speechListener = RecordingListener()
        val speechSocket = GatedWebSocket(gateOn = "\u0000never")
        val speechClient = FakeWebSocketClient(speechSocket)
        var clock1 = 1_000L
        val speechSession = GeminiCloudLiveTranscriptionClient(
            httpClient = speechClient,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            stallTimeoutMs = 1_000L,
            stallCheckIntervalMs = 20L,
            nowMs = { clock1 },
            allowTestEndpoint = true,
        ).create(speechListener)
        speechSession.connect()
        speechClient.socketListener!!.onOpen(speechSocket, upgradeResponse())
        speechSession.startActivity()
        speechClient.socketListener!!.onMessage(speechSocket, "{\"setupComplete\":{}}")

        val loudPcm = ByteArray(3200) { idx -> if (idx % 2 == 0) 0x00 else 0x12 }
        assertTrue(GeminiCloudLiveTranscriptionClient.hasSpeechEnergy(loudPcm, loudPcm.size))
        speechSession.sendPcm(loudPcm, loudPcm.size)
        clock1 += 1_200L
        speechSession.sendPcm(loudPcm, loudPcm.size)

        assertTrue(speechListener.done.await(5, TimeUnit.SECONDS))
        assertEquals(CloudLiveFailureReason.STREAM_STALLED, (speechListener.result as CloudLiveTerminal.Failure).reason)

        // 2. Pure silence MUST NOT trigger STREAM_STALLED.
        val silenceListener = RecordingListener()
        val silenceSocket = GatedWebSocket(gateOn = "\u0000never")
        val silenceClient = FakeWebSocketClient(silenceSocket)
        var clock2 = 1_000L
        val silenceSession = GeminiCloudLiveTranscriptionClient(
            httpClient = silenceClient,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            stallTimeoutMs = 100L,
            stallCheckIntervalMs = 20L,
            nowMs = { clock2 },
            allowTestEndpoint = true,
        ).create(silenceListener)
        silenceSession.connect()
        silenceClient.socketListener!!.onOpen(silenceSocket, upgradeResponse())
        silenceSession.startActivity()
        silenceClient.socketListener!!.onMessage(silenceSocket, "{\"setupComplete\":{}}")

        val silentPcm = ByteArray(3200)
        assertFalse(GeminiCloudLiveTranscriptionClient.hasSpeechEnergy(silentPcm, silentPcm.size))
        silenceSession.sendPcm(silentPcm, silentPcm.size)
        clock2 += 5_000L
        silenceSession.sendPcm(silentPcm, silentPcm.size)
        Thread.sleep(150)
        assertNull("Pure silence must never trigger STREAM_STALLED", silenceListener.result)
        silenceSession.cancel()
    }

    @Test fun `SMART mode preserves earlier paragraphs across new segments and interimInputTranscription keeps committed prefix`() {
        val listener = RecordingListener()
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val session = GeminiCloudLiveTranscriptionClient(
            httpClient = client,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            model = GeminiCloudLiveTranscriptionClient.DEFAULT_MODEL,
            mode = LiveTranscriptionMode.SMART,
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            allowTestEndpoint = true,
        ).create(listener)

        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        session.startActivity()
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")

        // First paragraph committed
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"First paragraph about the release schedule.\"}}}")
        // Cumulative re-edit of first paragraph replaces cleanly
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"First paragraph about the release schedule for Friday.\"}}}")
        // Interim for second paragraph retains first paragraph prefix instead of wiping it
        wsListener.onMessage(socket, "{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"Also tell Sarah\"}}}")
        // Second distinct paragraph appends instead of wiping first paragraph
        wsListener.onMessage(socket, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Also tell Sarah to verify the invoice.\"}}}")

        assertEquals(
            listOf(
                "First paragraph about the release schedule.",
                "First paragraph about the release schedule for Friday.",
                "First paragraph about the release schedule for Friday. Also tell Sarah",
                "First paragraph about the release schedule for Friday. Also tell Sarah to verify the invoice.",
            ),
            listener.interims,
        )
        session.cancel()
    }

    @Test fun `silent PCM keepalive sends after idle interval and stops after endActivity`() {
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val listener = RecordingListener()
        var now = 1_000L
        val session = GeminiCloudLiveTranscriptionClient(
            httpClient = client,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            keepaliveIntervalMs = 40,
            nowMs = { now },
            allowTestEndpoint = true,
        ).create(listener)

        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        session.startActivity()
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")
        socket.awaitSent(2) // setup + activityStart

        now += 50L
        socket.awaitSent(1) // silent PCM keepalive
        val keepaliveFrame = socket.sent.last()
        val audio = JSONObject(keepaliveFrame).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(3200, java.util.Base64.getDecoder().decode(audio.getString("data")).size)

        session.endActivity()
        val countAfterEnd = socket.sent.size
        now += 200L
        Thread.sleep(100)
        assertEquals("keepalive must stop after endActivity", countAfterEnd, socket.sent.size)
        session.cancel()
    }

    @Test fun `errorCode propagates from close code error frame and HTTP failure response`() {
        // 1. WebSocket close code (1008)
        val closeListener = RecordingListener()
        val closeSocket = GatedWebSocket(gateOn = "\u0000never")
        val closeClient = FakeWebSocketClient(closeSocket)
        val closeSession = factory(httpClient = closeClient).create(closeListener)
        closeSession.connect()
        closeClient.socketListener!!.onClosing(closeSocket, 1008, "policy violation")
        assertTrue(closeListener.done.await(5, TimeUnit.SECONDS))
        val closeFailure = closeListener.result as CloudLiveTerminal.Failure
        assertEquals(1008, closeFailure.errorCode)
        assertTrue(closeFailure.message.contains("1008"))

        // 2. JSON error frame code
        val errorListener = RecordingListener()
        scriptedSocket("{\"setupComplete\":{}}", "{\"error\":{\"code\":401,\"message\":\"invalid api key\"}}")
        val errorSession = factory().create(errorListener)
        errorSession.connect(); errorSession.startActivity()
        assertTrue(errorListener.done.await(5, TimeUnit.SECONDS))
        val errorFailure = errorListener.result as CloudLiveTerminal.Failure
        assertEquals(401, errorFailure.errorCode)

        // 3. HTTP handshake failure code (401)
        val httpListener = RecordingListener()
        val httpSocket = GatedWebSocket(gateOn = "\u0000never")
        val httpClient = FakeWebSocketClient(httpSocket)
        val httpSession = factory(httpClient = httpClient).create(httpListener)
        httpSession.connect()
        val response401 = Response.Builder()
            .request(Request.Builder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
        httpClient.socketListener!!.onFailure(httpSocket, java.io.IOException("unauthorized"), response401)
        assertTrue(httpListener.done.await(5, TimeUnit.SECONDS))
        assertEquals(401, (httpListener.result as CloudLiveTerminal.Failure).errorCode)
    }

    @Test fun `gemini-3_8-live accepts binary frames larger than 256KB up to 2MB`() {
        val listener = RecordingListener()
        val socket = GatedWebSocket(gateOn = "\u0000never")
        val client = FakeWebSocketClient(socket)
        val session = GeminiCloudLiveTranscriptionClient(
            httpClient = client,
            endpoint = server.url(GeminiCloudLiveTranscriptionClient.ENDPOINT_PATH),
            apiKey = "test-key",
            model = GeminiCloudLiveTranscriptionClient.SECONDARY_MODEL,
            callbackExecutor = Executor { it.run() },
            scheduler = scheduler,
            allowTestEndpoint = true,
        ).create(listener)

        session.connect()
        val wsListener = requireNotNull(client.socketListener)
        wsListener.onOpen(socket, upgradeResponse())
        session.startActivity()
        wsListener.onMessage(socket, "{\"setupComplete\":{}}")
        val largeAudioPayload = "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"data\":\"" +
            "A".repeat(300 * 1024) + "\"}}]}}}"
        wsListener.onMessage(socket, largeAudioPayload.encodeUtf8())
        assertNull("300KB audio frame must not fail gemini-3.8-live", listener.result)
        session.cancel()
    }

    private fun scriptedSocket(vararg events: String, delayMs: Long = 0, binary: Boolean = false): ServerScript {
        val script = ServerScript(events.toList(), delayMs, binary)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(script).build())
        return script
    }

    private open class ClosingWebSocketListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private class ServerScript(
        private val events: List<String>,
        private val delayMs: Long,
        private val binary: Boolean = false,
    ) : ClosingWebSocketListener() {
        private val received = CountDownLatch(1)
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Thread {
                if (delayMs > 0) Thread.sleep(delayMs)
                events.forEach { event ->
                    if (binary) webSocket.send(event.encodeUtf8()) else webSocket.send(event)
                }
            }.start()
        }
        override fun onMessage(webSocket: WebSocket, text: String) { received.countDown() }
        fun awaitMessages() { assertTrue(received.await(5, TimeUnit.SECONDS)) }
    }

    private class RecordingListener : CloudLiveTranscriptionListener {
        val interims = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        @Volatile var connectedCount = 0
        @Volatile var result: CloudLiveTerminal? = null
        @Volatile var terminals = 0
        override fun onConnected() { connectedCount++ }
        override fun onInterim(text: String, timing: CloudLiveTiming) { interims += text }
        override fun onTerminal(result: CloudLiveTerminal) { terminals++; this.result = result; done.countDown() }
    }

    private fun org.json.JSONArray.toStringList() = (0 until length()).map { getString(it) }
}
