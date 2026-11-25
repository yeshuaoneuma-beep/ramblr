package com.trevornk.ramblr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #285: runs ONE audio-file job to completion, on the calling (worker) thread:
 * import -> decode to 16 kHz mono PCM -> chunk -> transcribe each chunk (local or cloud, per the
 * user's existing settings) -> optional cleanup -> History. It does not own the process-keep-alive
 * (that is [AudioTranscriptionService]) and never logs transcript text.
 *
 * Settings are the live-dictation ones, read the same way: local-vs-cloud (`use_local`), the
 * provider chain and its fall-back toggles, the Dictation language, the personal vocabulary, and
 * the cleanup toggle + chain.
 */
internal class AudioJobRunner(
    private val context: Context,
    private val store: AudioJobStore,
    private val isCancelled: (String) -> Boolean,
    private val onStatus: (AudioJob) -> Unit,
    private val maxImportBytes: Long = MAX_IMPORT_BYTES,
    private val decodeAudio: (source: File, pcm: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit) -> AudioFileDecoder.Result = { source, pcm, cancelled, progress ->
        AudioFileDecoder.decode(source, pcm, cancelled, progress)
    },
    private val runStage1Stt: ((id: String, pcm: File, decoded: AudioFileDecoder.Result, workDir: File) -> String)? = null,
    private val runCleanup: ((id: String, raw: String) -> CleanupOutcome)? = null,
    private val persistHistory: (DictationHistoryEntry) -> Unit = { entry ->
        DictationHistoryStore.forContext(context).upsert(entry)
    },
) {
    private val filesDir: File get() = context.filesDir
    private val inFlight = InFlightCall()

    /** Aborts the network call currently in flight (the cancel flag stops the rest). */
    fun abortInFlight() = inFlight.cancel()

    private class Stop(val failure: JobFailure?) : Exception()

    fun run(initial: AudioJob) {
        val id = initial.id
        val workDir = AudioJobFiles.workDir(context.cacheDir)
        val pcm = File(workDir, "$id.pcm")
        inFlight.beginWork()
        try {
            if (isCancelled(id)) throw Stop(null)
            val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            val historyOn = prefs.getBoolean("dictation_history_enabled", true)

            val existingCheckpoint = AudioJobFiles.readRawCheckpoint(filesDir, id)
            val checkpoint = if (existingCheckpoint != null) {
                if (!initial.hasRawCheckpoint) {
                    store.mutate(id) { it.copy(hasRawCheckpoint = true) }
                }
                existingCheckpoint
            } else {
                if (!initial.hasLocalAudio) importSource(initial)

                val source = AudioJobFiles.sourceFile(filesDir, id)
                if (!source.exists()) throw Stop(JobFailure.UNREADABLE)

                event(id, JobEvent.DecodeStarted)
                var lastPercent = -1
                val decoded = try {
                    decodeAudio(source, pcm, { isCancelled(id) }) { f ->
                        val p = (f * 100).toInt()
                        if (p != lastPercent) { lastPercent = p; event(id, JobEvent.DecodeProgress(p)) }
                    }
                } catch (e: AudioDecodeException) {
                    if (isCancelled(id)) throw Stop(null)
                    throw Stop(if (e.cause is IOException && isNoSpace(e.cause)) JobFailure.STORAGE else JobFailure.UNREADABLE)
                }
                if (isBelowMinimumDuration(decoded.pcmBytes, AudioChunkPlanner.SAMPLE_RATE)) throw Stop(JobFailure.NO_SPEECH)

                val raw = if (runStage1Stt != null) {
                    event(id, JobEvent.TranscribeStarted(1, decoded.durationMs))
                    runStage1Stt.invoke(id, pcm, decoded, workDir)
                } else {
                    transcribeStage1(id, pcm, decoded, workDir)
                }
                pcm.delete()
                if (raw.isBlank() || isJunkTranscript(raw)) throw Stop(JobFailure.NO_SPEECH)
                if (isCancelled(id)) throw Stop(null)

                val checkpointTs = if (historyOn) System.currentTimeMillis() else null
                try {
                    AudioJobFiles.writeRawCheckpointAtomically(filesDir, id, raw, checkpointTs)
                } catch (e: IOException) {
                    throw Stop(if (isNoSpace(e)) JobFailure.STORAGE else JobFailure.TRANSCRIPTION_FAILED)
                }
                store.mutate(id) { it.copy(hasRawCheckpoint = true) }
                if (historyOn && checkpointTs != null) {
                    persistHistory(DictationHistoryEntry(checkpointTs, raw, cleanedText = null))
                }
                RawTranscriptCheckpoint(raw, checkpointTs)
            }
            if (isCancelled(id)) throw Stop(null)

            // ---- cleanup ------------------------------------------------------------------------
            val cleanup = if (runCleanup != null) {
                event(id, JobEvent.CleaningStarted)
                runCleanup.invoke(id, checkpoint.rawText)
            } else {
                cleanUp(id, checkpoint.rawText)
            }

            // ---- deliver ------------------------------------------------------------------------
            val finalText = cleanup.cleaned ?: checkpoint.rawText
            val finalTimestamp = checkpoint.historyTimestamp ?: System.currentTimeMillis()
            if (historyOn) {
                persistHistory(DictationHistoryEntry(finalTimestamp, checkpoint.rawText, cleanup.cleaned))
            } else {
                // History is the one place a job's text lives; with it off, hand the text over now.
                ClipboardUtil.copy(context, finalText)
            }
            val done = store.update(id, JobEvent.Completed(
                if (historyOn) finalTimestamp else null, cleanup.failed, copied = !historyOn,
                sectionsCleaned = if (cleanup.tally.partlyCleaned) cleanup.tally.cleaned else 0,
                sectionsTotal = if (cleanup.tally.partlyCleaned) cleanup.tally.total else 0,
            ))
            if (done?.status == JobStatus.DONE) {
                AudioJobFiles.deleteRawCheckpoint(filesDir, id)
                deleteAudio(id)
            }
            done?.let { onStatus(it) }
        } catch (s: Stop) {
            fail(id, s.failure)
        } catch (e: Throwable) {
            Log.e(TAG, "Audio job crashed: ${e.javaClass.simpleName}")
            fail(id, JobFailure.TRANSCRIPTION_FAILED)
        } finally {
            pcm.delete()
            File(workDir, "$id.tmp").delete()
        }
    }

    private fun transcribeStage1(
        id: String,
        pcm: File,
        decoded: AudioFileDecoder.Result,
        workDir: File,
    ): String {
        var local: LocalTranscriber? = null
        try {
            // ---- routes ------------------------------------------------------------------------
            val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            val chain = ProviderChainStore.load(context)
            val localModel = resolveLocalModelName(prefs.getString("model_name", "") ?: "")
            val vadFile = ModelDownloader.vadModelFile(context, SILERO_VAD_MODEL)
            val plan = AudioTranscriptionRoutes.plan(
                useLocal = prefs.getBoolean("use_local", true),
                localReady = localModel != null,
                allowCloudFallback = DictationModeToggle.allowCloudFallback(context),
                candidates = ProviderChainRuntime.transcriptionCandidates(
                    chain = chain,
                    allowLocalFallback = DictationModeToggle.allowLocalFallback(context),
                    localModelAvailable = localModel != null,
                ),
                hasCredential = { resolveEntryCredential(it).isNotBlank() },
            )
            if (plan.routes.isEmpty()) throw Stop(plan.unavailable ?: JobFailure.NO_PROVIDER)
            if (vadFile == null && plan.routes.any { it is AudioRoute.Local }) {
                // Same as live dictation (#139): fetch the ~1 MB VAD model for next time; this job
                // decodes unsegmented in short chunks instead.
                runCatching { ModelDownloadWorker.enqueue(context, SILERO_VAD_MODEL) }
            }

            // ---- chunk + transcribe -------------------------------------------------------------
            val totalSamples = decoded.pcmBytes / 2
            val maxChunk = AudioTranscriptionRoutes.maxChunkSamples(plan, vadAvailable = vadFile != null)
            val energies = if (totalSamples > maxChunk) AudioChunkPlanner.scanEnergies(pcm) else null
            val chunks = AudioChunkPlanner.plan(totalSamples, maxChunk, energies, AudioTranscriptionRoutes.searchBackSamples(maxChunk))
            event(id, JobEvent.TranscribeStarted(chunks.size, decoded.durationMs))

            val language = DictationLanguage.languageOrNull(context)
            val vocabulary = VocabularyTerms.parse(prefs.getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
            val texts = ArrayList<String>(chunks.size)
            val disqualifiedAccounts = mutableSetOf<String>()
            var routeIndex = 0
            for (chunk in chunks) {
                if (isCancelled(id)) throw Stop(null)
                val chunkFile = if (chunks.size == 1) pcm else File(workDir, "${id}_${chunk.index}.pcm").also {
                    try { AudioChunkPlanner.extract(pcm, chunk, it) } catch (e: IOException) {
                        it.delete(); throw Stop(if (isNoSpace(e)) JobFailure.STORAGE else JobFailure.UNREADABLE)
                    }
                }
                try {
                    var routesTried = 0
                    val r = ChunkWalk.run(plan.routes.size, routeIndex) { i ->
                        routesTried++
                        val hasNextRoute = routesTried < plan.routes.size
                        if (isCancelled(id)) return@run ChunkAttempt.Cancelled
                        when (val route = plan.routes[i]) {
                            AudioRoute.Local -> {
                                val t = local ?: LocalTranscriber.create(context, localModel!!).also { local = it }
                                    ?: return@run ChunkAttempt.Error()
                                try {
                                    ChunkAttempt.Text(transcribeLocalChunk(t, chunkFile, vadFile))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Local chunk failed: ${e.javaClass.simpleName}")
                                    ChunkAttempt.Error()
                                }
                            }
                            is AudioRoute.Cloud -> transcribeCloudChunk(
                                id = id,
                                entry = route.entry,
                                file = chunkFile,
                                language = language,
                                vocabulary = vocabulary,
                                hasNextRoute = hasNextRoute,
                                disqualifiedAccounts = disqualifiedAccounts,
                            )
                        }
                    }
                    if (r.cancelled) throw Stop(null)
                    texts += r.text ?: throw Stop(JobFailure.TRANSCRIPTION_FAILED)
                    routeIndex = r.routeIndex
                } finally {
                    if (chunkFile !== pcm) chunkFile.delete()
                }
                event(id, JobEvent.ChunkDone(chunk.index))
            }
            // Free the (large) on-device ASR model before cleanup can load a local LLM.
            local?.release(); local = null
            return SegmentedTranscript.join(texts)
        } finally {
            runCatching { local?.release() }
        }
    }

    private fun fail(id: String, failure: JobFailure?) {
        val job = if (failure == null || isCancelled(id)) {
            val cancelled = store.update(id, JobEvent.Cancel)
            // A cancelled import/decode of a user's own file leaves nothing behind; a recorded
            // note went back to SAVED (its audio is the only copy) and is kept.
            if (cancelled != null && !AudioJobMachine.keepsAudio(cancelled)) deleteAudio(id)
            if (cancelled != null && !AudioJobMachine.keepsRawCheckpoint(cancelled)) {
                AudioJobFiles.deleteRawCheckpoint(filesDir, id)
            }
            cancelled
        } else {
            store.update(id, JobEvent.Failed(failure))
        }
        job?.let { onStatus(it) }
    }

    private fun event(id: String, e: JobEvent) {
        store.update(id, e)?.let { onStatus(it) }
    }

    private fun deleteAudio(id: String) {
        AudioJobFiles.sourceFile(filesDir, id).delete()
    }

    // ---- import ---------------------------------------------------------------------------------

    private fun importSource(job: AudioJob) {
        val id = job.id
        val uri = job.sourceUri?.let { Uri.parse(it) } ?: throw Stop(JobFailure.UNREADABLE)
        event(id, JobEvent.ImportStarted)
        val dest = AudioJobFiles.sourceFile(filesDir, id)
        val tmp = File(dest.parentFile, "${dest.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw Stop(JobFailure.UNREADABLE)
            input.use { src ->
                tmp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        if (isCancelled(id)) throw Stop(null)
                        val n = src.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > maxImportBytes) throw Stop(JobFailure.TOO_LARGE)
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() == 0L) throw Stop(JobFailure.UNREADABLE)
            if (!tmp.renameTo(dest)) throw Stop(JobFailure.STORAGE)
        } catch (s: Stop) {
            tmp.delete(); throw s
        } catch (e: SecurityException) {
            tmp.delete(); throw Stop(JobFailure.UNREADABLE)
        } catch (e: IOException) {
            tmp.delete(); throw Stop(if (isNoSpace(e)) JobFailure.STORAGE else JobFailure.UNREADABLE)
        }
        store.mutate(id) { it.copy(hasLocalAudio = true, sourceUri = null) }
    }

    private fun isNoSpace(e: Throwable?): Boolean {
        val m = e?.message?.lowercase() ?: return false
        return "enospc" in m || "no space" in m
    }

    // ---- transcription --------------------------------------------------------------------------

    private fun resolveLocalModelName(configured: String): String? {
        if (configured.isNotBlank()) {
            return configured.takeIf { ModelDownloader.isInstalledDir(File(filesDir, "models/$it")) }
        }
        return LocalTranscriber.availableModels(context).firstOrNull()
    }

    private fun transcribeLocalChunk(t: LocalTranscriber, file: File, vadFile: File?): String {
        val vad = vadFile?.let { SherpaVadHandle.create(it) }
        return try {
            if (vad != null) t.transcribeSegmented(file, vad, AudioChunkPlanner.SAMPLE_RATE)
            else t.transcribe(PcmFileBuffer.readAsFloatArray(file), AudioChunkPlanner.SAMPLE_RATE)
        } finally {
            vad?.close()
        }
    }

    private fun resolveEntryCredential(entry: ProviderChainEntry): String =
        entry.vaultKeyId?.takeIf { it.isNotBlank() }?.let { ApiKeyVaultStore.resolveSecret(context, it) }?.ifBlank { null }
            ?: ProviderCredentialStore.getOrLegacy(context, entry)

    private fun transcribeCloudChunk(
        id: String,
        entry: ProviderChainEntry,
        file: File,
        language: String?,
        vocabulary: List<String>,
        hasNextRoute: Boolean = false,
        disqualifiedAccounts: MutableSet<String> = mutableSetOf(),
    ): ChunkAttempt {
        val accountKey = accountKeyFor(entry)
        if (accountKey != null && accountKey in disqualifiedAccounts) {
            return ChunkAttempt.Error(retryable = false)
        }
        val apiKey = resolveEntryCredential(entry)
        if (entry.kind == ProviderKind.GEMINI) {
            val geminiModel = entry.transcriptionModel?.ifBlank { null } ?: GeminiTranscriberClient.DEFAULT_MODEL
            if (!GeminiTranscriberClient.supportsAudioTranscription(geminiModel)) {
                return ChunkAttempt.Error(retryable = false)
            }
        }
        val slotAttempts = entry.sttAttempts.coerceIn(
            AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
            AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS,
        )
        val effectiveMaxAttempts = if (slotAttempts > 1) {
            slotAttempts
        } else if (!hasNextRoute) {
            2
        } else {
            1
        }
        val effectiveHasNextRoute = if (slotAttempts > 1) false else hasNextRoute
        for (attempt in 0 until effectiveMaxAttempts) {
            if (isCancelled(id)) return ChunkAttempt.Cancelled
            val latch = CountDownLatch(1)
            var text: String? = null
            var noSpeech = false
            var errorKind = CloudErrorKind.TEMPORARY_BUSY
            var httpCode: Int? = null
            var retryAfterMs: Long? = null
            val cb: (String?, String?, CloudErrorKind, Int?, Long?) -> Unit = { transcript, error, kind, code, retryAfter ->
                text = transcript?.takeIf(String::isNotBlank)
                noSpeech = isNoSpeechTranscript(transcript, error)
                errorKind = kind
                httpCode = code
                retryAfterMs = retryAfter
                latch.countDown()
            }
            when (entry.kind) {
                ProviderKind.OPENAI -> TranscriberClient.transcribe(
                    file, apiKey, inFlight,
                    baseUrl = entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL,
                    model = entry.transcriptionModel?.ifBlank { null } ?: TranscriberClient.DEFAULT_MODEL,
                    vocabularyTerms = vocabulary,
                    language = language,
                ) { cb(it.text, it.error, it.errorKind, it.httpCode, it.retryAfterMs) }
                ProviderKind.GEMINI -> {
                    val geminiModel = entry.transcriptionModel?.ifBlank { null } ?: GeminiTranscriberClient.DEFAULT_MODEL
                    val geminiEndpoint = entry.baseUrlOverride?.takeIf { it.isNotBlank() }
                        ?.let { GeminiCleanupProvider.endpointUrl(geminiModel, it) }
                        ?: GeminiCleanupProvider.endpointUrl(geminiModel)
                    GeminiTranscriberClient.transcribe(
                        file, apiKey,
                        geminiModel,
                        inFlight,
                        vocabularyTerms = vocabulary,
                        language = language,
                        endpoint = geminiEndpoint,
                        retryDelaysMs = emptyList(),
                    ) { cb(it.text, it.error, it.errorKind, it.httpCode, it.retryAfterMs) }
                }
                else -> return ChunkAttempt.Error(retryable = false)
            }
            if (!latch.await(NetworkClients.CALL_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS)) {
                inFlight.cancel()
                errorKind = CloudErrorKind.TEMPORARY_BUSY
                httpCode = null
                retryAfterMs = null
            }
            if (isCancelled(id)) return ChunkAttempt.Cancelled
            text?.let { return ChunkAttempt.Text(it) }
            // #306: a silent chunk is empty text, not a failed call -- retrying (or walking to the
            // next route) cannot find speech in it, and SegmentedTranscript.join drops it.
            if (noSpeech) return ChunkAttempt.Text("")
            if (CloudErrorClassifier.isAccountDeadError(httpCode)) {
                if (accountKey != null) disqualifiedAccounts += accountKey
                return ChunkAttempt.Error(retryable = false)
            }
            if (!shouldRetryCloudChunk(attempt, errorKind, httpCode, retryAfterMs, effectiveHasNextRoute, effectiveMaxAttempts)) {
                return ChunkAttempt.Error(retryable = errorKind == CloudErrorKind.TEMPORARY_BUSY)
            }
            val delayMs = cloudChunkRetryDelayMs(errorKind, httpCode, retryAfterMs)
            if (delayMs > 0L) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return ChunkAttempt.Cancelled }
            }
        }
        return ChunkAttempt.Error()
    }

    // ---- cleanup --------------------------------------------------------------------------------

    internal class CleanupOutcome(val cleaned: String?, val failed: Boolean, val tally: CleanupSectionTally = CleanupSectionTally())

    /** Same toggle, chain, prompt and vocabulary as live dictation; applied piece by piece because
     *  a long transcript is far beyond a cleanup model's context window. */
    private fun cleanUp(id: String, raw: String): CleanupOutcome {
        if (!PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(context))) return CleanupOutcome(null, false)
        val selectedLocalModel = LocalCleanupProvider.selectedModel(context)
        val localCleanupAvailable = ModelDownloader.localCleanupModelFile(context, selectedLocalModel) != null
        val chain = ProviderChainRuntime.effectiveChainForCleanup(
            chain = ProviderChainStore.load(context),
            cloudEnabled = CloudFeatureToggle.cleanupEnabled(context),
            allowLocalFallback = DictationModeToggle.allowLocalFallback(context),
            localModelAvailable = localCleanupAvailable,
            localModelArchive = selectedLocalModel.archive,
        )
        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(chain)
        if (waterfall.steps.isEmpty()) return CleanupOutcome(null, false)

        event(id, JobEvent.CleaningStarted)
        val prefs = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        val vocabulary = VocabularyTerms.parse(prefs.getString("custom_vocabulary_terms", VocabularyTerms.DEFAULT_SERIALIZED))
        val savedPrompt = PostProcessor.effectiveSavedPrompt(prefs.getString("post_processing_prompt", null), prefs.getString("cleanup_style", null))
        val prompt = PostProcessor.withKeepLanguage(PostProcessor.interpolateVocabulary(savedPrompt, vocabulary))
        val cursor = CleanupWaterfallCursor()

        val tally = CleanupSectionTally()
        val localPrompt = LocalCleanupProvider.selectedSystemPrompt(context)
        val localTerminal = waterfall.steps.lastOrNull()?.group == CleanupStepGroup.LOCAL_LLM
        val out = ArrayList<String>()
        // Pieces for a local chain are split into model-sized chunks by the executor itself (#325);
        // this only bounds how much one waterfall run (and its latch) covers.
        val pieceChars = TranscriptChunker.pieceCharsFor(waterfall)
        for (piece in TranscriptChunker.split(raw, pieceChars)) {
            if (isCancelled(id)) throw Stop(null)
            val latch = CountDownLatch(1)
            var result: PostProcessor.Result? = null
            PostProcessor.processProviderChain(
                text = piece,
                prompt = prompt,
                chain = chain,
                cursor = cursor,
                cancelHolder = inFlight,
                credentialLookup = { kind -> ProviderCredentialStore.getLegacyByKind(context, kind) },
                entryCredentialLookup = { entryId -> ProviderCredentialStore.get(context, entryId) },
                vaultCredentialLookup = { vaultKeyId -> ApiKeyVaultStore.resolveSecret(context, vaultKeyId) },
                localModelPath = { ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context))?.absolutePath },
                localPrompt = localPrompt,
                localVocabulary = vocabulary,
                temperatureCacheContext = context.applicationContext,
                localStepBudgetMs = TranscriptChunker.localStepBudgetMsFor(waterfall),
            ) { r -> result = r; latch.countDown() }
            if (!latch.await(TranscriptChunker.pieceTimeoutSecondsFor(waterfall), TimeUnit.SECONDS)) inFlight.cancel()
            if (isCancelled(id)) throw Stop(null)
            val cleaned = result?.text?.takeIf { it.isNotBlank() }
            // A piece is cleaned, partly cleaned (a chunked on-device run that left some chunks
            // raw, #325 -- its text is the cleaned+raw mix) or raw. The tally keeps the section
            // counts so the job can say "partly cleaned" instead of "cleanup failed".
            if (cleaned != null) {
                out += cleaned
                tally.pieceCleaned(result?.sections)
            } else {
                out += piece
                // A raw piece would have been cleaned chunk by chunk on a terminal on-device step.
                tally.pieceRaw(if (localTerminal) LocalCleanupChunker.split(piece, LocalCleanupChunker.maxChunkChars(localPrompt)).size else 1)
            }
        }
        // Nothing improved: report the raw transcript honestly rather than a "cleaned" copy of it.
        return if (tally.cleaned > 0) CleanupOutcome(out.joinToString("\n\n"), failed = false, tally = tally) else CleanupOutcome(null, true, tally)
    }

    companion object {
        private const val TAG = "AudioJob"

        internal fun accountKeyFor(entry: ProviderChainEntry): String? = when {
            entry.kind == ProviderKind.LOCAL -> null
            !entry.vaultKeyId.isNullOrBlank() -> "vault:${entry.vaultKeyId}"
            !entry.presetId.isNullOrBlank() -> "preset:${entry.presetId.lowercase()}"
            else -> "kind:${entry.kind.name}:${entry.baseUrlOverride?.let(PostProcessor::normalizeBaseUrl) ?: ""}"
        }

        internal fun shouldRetryCloudChunk(
            attempt: Int,
            errorKind: CloudErrorKind,
            httpCode: Int? = null,
            retryAfterMs: Long? = null,
            hasNextRoute: Boolean = false,
            maxAttempts: Int = 2,
        ): Boolean {
            val boundedMax = maxAttempts.coerceIn(
                AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
                AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS,
            )
            if (attempt + 1 >= boundedMax) return false
            if (CloudErrorClassifier.isAccountDeadError(httpCode)) return false
            if (CloudErrorClassifier.isQuickRetryable429(httpCode, retryAfterMs)) return true
            return errorKind == CloudErrorKind.TEMPORARY_BUSY && !hasNextRoute
        }

        internal fun cloudChunkRetryDelayMs(
            errorKind: CloudErrorKind,
            httpCode: Int? = null,
            retryAfterMs: Long? = null,
        ): Long = if (CloudErrorClassifier.isQuickRetryable429(httpCode, retryAfterMs)) {
            CloudErrorClassifier.quick429BackoffMs(retryAfterMs)
        } else {
            3_000L
        }

        /** Any app can share audio here, and a sender can serve an endless stream. */
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024

        /** Best-effort display name for a content URI (never throws). */
        fun displayNameOf(context: Context, uri: Uri, fallback: String): String = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
