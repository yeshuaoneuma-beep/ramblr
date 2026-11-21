package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioJobCheckpointTest {

    private lateinit var app: Application
    private lateinit var store: AudioJobStore

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        AudioJobFiles.dir(app.filesDir).deleteRecursively()
        DictationHistoryStore.forContext(app).clear()
        store = AudioJobStore(File(app.filesDir, "audio_jobs_test.jsonl").apply { delete() })
    }

    @Test fun `atomic raw checkpoint writes without leaving temp files and deletes cleanly`() {
        val target = AudioJobFiles.writeRawCheckpointAtomically(app.filesDir, "job-1", "hello raw checkpoint", 1234L)
        assertTrue(target.exists())
        assertFalse(File(target.parentFile, "${target.name}.tmp").exists())

        val readBack = AudioJobFiles.readRawCheckpoint(app.filesDir, "job-1")
        assertNotNull(readBack)
        assertEquals("hello raw checkpoint", readBack!!.rawText)
        assertEquals(1234L, readBack.historyTimestamp)

        // Atomic overwrite updates in place with no .tmp residue.
        AudioJobFiles.writeRawCheckpointAtomically(app.filesDir, "job-1", "updated raw text", 5678L)
        val updated = AudioJobFiles.readRawCheckpoint(app.filesDir, "job-1")
        assertEquals("updated raw text", updated?.rawText)
        assertEquals(5678L, updated?.historyTimestamp)
        assertFalse(File(target.parentFile, "${target.name}.tmp").exists())

        AudioJobFiles.deleteRawCheckpoint(app.filesDir, "job-1")
        assertFalse(target.exists())
        assertNull(AudioJobFiles.readRawCheckpoint(app.filesDir, "job-1"))
    }

    @Test fun `raw checkpoint and raw history are written atomically before cleanup while source audio stays intact`() {
        val jobId = "job-before-cleanup"
        val source = AudioJobFiles.sourceFile(app.filesDir, jobId).apply { parentFile?.mkdirs(); writeBytes(ByteArray(128) { 7 }) }
        val job = AudioJob(jobId, "clip.m4a", JobKind.IMPORTED, JobStatus.QUEUED, 1L, hasLocalAudio = true)
        store.upsert(job)

        var sawCheckpointBeforeCleanup = false
        var sawSourceIntactDuringCleanup = false
        var sawHistoryRawBeforeCleanup = false

        val runner = AudioJobRunner(
            context = app,
            store = store,
            isCancelled = { false },
            onStatus = {},
            decodeAudio = { _, pcm, _, _ ->
                pcm.writeBytes(ByteArray(32_000))
                AudioFileDecoder.Result(pcmBytes = 32_000L, durationMs = 1_000L)
            },
            runStage1Stt = { _, _, _, _ -> "transcribed stage one raw text" },
            runCleanup = { id, raw ->
                val cp = AudioJobFiles.readRawCheckpoint(app.filesDir, id)
                sawCheckpointBeforeCleanup = cp?.rawText == raw && store.get(id)?.hasRawCheckpoint == true
                sawSourceIntactDuringCleanup = source.exists()
                val hist = DictationHistoryStore.forContext(app).all()
                sawHistoryRawBeforeCleanup = hist.size == 1 && hist.single().rawText == raw && hist.single().cleanedText == null
                AudioJobRunner.CleanupOutcome(cleaned = "Cleaned stage two text.", failed = false)
            },
        )

        runner.run(job)

        assertTrue("atomic raw checkpoint must exist before cleanup", sawCheckpointBeforeCleanup)
        assertTrue("source audio must remain intact during cleanup", sawSourceIntactDuringCleanup)
        assertTrue("raw transcript must already be safely upserted before cleanup", sawHistoryRawBeforeCleanup)

        val done = store.get(jobId)!!
        assertEquals(JobStatus.DONE, done.status)
        assertFalse(done.hasLocalAudio)
        assertFalse(done.hasRawCheckpoint)
        assertFalse("checkpoint must be deleted after final persistence succeeds", AudioJobFiles.rawCheckpointFile(app.filesDir, jobId).exists())
        assertFalse("source audio must be deleted after final persistence succeeds", source.exists())

        val history = DictationHistoryStore.forContext(app).all()
        assertEquals("final upsert must update raw entry in place without duplicating", 1, history.size)
        assertEquals("transcribed stage one raw text", history.single().rawText)
        assertEquals("Cleaned stage two text.", history.single().cleanedText)
    }

    @Test fun `cleanup failure preserves raw checkpoint, source audio, and raw history entry`() {
        val jobId = "job-cleanup-crash"
        val source = AudioJobFiles.sourceFile(app.filesDir, jobId).apply { parentFile?.mkdirs(); writeBytes(ByteArray(128) { 9 }) }
        val job = AudioJob(jobId, "clip.m4a", JobKind.IMPORTED, JobStatus.QUEUED, 2L, hasLocalAudio = true)
        store.upsert(job)

        val runner = AudioJobRunner(
            context = app,
            store = store,
            isCancelled = { false },
            onStatus = {},
            decodeAudio = { _, pcm, _, _ ->
                pcm.writeBytes(ByteArray(32_000))
                AudioFileDecoder.Result(pcmBytes = 32_000L, durationMs = 1_000L)
            },
            runStage1Stt = { _, _, _, _ -> "preserved raw transcript on crash" },
            runCleanup = { _, _ -> throw OutOfMemoryError("simulated cleanup crash") },
        )

        runner.run(job)

        val failed = store.get(jobId)!!
        assertEquals(JobStatus.FAILED, failed.status)
        assertTrue(failed.hasLocalAudio)
        assertTrue(failed.hasRawCheckpoint)
        assertTrue(AudioJobMachine.canRetry(failed))
        assertTrue("source audio must survive cleanup crash", source.exists())
        assertEquals("preserved raw transcript on crash", AudioJobFiles.readRawCheckpoint(app.filesDir, jobId)?.rawText)

        val history = DictationHistoryStore.forContext(app).all()
        assertEquals(1, history.size)
        assertEquals("preserved raw transcript on crash", history.single().rawText)
        assertNull(history.single().cleanedText)
    }

    @Test fun `final persistence failure preserves raw checkpoint and source audio`() {
        val jobId = "job-persist-fail"
        val source = AudioJobFiles.sourceFile(app.filesDir, jobId).apply { parentFile?.mkdirs(); writeBytes(ByteArray(128) { 5 }) }
        val job = AudioJob(jobId, "clip.m4a", JobKind.IMPORTED, JobStatus.QUEUED, 3L, hasLocalAudio = true)
        store.upsert(job)

        var historyWriteCount = 0
        val runner = AudioJobRunner(
            context = app,
            store = store,
            isCancelled = { false },
            onStatus = {},
            decodeAudio = { _, pcm, _, _ ->
                pcm.writeBytes(ByteArray(32_000))
                AudioFileDecoder.Result(pcmBytes = 32_000L, durationMs = 1_000L)
            },
            runStage1Stt = { _, _, _, _ -> "raw text waiting for persistence" },
            runCleanup = { _, _ -> AudioJobRunner.CleanupOutcome(cleaned = "Cleaned text.", failed = false) },
            persistHistory = { entry ->
                historyWriteCount++
                if (historyWriteCount == 1) {
                    DictationHistoryStore.forContext(app).upsert(entry)
                } else {
                    throw RuntimeException("simulated disk failure on final persistence")
                }
            },
        )

        runner.run(job)

        val failed = store.get(jobId)!!
        assertEquals(JobStatus.FAILED, failed.status)
        assertTrue(source.exists())
        assertEquals("raw text waiting for persistence", AudioJobFiles.readRawCheckpoint(app.filesDir, jobId)?.rawText)
    }

    @Test fun `job recovery with existing raw checkpoint skips decode and STT and deletes files after final persistence`() {
        val jobId = "job-recover"
        val source = AudioJobFiles.sourceFile(app.filesDir, jobId).apply { parentFile?.mkdirs(); writeBytes(ByteArray(128) { 3 }) }
        val rawTs = 987654321L
        AudioJobFiles.writeRawCheckpointAtomically(app.filesDir, jobId, "recovered raw transcript", rawTs)
        DictationHistoryStore.forContext(app).upsert(DictationHistoryEntry(rawTs, "recovered raw transcript", null))

        // Simulate process killed mid-CLEANING.
        val interrupted = AudioJob(
            id = jobId,
            displayName = "long_meeting.m4a",
            kind = JobKind.IMPORTED,
            status = JobStatus.CLEANING,
            createdAt = 4L,
            hasLocalAudio = true,
            hasRawCheckpoint = true,
        )
        store.upsert(interrupted)

        assertEquals(listOf(jobId), store.recoverInterrupted())
        assertEquals(JobFailure.INTERRUPTED, store.get(jobId)?.failure)
        assertTrue(AudioJobMachine.canRetry(store.get(jobId)!!))

        val retried = store.update(jobId, JobEvent.Retry)!!
        assertEquals(JobStatus.QUEUED, retried.status)

        var decodeCalls = 0
        var sttCalls = 0
        var cleanedInput: String? = null
        val runner = AudioJobRunner(
            context = app,
            store = store,
            isCancelled = { false },
            onStatus = {},
            decodeAudio = { _, _, _, _ ->
                decodeCalls++
                error("decode must not run when raw checkpoint exists")
            },
            runStage1Stt = { _, _, _, _ ->
                sttCalls++
                error("STT must not re-run when raw checkpoint exists")
            },
            runCleanup = { _, raw ->
                cleanedInput = raw
                AudioJobRunner.CleanupOutcome(cleaned = "Recovered and cleaned.", failed = false)
            },
        )

        runner.run(retried)

        assertEquals(0, decodeCalls)
        assertEquals(0, sttCalls)
        assertEquals("recovered raw transcript", cleanedInput)

        val done = store.get(jobId)!!
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(rawTs, done.historyTimestamp)
        assertFalse(done.hasLocalAudio)
        assertFalse(done.hasRawCheckpoint)
        assertFalse(source.exists())
        assertFalse(AudioJobFiles.rawCheckpointFile(app.filesDir, jobId).exists())

        val history = DictationHistoryStore.forContext(app).all()
        assertEquals(1, history.size)
        assertEquals("recovered raw transcript", history.single().rawText)
        assertEquals("Recovered and cleaned.", history.single().cleanedText)
    }

    @Test fun `recovery with cleanup disabled completes cleanly without re-running STT`() {
        PostProcessingToggle.setEnabled(app, false)
        val jobId = "job-recover-no-cleanup"
        val source = AudioJobFiles.sourceFile(app.filesDir, jobId).apply { parentFile?.mkdirs(); writeBytes(ByteArray(64) { 1 }) }
        AudioJobFiles.writeRawCheckpointAtomically(app.filesDir, jobId, "checkpoint raw only", 555L)

        val queued = AudioJob(
            id = jobId,
            displayName = "note.m4a",
            kind = JobKind.IMPORTED,
            status = JobStatus.QUEUED,
            createdAt = 5L,
            hasLocalAudio = true,
            hasRawCheckpoint = true,
        )
        store.upsert(queued)

        var sttCalls = 0
        val runner = AudioJobRunner(
            context = app,
            store = store,
            isCancelled = { false },
            onStatus = {},
            runStage1Stt = { _, _, _, _ -> sttCalls++; "unexpected" },
        )

        runner.run(queued)

        assertEquals(0, sttCalls)
        val done = store.get(jobId)!!
        assertEquals(JobStatus.DONE, done.status)
        assertFalse(source.exists())
        assertFalse(AudioJobFiles.rawCheckpointFile(app.filesDir, jobId).exists())
        val history = DictationHistoryStore.forContext(app).all()
        assertEquals(1, history.size)
        assertEquals("checkpoint raw only", history.single().rawText)
        assertNull(history.single().cleanedText)
    }
}
