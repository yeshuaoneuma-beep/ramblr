package com.trevornk.ramblr

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2DPipelineTest {

    class TestAccessibilityService : WhisperAccessibilityService()

    private lateinit var app: Application

    private class CapturingListener : RuntimeListener {
        val stageStatuses = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onRecordingStartRequested() {}
        override fun onRecordingStartFailed() {}
        override fun onRecordingStarted() {}
        override fun onEnterTranscribingUi() {}
        override fun onIdleUi() {}
        override fun onStreamingTeardown() {}
        override fun onStreamingPartial(text: String) {}
        override fun onStageStatus(statusText: String) { stageStatuses += statusText }
        override fun onUserMessage(message: String) { errors += message }
        override fun deliverText(
            text: String,
            rawText: String?,
            paidFallbackGroup: CleanupStepGroup?,
            cleanupError: String?,
            feedbackDurationMs: Long,
        ) {
            delivered += text
        }
        override fun foregroundPackageName(): String? = null
    }

    private class TestRecordingEngine(
        cacheDir: File,
        private val stateMachine: RecordingStateMachine,
    ) : RecordingEngine(cacheDir, stateMachine) {
        var onFinished: ((Result) -> Unit)? = null
        override fun start(onFinished: (Result) -> Unit, onChunk: (ByteArray, Int) -> Unit): Boolean {
            if (!stateMachine.tryStartRecording()) return false
            this.onFinished = onFinished
            return true
        }
        override fun awaitTeardown(timeoutMs: Long): Boolean = true
        override fun isReaderTeardownPending(): Boolean = false

        fun finishWithPcm(pcmFile: File) {
            stateMachine.tryStartTranscribing()
            onFinished?.invoke(Result(pcmFile = pcmFile, bytesRecorded = pcmFile.length(), discarded = false, stopReason = StopReason.USER))
        }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        DiagnosticErrorLog.clear(app)
        File(app.filesDir, "models").deleteRecursively()
        WhisperAccessibilityService.setMainActivityForeground(false)
        WhisperAccessibilityService.setOverlayForceVisibleOverride(false)
        ActiveDictationHostRegistry.resetForTest()
    }

    @After fun tearDown() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.values().forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        File(app.filesDir, "models").deleteRecursively()
        ActiveDictationHostRegistry.resetForTest()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun installCatalogTranscriptionModel(): Model {
        val model = MODEL_CATALOG.first()
        val dir = ModelDownloader.modelDir(app, model)
        dir.mkdirs()
        ModelDownloader.completeMarker(dir).createNewFile()
        return model
    }

    private fun usablePcmFile(): File = File.createTempFile("p2d_", ".pcm", app.cacheDir).apply {
        writeBytes(ByteArray(320 * 40)) // > 300ms minimum duration floor
    }

    // ---- Fix 5: Blank model_name detection & persistence ----------------------------------------

    @Test fun `TranscriptionActivity subtitle and MainActivity refresh resolve and persist first installed catalog model when model_name is blank`() {
        val prefs = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_local", true).remove("model_name").commit()

        assertEquals("Local · no model installed yet", TranscriptionActivity.subtitle(app))

        val installed = installCatalogTranscriptionModel()
        assertEquals("Local · ${installed.name}", TranscriptionActivity.subtitle(app))
        assertEquals("", prefs.getString("model_name", ""))

        Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(installed.archive, prefs.getString("model_name", ""))
    }

    // ---- Fix 7: Cloud STT eligibility gating AAC encoder & connection warmup --------------------

    @Test fun `isCloudSttEligible and hostsToWarmForDictation gate cloud upload and warmup properly`() {
        val cloudChain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", transcriptionModel = "gpt-transcribe"),
                ProviderChainEntry(ProviderKind.ANTHROPIC, "claude-haiku-4-5"),
            )
        )
        val configured: (ProviderChainEntry) -> Boolean = { true }

        // Local-only mode (no cloud fallback): not cloud STT eligible, warms zero STT hosts; and if PostProcessing off, warms zero hosts at all.
        assertFalse(DictationRuntime.isCloudSttEligible(useLocal = true, allowCloudFallback = false, chain = cloudChain, isConfigured = configured))
        assertTrue(
            DictationRuntime.hostsToWarmForDictation(
                chain = cloudChain,
                useLocal = true,
                allowCloudFallback = false,
                postProcessingEnabled = false,
                cloudCleanupEnabled = true,
                isConfigured = configured,
            ).isEmpty()
        )
        // With cleanup on in local mode, only cleanup hosts are warmed (Anthropic/OpenAI cleanup), still not when PostProcessing is off.
        assertEquals(
            setOf("api.openai.com", "api.anthropic.com"),
            DictationRuntime.hostsToWarmForDictation(
                chain = cloudChain,
                useLocal = true,
                allowCloudFallback = false,
                postProcessingEnabled = true,
                cloudCleanupEnabled = true,
                isConfigured = configured,
            ),
        )

        // Cloud STT eligible when useLocal = false (and configured) OR allowCloudFallback = true.
        assertTrue(DictationRuntime.isCloudSttEligible(useLocal = false, allowCloudFallback = false, chain = cloudChain, isConfigured = configured))
        assertTrue(DictationRuntime.isCloudSttEligible(useLocal = true, allowCloudFallback = true, chain = cloudChain, isConfigured = configured))
        assertFalse(DictationRuntime.isCloudSttEligible(useLocal = false, allowCloudFallback = false, chain = cloudChain, isConfigured = { false }))
    }

    @Test fun `startRecording skips AacEncoderSession allocation in local-only mode`() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("use_local", true)
            .commit()
        DictationModeToggle.setAllowCloudFallback(app, false)
        CompressedUploadToggle.setEnabled(app, true)

        val listener = CapturingListener()
        var engine: TestRecordingEngine? = null
        val rt = DictationRuntime(
            context = app,
            listener = listener,
            leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
            engineFactory = { dir, sm -> TestRecordingEngine(dir, sm).also { engine = it } },
        )
        try {
            rt.onTap()
            assertEquals(RecordingStateMachine.State.RECORDING, rt.currentState())
            val aacField = DictationRuntime::class.java.getDeclaredField("aacEncoderSession").apply { isAccessible = true }
            assertNull("local-only mode must never allocate AacEncoderSession", aacField.get(rt))
            assertNotNull(engine)
        } finally {
            rt.shutdown()
        }
    }

    // ---- Fix 8 + Fix 1: DictationRuntime live stage status on retry/failover & happy path silent --

    @Test fun `happy path 1st attempt emits zero stageStatus callbacks while STT busy retry and cloud failover emit expected messages`() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/v1").toString().trimEnd('/')
            val prefs = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("use_local", false).commit()
            PostProcessingToggle.setEnabled(app, false)
            DictationModeToggle.setAllowLocalFallback(app, false)
            ProviderChainStore.save(
                app,
                ProviderChain(
                    listOf(
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"),
                        ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash-lite", baseUrlOverride = base, transcriptionModel = "gemini-3.1-flash-lite"),
                    )
                ),
            )
            ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k-oai")
            ProviderCredentialStore.setLegacyByKind(app, ProviderKind.GEMINI, "k-gem")

            // 1. Happy path: 1st OpenAI attempt succeeds -> stageStatuses must remain empty.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder().body("""{"text":"happy transcript"}""").build()
            }
            val happyListener = CapturingListener()
            var happyEngine: TestRecordingEngine? = null
            val happyRt = DictationRuntime(
                context = app,
                listener = happyListener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                transcriptionRetryBackoffMs = 1L,
                engineFactory = { dir, sm -> TestRecordingEngine(dir, sm).also { happyEngine = it } },
            )
            happyRt.onTap()
            happyRt.stopAndTranscribe()
            happyEngine!!.finishWithPcm(usablePcmFile())
            val d1 = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < d1 && happyRt.currentState() != RecordingStateMachine.State.IDLE) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            assertEquals(listOf("happy transcript"), happyListener.delivered)
            assertTrue("1st attempt happy path must emit zero stage status", happyListener.stageStatuses.isEmpty())
            happyRt.shutdown()

            // 2. Single provider [OPENAI] returns 503 then 200 -> emits "OpenAI busy — retrying…".
            ProviderChainStore.save(
                app,
                ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"))),
            )
            var singleCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (++singleCalls == 1) {
                        MockResponse.Builder().code(503).body("""{"error":{"message":"busy"}}""").build()
                    } else {
                        MockResponse.Builder().body("""{"text":"retried openai ok"}""").build()
                    }
            }
            val retryListener = CapturingListener()
            var retryEngine: TestRecordingEngine? = null
            val retryRt = DictationRuntime(
                context = app,
                listener = retryListener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                transcriptionRetryBackoffMs = 1L,
                engineFactory = { dir, sm -> TestRecordingEngine(dir, sm).also { retryEngine = it } },
            )
            retryRt.onTap()
            retryRt.stopAndTranscribe()
            retryEngine!!.finishWithPcm(usablePcmFile())
            val dRetry = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < dRetry && retryRt.currentState() != RecordingStateMachine.State.IDLE) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("retried openai ok"), retryListener.delivered)
            assertEquals(listOf("OpenAI busy — retrying…"), retryListener.stageStatuses)
            retryRt.shutdown()

            // 3. Two providers [OPENAI, GEMINI]: OpenAI 503 -> fails over to Gemini ("OpenAI failed — trying Gemini…") -> succeeds.
            ProviderChainStore.save(
                app,
                ProviderChain(
                    listOf(
                        ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"),
                        ProviderChainEntry(ProviderKind.GEMINI, "gemini-3.5-flash", baseUrlOverride = base, transcriptionModel = "gemini-3.5-flash"),
                    )
                ),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.target.contains("/audio/transcriptions")) {
                        MockResponse.Builder().code(503).body("""{"error":{"message":"busy"}}""").build()
                    } else {
                        MockResponse.Builder().body(
                            """{"candidates":[{"content":{"parts":[{"text":"gemini rescued"}]},"finishReason":"STOP"}]}"""
                        ).build()
                    }
            }
            val failoverListener = CapturingListener()
            var failoverEngine: TestRecordingEngine? = null
            val failoverRt = DictationRuntime(
                context = app,
                listener = failoverListener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                transcriptionRetryBackoffMs = 1L,
                engineFactory = { dir, sm -> TestRecordingEngine(dir, sm).also { failoverEngine = it } },
            )
            failoverRt.onTap()
            failoverRt.stopAndTranscribe()
            failoverEngine!!.finishWithPcm(usablePcmFile())
            val d2 = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < d2 && failoverRt.currentState() != RecordingStateMachine.State.IDLE) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("gemini rescued"), failoverListener.delivered)
            assertEquals(listOf("OpenAI failed — trying Gemini…"), failoverListener.stageStatuses)
            // Scrubbed 503 error recorded in DiagnosticErrorLog too!
            assertTrue(DiagnosticErrorLog.readRecent(app).any { it.contains("HTTP 503") })
            failoverRt.shutdown()
        } finally {
            server.close()
        }
    }

    @Test fun `cloud STT failure falls over to installed local model and emits Cloud unavailable trying on-device`() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/v1").toString().trimEnd('/')
            installCatalogTranscriptionModel()
            val prefs = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("use_local", false).commit()
            PostProcessingToggle.setEnabled(app, false)
            DictationModeToggle.setAllowLocalFallback(app, true)
            ProviderChainStore.save(
                app,
                ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", baseUrlOverride = base, transcriptionModel = "gpt-transcribe"))),
            )
            ProviderCredentialStore.setLegacyByKind(app, ProviderKind.OPENAI, "k-oai")

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder().code(400).body("""{"error":{"message":"bad request"}}""").build()
            }

            val listener = CapturingListener()
            var engine: TestRecordingEngine? = null
            val rt = DictationRuntime(
                context = app,
                listener = listener,
                leaseRegistry = InMemoryDictationSessionLeaseRegistry(),
                transcriptionRetryBackoffMs = 1L,
                transcriberHooks = TranscriberHooks(
                    createLocal = { _, _ -> allocateWithoutConstructor(LocalTranscriber::class.java) },
                    releaseLocal = {},
                    decodeLocal = { _, _, _ -> "local fallback transcript" },
                    runOffMain = { work -> Thread(work).start() },
                ),
                engineFactory = { dir, sm -> TestRecordingEngine(dir, sm).also { engine = it } },
            )
            rt.onTap()
            rt.stopAndTranscribe()
            engine!!.finishWithPcm(usablePcmFile())
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && rt.currentState() != RecordingStateMachine.State.IDLE) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("local fallback transcript"), listener.delivered)
            assertEquals(listOf("Cloud unavailable — trying on-device…"), listener.stageStatuses)
            rt.shutdown()
        } finally {
            server.close()
        }
    }

    // ---- Fix 8: Accessibility overlay pill visibility + BLASTSyncEngine alpha-only invariant ----

    @Test fun `WhisperAccessibilityService displays non-touchable stageStatus pill and clears alpha-only on idle`() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val service = Robolectric.buildService(TestAccessibilityService::class.java, null).create().get()
        try {
            shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
            shadowOf(service.getSystemService(PowerManager::class.java)).turnScreenOn(true)
            WhisperAccessibilityService::class.java.getDeclaredMethod("showOverlay")
                .apply { isAccessible = true }.invoke(service)

            val listener = WhisperAccessibilityService::class.java.getDeclaredField("runtimeListener")
                .apply { isAccessible = true }.get(service) as RuntimeListener
            val feedbackView = WhisperAccessibilityService::class.java.getDeclaredField("feedbackView")
                .apply { isAccessible = true }.get(service) as TextView
            val feedbackParams = WhisperAccessibilityService::class.java.getDeclaredField("feedbackLayoutParams")
                .apply { isAccessible = true }.get(service) as WindowManager.LayoutParams

            listener.onStageStatus("OpenAI busy — retrying…")
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("OpenAI busy — retrying…", feedbackView.text.toString())
            assertEquals(View.VISIBLE, feedbackView.visibility)
            assertEquals(1f, feedbackView.alpha, 0.001f)
            assertTrue(feedbackParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)

            listener.onIdleUi()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("BLASTSyncEngine invariant: pill must stay View.VISIBLE", View.VISIBLE, feedbackView.visibility)
            assertEquals(0f, feedbackView.alpha, 0.001f)
        } finally {
            service.onDestroy()
            Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }
}
