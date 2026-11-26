package com.trevornk.ramblr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 3A.3 end-to-end UI, unread badge, zero-RadioButton, and shared Groq/OpenRouter vault key
 * resolution tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase3A3UiAndRoutingTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        clearState()
    }

    @After fun tearDown() {
        clearState()
    }

    private fun clearState() {
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderKind.entries.forEach { ProviderCredentialStore.clearLegacyByKind(app, it) }
        ProviderCapabilityRegistry.allRegistered().forEach {
            ProviderCredentialStore.clearVaultSecret(app, ProviderCapabilityRegistry.canonicalVaultKeyId(it.id))
        }
        AppNotificationStore.clear(app)
        DiagnosticErrorLog.clear(app)
        DictationHistoryStore.forContext(app).clear()
        DictationHistoryStore.clearUnreadCount(app)
    }

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).all())
        }
    }

    private fun View.row(title: String): View = all().first { v ->
        v.isClickable && v.findViewWithTag<TextView>("title")?.text?.toString() == title
    }

    @Test fun `zero RadioButton or RadioGroup instances exist across all settings screens`() {
        val intents = listOf<Class<out Activity>>(
            MainActivity::class.java,
            DictationModeActivity::class.java,
            LiveTranscriptionActivity::class.java,
            TranscriptionActivity::class.java,
            CleanupActivity::class.java,
            CleanupStylesActivity::class.java,
            CloudProviderActivity::class.java,
            ApiKeysVaultActivity::class.java,
            FallbackActivity::class.java,
            NotificationsActivity::class.java,
            LivePreviewActivity::class.java,
            OverlayAppearanceActivity::class.java,
            SetupActivity::class.java,
        ).map { Intent(app, it) } +
            BehaviorActivity.Section.entries.map { BehaviorActivity.intent(app, it) } +
            InvocationActivity.Section.entries.map { InvocationActivity.intent(app, it) } +
            DataLogsActivity.Section.entries.map { DataLogsActivity.intent(app, it) }

        for (intent in intents) {
            @Suppress("UNCHECKED_CAST")
            val clazz = Class.forName(intent.component!!.className) as Class<Activity>
            val activity = Robolectric.buildActivity(clazz, intent).setup().get()
            val radios = activity.window.decorView.all().filter { it is RadioButton || it is RadioGroup }.toList()
            assertTrue("${clazz.simpleName} must contain zero RadioButton/RadioGroup widgets", radios.isEmpty())
        }
    }

    @Test fun `MainActivity displays Rhema Cadence title and unread badges that clear when target screens open`() {
        AppNotificationStore.record(app, AppNotificationCategory.FALLBACK, "Fell back to Local", "Cloud timed out")
        AppNotificationStore.record(app, AppNotificationCategory.LIVE_DISCONNECT, "Live disconnected", "Switched to Batch STT")
        DiagnosticErrorLog.record(app, stage = "test_err", detail = "timeout")
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit()
            .putBoolean("dictation_history_enabled", true).commit()
        DictationHistoryStore.forContext(app).add(DictationHistoryEntry(1000L, "hello", null))

        assertEquals(2, AppNotificationStore.unreadCount(app))
        assertEquals(1, DiagnosticErrorLog.unreadCount(app))
        assertEquals(1, DictationHistoryStore.unreadCount(app))

        val mainController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val main = mainController.get()
        val root = main.window.decorView

        assertTrue(root.all().filterIsInstance<TextView>().any { it.text.toString() == "Rhema Cadence" })

        val notifBadge = root.row("Notifications").findViewWithTag<TextView>("unread_badge")
        val historyBadge = root.row("History").findViewWithTag<TextView>("unread_badge")
        val diagBadge = root.row("Diagnostics").findViewWithTag<TextView>("unread_badge")

        assertEquals(View.VISIBLE, notifBadge.visibility)
        assertEquals("2", notifBadge.text.toString())
        assertEquals(View.VISIBLE, historyBadge.visibility)
        assertEquals("1", historyBadge.text.toString())
        assertEquals(View.VISIBLE, diagBadge.visibility)
        assertEquals("1", diagBadge.text.toString())

        // Opening NotificationsActivity clears Notifications unread count
        Robolectric.buildActivity(NotificationsActivity::class.java).setup().get()
        assertEquals(0, AppNotificationStore.unreadCount(app))

        // Opening DataLogsActivity(HISTORY) clears History unread count
        Robolectric.buildActivity(
            DataLogsActivity::class.java,
            DataLogsActivity.intent(app, DataLogsActivity.Section.HISTORY),
        ).setup().get()
        assertEquals(0, DictationHistoryStore.unreadCount(app))

        // Opening DataLogsActivity(DIAGNOSTICS) clears Diagnostics unread count
        Robolectric.buildActivity(
            DataLogsActivity::class.java,
            DataLogsActivity.intent(app, DataLogsActivity.Section.DIAGNOSTICS),
        ).setup().get()
        assertEquals(0, DiagnosticErrorLog.unreadCount(app))

        // Resuming MainActivity hides all three badges
        mainController.pause().resume()
        assertEquals(View.GONE, notifBadge.visibility)
        assertEquals(View.GONE, historyBadge.visibility)
        assertEquals(View.GONE, diagBadge.visibility)
    }

    @Test fun `FallbackActivity toggles DictationModeToggle switches and persists state`() {
        val activity = Robolectric.buildActivity(FallbackActivity::class.java).setup().get()
        val root = activity.window.decorView

        assertTrue(DictationModeToggle.allowLocalFallback(app))
        assertFalse(DictationModeToggle.allowCloudFallback(app))

        root.row("Fall back to on-device if cloud fails").performClick()
        root.row("Fall back to cloud if on-device fails").performClick()

        assertFalse(DictationModeToggle.allowLocalFallback(app))
        assertTrue(DictationModeToggle.allowCloudFallback(app))
    }

    @Test fun `CleanupStylesActivity toggles shared options and updates prefs`() {
        val activity = Robolectric.buildActivity(CleanupStylesActivity::class.java).setup().get()
        val root = activity.window.decorView

        assertTrue(root.all().filterIsInstance<TextView>().any { it.text.toString() == "Applies to both Cloud and Local AI cleanup." })

        val beforePreview = PreviewBeforeInjectToggle.isEnabled(app)
        root.row("Preview before inserting").performClick()
        assertEquals(!beforePreview, PreviewBeforeInjectToggle.isEnabled(app))
    }

    @Test fun `ProviderCredentialStore resolves Groq and OpenRouter shared vault keys even when vaultKeyId is null`() {
        val groqVaultId = ProviderCapabilityRegistry.canonicalVaultKeyId(ProviderCapabilityRegistry.ID_GROQ)
        val openRouterVaultId = ProviderCapabilityRegistry.canonicalVaultKeyId(ProviderCapabilityRegistry.ID_OPENROUTER)
        ApiKeyVaultStore.upsert(
            app,
            ApiKeyVaultEntry(groqVaultId, ProviderCapabilityRegistry.ID_GROQ, "Groq", locked = true),
            secret = "gsk_valid_groq_secret_key",
        )
        ApiKeyVaultStore.upsert(
            app,
            ApiKeyVaultEntry(openRouterVaultId, ProviderCapabilityRegistry.ID_OPENROUTER, "OpenRouter", locked = true),
            secret = "sk-or-v1-valid-openrouter-key",
        )

        val groqCardNoLink = ProviderChainEntry(
            kind = ProviderKind.OPENAI,
            model = "openai/gpt-oss-120b",
            transcriptionModel = "whisper-large-v3-turbo",
            baseUrlOverride = ProviderCapabilityRegistry.GROQ.defaultBaseUrl,
            id = "groq-unlinked",
            vaultKeyId = null,
        )
        val openRouterCardNoLink = ProviderChainEntry(
            kind = ProviderKind.OPENAI,
            model = "openai/gpt-4o-mini",
            transcriptionModel = "openai/whisper-1",
            baseUrlOverride = ProviderCapabilityRegistry.OPENROUTER.defaultBaseUrl,
            id = "or-unlinked",
            vaultKeyId = null,
        )

        assertTrue(ProviderCredentialStore.isConfigured(app, groqCardNoLink))
        assertEquals("gsk_valid_groq_secret_key", ProviderCredentialStore.get(app, groqCardNoLink))
        assertTrue(ProviderCredentialStore.isConfigured(app, openRouterCardNoLink))
        assertEquals("sk-or-v1-valid-openrouter-key", ProviderCredentialStore.get(app, openRouterCardNoLink))
    }
}
