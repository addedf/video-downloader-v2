package com.zemin.downloader

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.common.bean.DownloadRequest
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.common.core.DownloadModule
import com.zemin.downloader.common.core.StoreModule
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.preview.CollectionPreviewPolicy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: real network and MediaStore writes, with test media removed afterwards. */
@RunWith(AndroidJUnit4::class)
class XDownloadInstrumentedTest {
    @Test
    fun publicProfilePreviewSelectionDownloadAndMediaStore() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runXLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profileUrl = args.getString("xProfile") ?: "https://x.com/NASA"
        val postUrl = args.getString("xPost") ?: "https://x.com/i/status/2106061652617568263"
        BridgeAbilityManager.update(DownloadType.TWITTER)
        val post = DownloadModule.resolve(postUrl)
        assertTrue(post.message, post.ok)
        assertTrue(post.resources.isNotEmpty())
        val first = DownloadModule.resolve(profileUrl)
        assertTrue(first.message, first.ok)
        assertNotNull(first.collection)
        val cursor = first.collection?.nextCursor
        val preview = if (cursor != null) {
            val next = DownloadModule.resolve(profileUrl, cursor)
            assertTrue(next.message, next.ok)
            CollectionPreviewPolicy.merge(first, next)
        } else first
        assertTrue(preview.resources.isNotEmpty())
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            var request: DownloadRequest? = null
            instrumentation.runOnMainSync {
                activity.previewSection.render(profileUrl, preview)
                request = activity.previewSection.buildDownloadRequest()
                assertEquals(preview.resources.size, request?.snapshot?.resources?.size)
                assertEquals("all", request?.selection?.resourceType)
                if (preview.collection?.nextCursor != null) {
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnContinueCollection).visibility)
                }
            }
            if (args.getString("captureXPreview") == "true") {
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(600L)
                instrumentation.waitForIdleSync()
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(instrumentation.targetContext.getExternalFilesDir(null), "x-preview.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    screenshot.recycle()
                }
            }
            val resolvedRequest = requireNotNull(request)
            // One resource per media type checks both MIME paths without downloading an entire profile.
            val selected = preview.resources.distinctBy { it.mediaType }.take(2)
            val downloadRequest = resolvedRequest.copy(selection = resolvedRequest.selection.copy(
                resourceIds = selected.map { it.id },
            ))
            val result = DownloadModule.download(profileUrl, downloadRequest)
            assertTrue(result.error ?: result.message, result.ok)
            assertEquals(selected.size, result.files.size)
            result.files.forEach { path ->
                val file = File(path)
                val uri = requireNotNull(StoreModule.registerMediaFile(file))
                try {
                    instrumentation.targetContext.contentResolver.openInputStream(uri).use {
                        assertTrue(requireNotNull(it).read() >= 0)
                    }
                    if (file.extension == "mp4") {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(instrumentation.targetContext, uri)
                            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            assertTrue(requireNotNull(duration).toLong() > 0)
                        }
                    }
                } finally {
                    instrumentation.targetContext.contentResolver.delete(uri, null, null)
                    StoreModule.deleteTemporaryDownloadFile(file)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
