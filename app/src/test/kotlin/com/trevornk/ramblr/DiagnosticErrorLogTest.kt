package com.trevornk.ramblr

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticErrorLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun initWorkManager() {
        val app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        DiagnosticErrorLog.clear(app)
    }

    @Test fun `scrub redacts api keys bearer tokens headers and url credentials`() {
        val raw = """
            HTTP 401 Authorization: Bearer sk-proj-secret1234567890abcd
            x-api-key: sk-ant-secret987654321
            x-goog-api-key=AIzaSyDummyKey1234567890123456
            url=https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=AIzaSyUrlSecretValue1234
            groq=gsk_superSecretTokenValue123456
        """.trimIndent()

        val scrubbed = DiagnosticErrorLog.scrub(raw)
        assertFalse(scrubbed.contains("secret1234567890abcd"))
        assertFalse(scrubbed.contains("sk-ant-secret"))
        assertFalse(scrubbed.contains("AIzaSyDummyKey"))
        assertFalse(scrubbed.contains("AIzaSyUrlSecretValue"))
        assertFalse(scrubbed.contains("gsk_superSecret"))
        assertTrue(scrubbed.contains("[redacted]"))
    }

    @Test fun `ring buffer caps persisted entries at MAX_ENTRIES`() {
        val file = File(temp.root, "diagnostic_errors.log")
        for (i in 1..(DiagnosticErrorLog.MAX_ENTRIES + 15)) {
            DiagnosticErrorLog.recordToFile(file, "entry-$i")
        }
        val lines = DiagnosticErrorLog.readFromFile(file)
        assertEquals(DiagnosticErrorLog.MAX_ENTRIES, lines.size)
        assertEquals("entry-16", lines.first())
        assertEquals("entry-${DiagnosticErrorLog.MAX_ENTRIES + 15}", lines.last())
    }

    @Test fun `diagnostics screen shows recent error log rows and opens viewer dialog`() {
        val app = RuntimeEnvironment.getApplication()
        DiagnosticErrorLog.record(
            context = app,
            stage = "cleanup",
            provider = "OPENAI_DIRECT",
            model = "gpt-5.4-mini",
            httpCode = 503,
            detail = "Service unavailable with key=sk-proj-abc1234567890xyz",
        )

        val activity = Robolectric.buildActivity(
            DataLogsActivity::class.java,
            DataLogsActivity.intent(app, DataLogsActivity.Section.DIAGNOSTICS),
        ).setup().get()

        fun View.all(): Sequence<View> = sequence {
            yield(this@all)
            if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
        }
        fun View.row(title: String) = all().first {
            it.isClickable && it.findViewWithTag<TextView>("title")?.text?.toString() == title
        }

        val recentRow = activity.window.decorView.row("Recent errors")
        val shareRow = activity.window.decorView.row("Share error log")
        assertTrue(recentRow.findViewWithTag<TextView>("subtitle").text.toString().contains("1 recent"))
        assertTrue(shareRow.isShown)

        recentRow.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val dialogTexts = dialog.window!!.decorView.all().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(dialogTexts.contains("HTTP 503"))
        assertFalse(dialogTexts.contains("abc1234567890xyz"))

        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(DiagnosticErrorLog.readRecent(app).isEmpty())
    }
}
