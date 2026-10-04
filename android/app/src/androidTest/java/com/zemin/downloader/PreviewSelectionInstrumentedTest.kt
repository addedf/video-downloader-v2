package com.zemin.downloader

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolveCapabilities
import com.zemin.downloader.common.ResolveCounts
import com.zemin.downloader.common.ResolvedLiveVideo
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.ui.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewSelectionInstrumentedTest {
    @Test
    fun allResourcesStartSelectedAndThumbnailClicksOnlyChangePreview() = withPreview { activity ->
        assertCompactSelectionLayout(activity)
        assertEquals(IMAGE_IDS, selectedIds(activity))
        assertTrue(selectAll(activity).isChecked)
        assertEquals(listOf(true, true, true), previewCheckStates(activity, 3))
        assertSummary(activity, selected = 3, total = 3)
        assertTrue(saveButton(activity).isEnabled)

        thumbnail(activity, 1).performClick()

        assertEquals(IMAGE_IDS, selectedIds(activity))
        assertTrue(currentCheck(activity).isChecked)
        assertEquals(
            activity.getString(
                R.string.main_select_resource,
                activity.getString(R.string.main_preview_resource_image),
                2,
            ),
            currentCheck(activity).contentDescription.toString(),
        )
        assertEquals(
            activity.getString(R.string.main_preview_counter_image_format, 2, 3),
            activity.findViewById<TextView>(R.id.tvPreviewCounter).text.toString(),
        )
    }

    @Test
    fun eachTabKeepsItsChoicesAndRequestsOnlyItsSelectedResources() = withPreview { activity ->
        toggleResource(activity, 1)
        assertEquals(listOf("image-a", "image-c"), selectedIds(activity))
        assertFalse(selectAll(activity).isChecked)
        assertSummary(activity, selected = 2, total = 3)

        activity.findViewById<View>(R.id.btnCoverTab).performClick()
        assertEquals(COVER_IDS, selectedIds(activity))
        assertTrue(selectAll(activity).isChecked)
        assertEquals("cover", activity.previewSection.buildDownloadRequest()?.selection?.resourceType)
        toggleResource(activity, 0)
        assertEquals(listOf("cover-b"), selectedIds(activity))
        assertSummary(activity, selected = 1, total = 2)

        activity.findViewById<View>(R.id.btnImageTab).performClick()
        assertEquals(listOf(true, false, true), previewCheckStates(activity, 3))
        assertEquals(listOf("image-a", "image-c"), selectedIds(activity))
        assertEquals("image", activity.previewSection.buildDownloadRequest()?.selection?.resourceType)
        assertSummary(activity, selected = 2, total = 3)

        activity.findViewById<View>(R.id.btnCoverTab).performClick()
        assertEquals(listOf(false, true), previewCheckStates(activity, 2))
        assertEquals(listOf("cover-b"), selectedIds(activity))
    }

    @Test
    fun selectAllControlsSavingAndNewPreviewResetsPreviousChoices() = withPreview { activity ->
        selectAll(activity).performClick()
        assertEquals(listOf(false, false, false), previewCheckStates(activity, 3))
        assertFalse(saveButton(activity).isEnabled)
        assertNull(activity.previewSection.buildDownloadRequest())
        assertSummary(activity, selected = 0, total = 3)

        selectAll(activity).performClick()
        assertEquals(listOf(true, true, true), previewCheckStates(activity, 3))
        assertTrue(saveButton(activity).isEnabled)
        assertEquals(IMAGE_IDS, selectedIds(activity))

        toggleResource(activity, 0)
        activity.previewSection.render(SOURCE_URL, preview())
        assertTrue(selectAll(activity).isChecked)
        assertEquals(IMAGE_IDS, selectedIds(activity))
        assertSummary(activity, selected = 3, total = 3)

        selectAll(activity).performClick()
        activity.previewSection.clear()
        assertNull(activity.previewSection.buildDownloadRequest())
        assertFalse(saveButton(activity).isEnabled)
        assertEquals(0, activity.findViewById<ViewGroup>(R.id.thumbContainer).childCount)
        assertEquals(View.GONE, currentCheck(activity).visibility)
        assertFalse(currentCheck(activity).isEnabled)

        activity.previewSection.render(SOURCE_URL, preview())
        assertTrue(selectAll(activity).isChecked)
        assertEquals(IMAGE_IDS, selectedIds(activity))
        assertTrue(saveButton(activity).isEnabled)
    }

    @Test
    fun busyUiDisablesSelectionAndSavingThenRestoresTheSameChoices() = withPreview { activity ->
        toggleResource(activity, 1)
        activity.setUiEnabled(false)

        assertFalse(selectAll(activity).isEnabled)
        assertFalse(currentCheck(activity).isEnabled)
        assertFalse(saveButton(activity).isEnabled)

        activity.setUiEnabled(true)
        assertTrue(selectAll(activity).isEnabled)
        assertTrue(currentCheck(activity).isEnabled)
        assertTrue(saveButton(activity).isEnabled)
        assertEquals(listOf(true, false, true), previewCheckStates(activity, 3))
        assertEquals(listOf("image-a", "image-c"), selectedIds(activity))

        // A finished task must not enable saving when the current tab has no selection.
        toggleResource(activity, 0)
        toggleResource(activity, 2)
        activity.setUiEnabled(false)
        activity.setUiEnabled(true)
        assertFalse(saveButton(activity).isEnabled)
        assertNull(activity.previewSection.buildDownloadRequest())
    }

    @Test
    fun selectedLiveImagesCanBeReplacedByASmallerPreview() = withPreview { activity ->
        val gallery = preview()
        val livePreview = gallery.copy(
            mediaType = "live_photo",
            resources = gallery.resources.map { resource ->
                if (resource.mediaType == "image") {
                    resource.copy(liveVideo = ResolvedLiveVideo(available = true))
                } else {
                    resource
                }
            },
            capabilities = gallery.capabilities.copy(hasLiveVideo = true),
            counts = gallery.counts.copy(liveVideos = IMAGE_IDS.size),
        )
        activity.previewSection.render(SOURCE_URL, livePreview)
        val liveCheck = activity.findViewById<CheckBox>(R.id.checkLiveVideo)
        assertEquals(View.VISIBLE, liveCheck.visibility)
        liveCheck.performClick()
        toggleResource(activity, 1)
        val request = requireNotNull(activity.previewSection.buildDownloadRequest())
        assertTrue(request.selection.includeLiveVideo)
        assertEquals(listOf("image-a", "image-c"), request.selection.resourceIds)

        // Resetting the Live option must not refresh the previous preview's larger thumbnail list.
        val replacement = gallery.copy(
            sourceId = "replacement-test",
            resources = listOf(ResolvedResource(id = "replacement-image", mediaType = "image")),
            capabilities = ResolveCapabilities(hasImages = true),
            counts = ResolveCounts(images = 1),
        )
        activity.previewSection.render(SOURCE_URL, replacement)

        assertFalse(liveCheck.isChecked)
        assertEquals(View.GONE, liveCheck.visibility)
        assertEquals(1, activity.findViewById<ViewGroup>(R.id.thumbContainer).childCount)
        assertTrue(currentCheck(activity).isChecked)
        assertEquals(listOf("replacement-image"), selectedIds(activity))
        assertFalse(requireNotNull(activity.previewSection.buildDownloadRequest()).selection.includeLiveVideo)
        assertSummary(activity, selected = 1, total = 1)
    }

    @Test
    fun appendedResourcesPreserveExistingChoicesOnlyForTheSameSource() = withPreview { activity ->
        toggleResource(activity, 1)
        val gallery = preview()
        val expanded = gallery.copy(
            resources = gallery.resources + ResolvedResource(id = "image-d", index = 3, mediaType = "image"),
            counts = gallery.counts.copy(images = 4),
        )

        activity.previewSection.render(SOURCE_URL, expanded, preserveSelection = true)
        assertEquals(listOf(true, false, true, true), previewCheckStates(activity, 4))
        assertEquals(listOf("image-a", "image-c", "image-d"), selectedIds(activity))
        assertSummary(activity, selected = 3, total = 4)

        activity.findViewById<View>(R.id.btnCoverTab).performClick()
        toggleResource(activity, 0)
        activity.previewSection.render(SOURCE_URL, expanded, preserveSelection = true)
        assertEquals("cover", activity.previewSection.buildDownloadRequest()?.selection?.resourceType)
        assertEquals(listOf("cover-b"), selectedIds(activity))
        assertSummary(activity, selected = 1, total = 2)

        activity.previewSection.render(SOURCE_URL, expanded)
        assertEquals(listOf(true, true, true, true), previewCheckStates(activity, 4))
        assertEquals(IMAGE_IDS + "image-d", selectedIds(activity))

        toggleResource(activity, 1)
        activity.previewSection.render(
            SOURCE_URL,
            expanded.copy(sourceId = "different-source"),
            preserveSelection = true,
        )
        assertEquals(listOf(true, true, true, true), previewCheckStates(activity, 4))
        assertEquals(IMAGE_IDS + "image-d", selectedIds(activity))
        assertSummary(activity, selected = 4, total = 4)
    }

    @Test
    fun currentSelectionFollowsThumbnailsAcrossPagesWithoutChangingOtherChoices() = withPreview { activity ->
        val resources = (0 until 45).map { index ->
            ResolvedResource(id = "paged-image-$index", index = index, mediaType = "image")
        }
        activity.previewSection.render(
            SOURCE_URL,
            preview().copy(resources = resources, counts = ResolveCounts(images = resources.size)),
        )
        assertEquals(resources.map { it.id }, selectedIds(activity))
        toggleResource(activity, 1)
        toggleResource(activity, 44)
        assertFalse(currentCheck(activity).isChecked)
        assertSummary(activity, selected = 43, total = 45)

        thumbnail(activity, 1).performClick()
        assertFalse(currentCheck(activity).isChecked)
        thumbnail(activity, 0).performClick()
        assertTrue(currentCheck(activity).isChecked)
        thumbnail(activity, 44).performClick()
        assertFalse(currentCheck(activity).isChecked)
        assertEquals(resources.filterIndexed { index, _ -> index !in setOf(1, 44) }.map { it.id }, selectedIds(activity))

        selectAll(activity).performClick()
        assertTrue(currentCheck(activity).isChecked)
        thumbnail(activity, 1).performClick()
        assertTrue(currentCheck(activity).isChecked)
        assertEquals(resources.map { it.id }, selectedIds(activity))
        assertSummary(activity, selected = 45, total = 45)
    }

    private fun withPreview(assertions: (MainActivity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clipboard = instrumentation.targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        val activity = startMainActivity(instrumentation)
        try {
            instrumentation.runOnMainSync {
                activity.previewSection.render(SOURCE_URL, preview())
            }
            awaitPreviewLayout(instrumentation, activity)
            instrumentation.runOnMainSync {
                assertions(activity)
            }
            captureScreenshotIfRequested(instrumentation)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun awaitPreviewLayout(instrumentation: Instrumentation, activity: MainActivity) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var initialLayoutLogged = false
        var lastLayout = "Preview has not been laid out"
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync {
                val body = activity.findViewById<View>(R.id.previewBody)
                val selection = currentCheck(activity)
                val section = activity.findViewById<View>(R.id.previewSection)
                val views = listOf(body, selection, selectAll(activity),
                    activity.findViewById<View>(R.id.tvSelectionSummary))
                val laidOut = views.all {
                    it.isLaidOut && !it.isLayoutRequested && it.width > 0 && it.height > 0
                }
                lastLayout = "body=${screenBounds(body)}, all=${screenBounds(selectAll(activity))}, " +
                    "summary=${screenBounds(activity.findViewById(R.id.tvSelectionSummary))}, " +
                    "section scale=(${section.scaleX}, ${section.scaleY}), " +
                    "translationY=${section.translationY}, alpha=${section.alpha}"
                if (laidOut && !initialLayoutLogged) {
                    Log.i("PreviewSelectionTest", "Initial layout: $lastLayout")
                    initialLayoutLogged = true
                }
                // revealFromBelow scales the entire section after layout. Screen coordinates
                // include that transform, but screenBounds uses the unscaled width and height.
                // Wait for the real entrance animation to finish before comparing those bounds.
                ready = laidOut && section.scaleX == 1f && section.scaleY == 1f &&
                    section.translationY == 0f && section.alpha == 1f
            }
            if (ready) {
                Log.i("PreviewSelectionTest", "Settled layout: $lastLayout")
                return
            }
            SystemClock.sleep(16L)
        }
        throw AssertionError("Preview layout or entrance animation did not settle: $lastLayout")
    }

    private fun assertCompactSelectionLayout(activity: MainActivity) {
        val page = activity.findViewById<ViewGroup>(R.id.pageContainer)
        assertEquals("Content should begin without an app-title placeholder", R.id.contentPanel, page.getChildAt(0).id)

        val thumbnails = activity.findViewById<ViewGroup>(R.id.thumbContainer)
        assertFalse("Selection controls must not take up thumbnail space", descendants(thumbnails).any { it is CheckBox })

        val bodyBounds = screenBounds(activity.findViewById(R.id.previewBody))
        val allBounds = screenBounds(selectAll(activity))
        val summaryBounds = screenBounds(activity.findViewById(R.id.tvSelectionSummary))
        listOf(allBounds, summaryBounds).forEach { bounds ->
            assertTrue("Selection controls must appear below the preview: control=$bounds, body=$bodyBounds",
                bounds.top >= bodyBounds.bottom)
        }
        assertTrue("Selected count must follow select all", summaryBounds.left >= allBounds.right)
        val bottomRow = selectAll(activity).parent as ViewGroup
        assertEquals(listOf(selectAll(activity)), descendants(bottomRow).filterIsInstance<CheckBox>().toList())
        assertFalse("Bottom row must not contain numbered selection scrolling", descendants(bottomRow).any { it is HorizontalScrollView })

        val overlay = currentCheck(activity)
        val card = activity.findViewById<ViewGroup>(R.id.previewCard)
        assertEquals("Current-resource selection must overlay the preview card", card, overlay.parent)
        val cardBounds = screenBounds(card)
        val overlayBounds = screenBounds(overlay)
        assertTrue("Current-resource selection must be visible inside the preview", cardBounds.contains(overlayBounds))
        assertTrue("Current-resource selection must be in the right half", overlayBounds.centerX() >= cardBounds.centerX())
        assertTrue("Current-resource selection must be in the bottom half", overlayBounds.centerY() >= cardBounds.centerY())
    }

    private fun descendants(parent: ViewGroup): Sequence<View> = sequence {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            yield(child)
            if (child is ViewGroup) yieldAll(descendants(child))
        }
    }

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun captureScreenshotIfRequested(instrumentation: Instrumentation) {
        if (InstrumentationRegistry.getArguments().getString("captureSelectionScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400L)
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            val output = File(directory, "preview-selection-${SCREENSHOT_SEQUENCE.incrementAndGet()}.png")
            output.outputStream().use { stream ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun startMainActivity(instrumentation: Instrumentation): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    private fun selectAll(activity: MainActivity): CheckBox =
        activity.findViewById(R.id.checkSelectAll)

    private fun saveButton(activity: MainActivity): View =
        activity.findViewById(R.id.btnSaveSheet)

    private fun thumbnail(activity: MainActivity, index: Int): FrameLayout {
        val container = activity.findViewById<ViewGroup>(R.id.thumbContainer)
        repeat(100) {
            container.findViewWithTag<FrameLayout>(index)?.let { return it }
            val children = (0 until container.childCount).map(container::getChildAt)
            val firstIndex = children.mapNotNull { it.tag as? Int }.minOrNull()
                ?: throw AssertionError("Thumbnail page is empty")
            val label = activity.getString(if (index < firstIndex) R.string.x_preview_previous else R.string.x_preview_next)
            val pageButton = children.filterIsInstance<Button>().singleOrNull { it.text.toString() == label }
                ?: throw AssertionError("No thumbnail page contains resource $index")
            pageButton.performClick()
        }
        throw AssertionError("Thumbnail paging did not reach resource $index")
    }

    private fun currentCheck(activity: MainActivity): CheckBox =
        activity.findViewById(R.id.checkPreviewSelected)

    private fun toggleResource(activity: MainActivity, index: Int) {
        thumbnail(activity, index).performClick()
        currentCheck(activity).performClick()
    }

    private fun previewCheckStates(activity: MainActivity, count: Int): List<Boolean> =
        (0 until count).map { index ->
            thumbnail(activity, index).performClick()
            currentCheck(activity).isChecked
        }

    private fun selectedIds(activity: MainActivity): List<String> {
        val request = activity.previewSection.buildDownloadRequest()
        assertNotNull(request)
        return requireNotNull(requireNotNull(request).selection.resourceIds)
    }

    private fun assertSummary(activity: MainActivity, selected: Int, total: Int) {
        val summary = activity.findViewById<TextView>(R.id.tvSelectionSummary).text.toString()
        assertEquals(listOf(selected, total), Regex("\\d+").findAll(summary).map { it.value.toInt() }.toList())
    }

    private fun preview() = PyResolveResult(
        ok = true,
        message = "ok",
        sourceUrl = SOURCE_URL,
        sourceId = "selection-test",
        title = "Selection fixture",
        mediaType = "gallery",
        resources = IMAGE_IDS.mapIndexed { index, id ->
            ResolvedResource(id = id, index = index, mediaType = "image")
        } + COVER_IDS.mapIndexed { index, id ->
            ResolvedResource(id = id, index = index, mediaType = "cover")
        },
        capabilities = ResolveCapabilities(hasImages = true, hasCover = true),
        counts = ResolveCounts(images = IMAGE_IDS.size, covers = COVER_IDS.size),
    )

    private companion object {
        const val SOURCE_URL = "https://www.douyin.com/note/123456789"
        val IMAGE_IDS = listOf("image-a", "image-b", "image-c")
        val COVER_IDS = listOf("cover-a", "cover-b")
        val SCREENSHOT_SEQUENCE = AtomicInteger()
    }
}
