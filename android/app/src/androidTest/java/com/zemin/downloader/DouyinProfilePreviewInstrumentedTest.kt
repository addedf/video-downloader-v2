package com.zemin.downloader

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolveCapabilities
import com.zemin.downloader.common.ResolveCounts
import com.zemin.downloader.common.ResolvedLiveVideo
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.bean.PyCollectionResponse
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.MainActivity
import android.widget.CheckBox
import android.view.View
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline Android integration: Douyin collection snapshots, selection and live media. */
@RunWith(AndroidJUnit4::class)
class DouyinProfilePreviewInstrumentedTest {
    @Test
    fun douyinKeepsMoreThan100ResourcesAndLiveSelection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        BridgeAbilityManager.update(DownloadType.DOU_YIN)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val resources = (1..121).map { index ->
                    ResolvedResource(id = "$index-image_1", mediaType = "image", formatHint = "jpg",
                        downloadUrls = listOf("https://invalid.test/$index.jpg"),
                        liveVideo = if (index == 1) ResolvedLiveVideo(available = true,
                            downloadUrls = listOf("https://invalid.test/live.mp4")) else null)
                }
                val preview = PyResolveResult(ok = true, message = "还有更早的作品", sourceId = "profile-test",
                    sourceUrl = "https://www.douyin.com/user/test",
                    title = "抖音主页测试", mediaType = "gallery", resources = resources,
                    counts = ResolveCounts(images = 121, liveVideos = 1),
                    capabilities = ResolveCapabilities(hasImages = true, hasLiveVideo = true),
                    collection = PyCollectionResponse(nextCursor = "next"))
                activity.previewSection.render(preview.sourceUrl.orEmpty(), preview)
                val request = requireNotNull(activity.previewSection.buildDownloadRequest())
                assertEquals("douyin", request.source.platform)
                assertEquals(preview.sourceUrl, request.source.url)
                assertEquals(preview.sourceId, request.snapshot?.sourceId)
                assertEquals(121, request.snapshot?.resources?.size)
                assertEquals(121, request.selection.resourceIds?.size)
                assertEquals("all", request.selection.resourceType)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnContinueCollection).visibility)
                val live = activity.findViewById<CheckBox>(R.id.checkLiveVideo)
                assertEquals(View.VISIBLE, live.visibility)
                live.performClick()
                assertTrue(requireNotNull(activity.previewSection.buildDownloadRequest()).selection.includeLiveVideo)
                val selectAll = activity.findViewById<CheckBox>(R.id.checkSelectAll)
                selectAll.performClick()
                assertFalse(activity.findViewById<View>(R.id.btnSaveSheet).isEnabled)
                selectAll.performClick()
                assertTrue(activity.findViewById<View>(R.id.btnSaveSheet).isEnabled)
                assertEquals(121, requireNotNull(activity.previewSection.buildDownloadRequest()).selection.resourceIds?.size)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
