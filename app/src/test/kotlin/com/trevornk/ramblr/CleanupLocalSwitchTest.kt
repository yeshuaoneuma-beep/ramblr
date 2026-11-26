package com.trevornk.ramblr

import android.app.Application
import android.content.Intent
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.UUID

/**
 * Verifies [CleanupActivity] (dedicated Local AI cleanup screen):
 * - A completed download selects the local cleanup model and updates the local floor entry
 *   without unexpectedly toggling [CloudFeatureToggle.cleanupEnabled] (which now belongs to
 *   [DictationModeActivity]).
 * - A stale SUCCEEDED replay never seen in flight is ignored by the completion gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupLocalSwitchTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val model = LOCAL_CLEANUP_MODEL_CATALOG.first()

    @Before fun setUp() {
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        CloudFeatureToggle.setCleanupEnabled(app, true)
        ModelDownloader.modelDir(app, model).deleteRecursively()
        app.getSharedPreferences("ramblr", 0).edit().remove("local_cleanup_model_name").apply()
    }

    private fun launch(): CleanupActivity =
        Robolectric.buildActivity(CleanupActivity::class.java, Intent(app, CleanupActivity::class.java))
            .setup().get()

    private fun installModel() {
        val dir = ModelDownloader.modelDir(app, model)
        dir.mkdirs()
        File(dir, ".complete").writeText("ok")
        assertTrue(ModelDownloader.isInstalled(app, model))
    }

    private fun info(id: UUID, state: WorkInfo.State) = WorkInfo(id, state, emptySet())

    /** RUNNING then SUCCEEDED for the same work id, as WorkManager delivers a real download. */
    private fun CleanupActivity.completeDownload() {
        val id = UUID.randomUUID()
        onCleanupModelWorkInfos(model, listOf(info(id, WorkInfo.State.RUNNING)))
        installModel()
        onCleanupModelWorkInfos(model, listOf(info(id, WorkInfo.State.SUCCEEDED)))
    }

    @Test fun `completed download selects the local cleanup model without flipping cloud mode`() {
        val activity = launch()

        activity.completeDownload()

        assertTrue("must not silently flip cloud cleanup mode", CloudFeatureToggle.cleanupEnabled(app))
        val local = ProviderChainStore.load(app).entries.single { it.kind == ProviderKind.LOCAL }
        assertEquals(model.archive, local.model)
        assertEquals(model.archive, app.getSharedPreferences("ramblr", 0).getString("local_cleanup_model_name", null))
        assertEquals("${model.name} ready", ShadowToast.getTextOfLatestToast())
    }

    @Test fun `a SUCCEEDED state never seen in flight (stale replay) does not re-select the model`() {
        val activity = launch()
        installModel()

        activity.onCleanupModelWorkInfos(model, listOf(info(UUID.randomUUID(), WorkInfo.State.SUCCEEDED)))

        assertEquals(null, app.getSharedPreferences("ramblr", 0).getString("local_cleanup_model_name", null))
        assertTrue(CloudFeatureToggle.cleanupEnabled(app))
    }
}
