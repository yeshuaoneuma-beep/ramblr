package com.trevornk.ramblr

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompressedUploadGateTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `CompressedUploadToggle defaults to true and respects explicit opt-out`() {
        assertTrue(CompressedUploadToggle.DEFAULT)
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("compressed_upload_gate_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertTrue(CompressedUploadToggle.isEnabled(prefs))
        CompressedUploadToggle.setEnabled(prefs, false)
        assertFalse(CompressedUploadToggle.isEnabled(prefs))
        CompressedUploadToggle.setEnabled(prefs, true)
        assertTrue(CompressedUploadToggle.isEnabled(prefs))
    }

    @Test fun `canInlineAudio uses compressed m4a file size when present`() {
        val largePcmBytes = 19_200_000L // ~10 minutes at 16 kHz mono PCM16, > 10 MB limit
        val smallM4aBytes = 4_800_000L  // ~4.8 MB AAC/M4A, <= 10 MB limit

        assertFalse("Raw 19.2 MB PCM alone exceeds inline cap", GeminiTranscriberClient.canInlineAudio(largePcmBytes))
        assertTrue(
            "19.2 MB PCM with 4.8 MB m4a must pass inline cap",
            GeminiTranscriberClient.canInlineAudio(pcmBytes = largePcmBytes, compressedBytes = smallM4aBytes),
        )
        assertFalse(
            "Oversized compressed file over 10 MB must still be rejected",
            GeminiTranscriberClient.canInlineAudio(
                pcmBytes = 5_000_000L,
                compressedBytes = GeminiTranscriberClient.MAX_INLINE_PCM_BYTES + 1,
            ),
        )
    }

    @Test fun `canInlineAudio File overload checks compressedFile length instead of raw pcmFile length`() {
        val pcmFile = File(temp.root, "long.pcm").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(19_200_000L) }
        }
        val m4aFile = File(temp.root, "long.m4a").apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(4_000_000L) }
        }

        assertFalse(GeminiTranscriberClient.canInlineAudio(pcmFile, compressedFile = null))
        assertTrue(GeminiTranscriberClient.canInlineAudio(pcmFile, compressedFile = m4aFile))
    }
}
