package com.trevornk.ramblr

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNotificationStoreTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        DiagnosticErrorLog.clear(app)
        DictationHistoryStore.forContext(app).clear()
    }

    @After fun tearDown() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `record prepends newest-first, caps at 50, tracks unread count, and marks all read`() {
        for (i in 1..60) {
            AppNotificationStore.record(
                context = app,
                category = AppNotificationCategory.FALLBACK,
                title = "Alert $i",
                body = "Body $i",
                nowMs = i.toLong() * 1000L,
            )
        }
        val all = AppNotificationStore.all(app)
        assertEquals(AppNotificationStore.MAX_ENTRIES, all.size)
        assertEquals("Alert 60", all.first().title)
        assertEquals("Alert 11", all.last().title)
        assertEquals(50, AppNotificationStore.unreadCount(app))

        AppNotificationStore.markAllRead(app)
        assertEquals(0, AppNotificationStore.unreadCount(app))
        assertTrue(AppNotificationStore.all(app).all { it.read })

        AppNotificationStore.clear(app)
        assertTrue(AppNotificationStore.all(app).isEmpty())
    }

    @Test fun `diagnostics and history unread counters increment and clear properly`() {
        assertEquals(0, DiagnosticErrorLog.unreadCount(app))
        DiagnosticErrorLog.record(app, stage = "stt", provider = "GROQ", detail = "HTTP 500")
        DiagnosticErrorLog.record(app, stage = "cleanup", provider = "GEMINI", detail = "HTTP 503")
        assertEquals(2, DiagnosticErrorLog.unreadCount(app))
        DiagnosticErrorLog.clearUnreadCount(app)
        assertEquals(0, DiagnosticErrorLog.unreadCount(app))

        val store = DictationHistoryStore.forContext(app)
        assertEquals(0, DictationHistoryStore.unreadCount(app))
        store.upsert(DictationHistoryEntry(1000L, "hello", null))
        assertEquals(1, DictationHistoryStore.unreadCount(app))
        // Updating the same timestamp in place must NOT double-increment!
        store.upsert(DictationHistoryEntry(1000L, "hello", "Hello."))
        assertEquals(1, DictationHistoryStore.unreadCount(app))
        store.add(DictationHistoryEntry(2000L, "second", null))
        assertEquals(2, DictationHistoryStore.unreadCount(app))
        store.clear()
        assertEquals(0, DictationHistoryStore.unreadCount(app))
    }

    @Test fun `live streaming models are strictly excluded from batch STT and cleanup and Whisper 448 ctx is hidden`() {
        val liveIds = listOf(
            "gemini-3.5-transcribe-live",
            "gemini-3.8-live",
            "gemini-2.5-flash-native-audio-preview",
            "gpt-4o-realtime-preview",
            "google/gemini-live-bidi",
            "gemini-3.5-live-translate-preview",
        )
        for (id in liveIds) {
            assertTrue("expected live model: $id", ModelCapabilityClassifier.isLiveStreamingModel(id))
            for (provider in listOf("gemini", "openai", "groq", "openrouter", "custom_openai")) {
                assertFalse("$provider/$id must not support BATCH_STT", ModelCapabilityClassifier.supportsLane(provider, id, CapabilityLane.BATCH_STT, setOf("audio", "text")))
                assertFalse("$provider/$id must not support CLEANUP", ModelCapabilityClassifier.supportsLane(provider, id, CapabilityLane.CLEANUP, setOf("audio", "text")))
            }
        }

        val whisper = ModelDescriptor(
            id = "whisper-large-v3-turbo",
            providerId = "groq",
            displayName = "Whisper Large v3 Turbo",
            lanes = setOf(CapabilityLane.BATCH_STT),
            contextLength = 448,
        )
        assertEquals(null, whisper.contextSummary())

        val llm = ModelDescriptor(
            id = "gemini-3.5-flash-lite",
            providerId = "gemini",
            displayName = "Gemini 3.5 Flash-Lite",
            lanes = setOf(CapabilityLane.CLEANUP),
            contextLength = 1_000_000,
        )
        assertEquals("1M ctx", llm.contextSummary())
    }
}
