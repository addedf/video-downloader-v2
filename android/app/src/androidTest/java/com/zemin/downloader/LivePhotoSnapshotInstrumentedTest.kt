package com.zemin.downloader

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.VideoView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.common.core.ResolveResultParser
import com.zemin.downloader.common.util.DownloadHistoryStore
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.MainActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in: reads a real schema-v2 response from target external files, then exercises
 * network playback and the normal UI save flow. New MediaStore files are retained for inspection.
 * Arguments: -e runLiveSnapshot true -e liveSnapshotPath <absolute target external-files path>.
 * The response and its signed URLs must never be copied into source code or test assets.
 */
@RunWith(AndroidJUnit4::class)
class LivePhotoSnapshotInstrumentedTest {
    @Test
    fun liveSnapshotPlaysAndSavesOnlySelectedImageVideoPairs() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runLiveSnapshot") == "true")
        assumeTrue("MediaStore pending-state validation requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val externalDirectory = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val snapshot = File(requireNotNull(args.getString("liveSnapshotPath")) {
            "Pass liveSnapshotPath pointing to a schema-v2 response in target external files"
        }).canonicalFile
        assertTrue("Snapshot must be inside target external files", snapshot.path.startsWith(externalDirectory.path + File.separator))
        assertTrue("Snapshot file is missing", snapshot.isFile)
        val preview = ResolveResultParser.parse(snapshot.readText())
        assertTrue("Snapshot must parse successfully", preview.ok)
        assertEquals(2, preview.schemaVersion)
        assertEquals("live_photo", preview.mediaType)
        assertTrue("Snapshot must expose Live videos", preview.capabilities.hasLiveVideo)
        val images = preview.resources.filter { it.mediaType == "image" }
        assertTrue("Fixture needs at least two images", images.size >= 2)
        assertTrue("First image must support Live playback", images.first().liveVideo?.available == true)
        val selected = listOf(images.first(), images.last())
        val selectedPositions = setOf(1, images.size)
        val expectedAssets = selected.flatMap { resource ->
            val index = resource.index.takeIf { it > 0 } ?: 1
            buildList {
                add(index to false)
                if (resource.liveVideo?.available == true) add(index to true)
            }
        }.toSet()
        val sourceUrl = requireNotNull(preview.sourceUrl).also {
            assertTrue("Snapshot source URL is required", it.isNotBlank())
        }
        val report = JSONObject().apply {
            put("snapshotFile", snapshot.name)
            put("imageCount", images.size)
            put("liveVideoCount", preview.counts.liveVideos)
            put("selectedPositions", JSONArray(selectedPositions.toList()))
            put("expectedSavedCount", expectedAssets.size)
            put("status", "running")
        }

        BridgeAbilityManager.update(DownloadType.DOU_YIN)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.setInputText(sourceUrl)
                activity.previewSection.render(sourceUrl, preview)
                assertEquals(images.map { it.id }, activity.previewSection.buildDownloadRequest()?.selection?.resourceIds)
                assertTrue(activity.findViewById<CheckBox>(R.id.checkSelectAll).isChecked)
                val liveCheck = activity.findViewById<CheckBox>(R.id.checkLiveVideo)
                assertEquals(View.VISIBLE, liveCheck.visibility)
                assertFalse(liveCheck.isChecked)
            }
            awaitOnMain(instrumentation, "Live preview did not begin playback", 60_000L) {
                val surface = activity.findViewById<VideoView>(R.id.playerSurface)
                activity.findViewById<View>(R.id.videoPreview).visibility == View.VISIBLE &&
                    surface.duration > 0 && surface.isPlaying
            }
            instrumentation.runOnMainSync {
                report.put("previewDurationMs", activity.findViewById<VideoView>(R.id.playerSurface).duration)
                report.put("previewIsPlaying", activity.findViewById<VideoView>(R.id.playerSurface).isPlaying)
                var visited = 0
                while (visited < images.size) {
                    val thumbs = activity.findViewById<ViewGroup>(R.id.thumbContainer)
                    val resources = (0 until thumbs.childCount).map(thumbs::getChildAt)
                        .filter { it.tag is Int }
                    assertTrue("Thumbnail page must contain resources", resources.isNotEmpty())
                    resources.forEach { thumbnail ->
                        thumbnail.performClick()
                        val check = activity.findViewById<CheckBox>(R.id.checkPreviewSelected)
                        assertTrue("Resources must start selected", check.isChecked)
                        val position = (thumbnail.tag as Int) + 1
                        if (position !in selectedPositions) check.performClick()
                    }
                    visited += resources.size
                    if (visited < images.size) {
                        val next = (0 until thumbs.childCount).map(thumbs::getChildAt)
                            .filterIsInstance<Button>()
                            .single { it.text.toString() == activity.getString(R.string.x_preview_next) }
                        next.performClick()
                    }
                }
                activity.findViewById<CheckBox>(R.id.checkLiveVideo).performClick()
                val request = requireNotNull(activity.previewSection.buildDownloadRequest())
                assertEquals(selected.map { it.id }, request.selection.resourceIds)
                assertTrue(request.selection.includeLiveVideo)
                assertEquals("image", request.selection.resourceType)
            }
            if (images.last().liveVideo?.available == true) {
                awaitOnMain(instrumentation, "Selected Live preview did not resume playback", 60_000L) {
                    val surface = activity.findViewById<VideoView>(R.id.playerSurface)
                    activity.findViewById<View>(R.id.videoPreview).visibility == View.VISIBLE &&
                        surface.duration > 0 && surface.isPlaying
                }
            }
            SystemClock.sleep(1_000L)
            capture(instrumentation, File(externalDirectory, "live-snapshot-before-save.png"))

            val previousDownloadId = DownloadHistoryStore.latest()?.downloadId
            instrumentation.runOnMainSync {
                val save = activity.findViewById<View>(R.id.btnSaveSheet)
                assertTrue("Save must be enabled", save.isEnabled)
                assertTrue("Save button must have its normal click handler", save.performClick())
                assertTrue("UI save must start DownloadFlow", activity.downloadFlow.isDownloading)
            }
            awaitOnMain(instrumentation, "UI download did not finish", 180_000L) {
                !activity.downloadFlow.isDownloading
            }
            val history = requireNotNull(DownloadHistoryStore.latest()) { "Download history was not created" }
            assertNotEquals("Save must create a new history record", previousDownloadId, history.downloadId)
            assertTrue("UI download must succeed; inspect app history if it fails", history.isSuccess)
            assertEquals(expectedAssets.size, history.savedUris.size)
            assertEquals(history.savedUris.size, history.savedUris.distinct().size)
            val media = history.savedUris.map { inspectMedia(context, it) }
            val actualAssets = media.map { item ->
                val match = requireNotNull(ASSET_SUFFIX.find(item.getString("displayName"))) {
                    "Unexpected saved filename suffix"
                }
                val live = match.groupValues[2].isNotEmpty()
                if (live) {
                    assertEquals("mp4", match.groupValues[3])
                    assertEquals("video/mp4", item.getString("mimeType"))
                } else {
                    assertTrue(match.groupValues[3] in setOf("jpg", "jpeg", "png", "webp", "gif"))
                    assertTrue(item.getString("mimeType").startsWith("image/"))
                }
                match.groupValues[1].toInt() to live
            }
            assertEquals("Only selected still images and their Live videos may be saved", expectedAssets, actualAssets.toSet())
            assertEquals("Each expected asset must be saved exactly once", expectedAssets.size, actualAssets.size)
            report.put("savedMedia", JSONArray(media))
            report.put("savedCount", media.size)
            report.put("status", "passed")
            SystemClock.sleep(1_000L)
            capture(instrumentation, File(externalDirectory, "live-snapshot-after-save.png"))
        } catch (failure: Throwable) {
            report.put("status", "failed")
            report.put("failureClass", failure.javaClass.simpleName)
            throw failure
        } finally {
            File(externalDirectory, "live-snapshot-report.json").writeText(report.toString(2))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun inspectMedia(context: Context, uri: Uri): JSONObject {
        val columns = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val item = context.contentResolver.query(uri, columns, null, null, null).use { cursor ->
            requireNotNull(cursor) { "Saved MediaStore row is missing" }
            assertTrue("Saved MediaStore row is missing", cursor.moveToFirst())
            JSONObject().apply {
                put("displayName", cursor.getString(0))
                put("mimeType", cursor.getString(1))
                put("size", cursor.getLong(2))
                put("isPending", cursor.getInt(3))
            }
        }
        assertTrue("Saved media must not be empty", item.getLong("size") > 0)
        assertEquals("Saved media must be published", 0, item.getInt("isPending"))
        context.contentResolver.openInputStream(uri).use { stream ->
            assertTrue("Saved media must be readable", requireNotNull(stream).read() >= 0)
        }
        if (item.getString("mimeType").startsWith("video/")) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                assertTrue("Saved Live video must have playable duration", duration > 0)
                item.put("durationMs", duration)
            }
        }
        return item
    }

    private fun awaitOnMain(instrumentation: Instrumentation, message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }

    private fun capture(instrumentation: Instrumentation, target: File) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val ASSET_SUFFIX = Regex("_image_(\\d+)(_live)?(?: \\(\\d+\\))?\\.(jpg|jpeg|png|webp|gif|mp4)$")
    }
}
