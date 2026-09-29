package com.zemin.downloader.ui.preview

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.zemin.downloader.R
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.bean.DownloadRequest
import com.zemin.downloader.common.bean.DownloadSelection
import com.zemin.downloader.common.bean.DownloadSnapshot
import com.zemin.downloader.common.bean.DownloadSnapshotLiveVideo
import com.zemin.downloader.common.bean.DownloadSnapshotResource
import com.zemin.downloader.common.bean.DownloadSource
import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.common.core.currentTitle
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.motion.UiMotion
import kotlinx.coroutines.CoroutineScope

/**
 * 预览区（标签页、缩略图、大图/视频预览、保存选择）的渲染与状态。
 * 只做展示；解析与下载编排见 [com.zemin.downloader.ui.download.DownloadFlowController]。
 */
class PreviewSectionRenderer(
    private val host: MainActivity,
    private val scope: CoroutineScope,
) {
    private val ui get() = host.ui
    private val context: Context get() = host

    private lateinit var previewImages: PreviewImageController
    private var currentPreview: PyResolveResult? = null
    private var currentPreviewInput: String? = null
    private var selectedResourceTab: ResourceTab = ResourceTab.IMAGE
    private var availableResourceTabs: List<ResourceTab> = emptyList()
    private var selectedPreviewIndex = 0

    val preview: PyResolveResult? get() = currentPreview
    val input: String? get() = currentPreviewInput
    val isLiveVideoSelected: Boolean
        get() = ui.checkLiveVideo.visibility == View.VISIBLE && ui.checkLiveVideo.isChecked

    fun setup() {
        previewImages = PreviewImageController(
            context = host,
            lifecycleOwner = host,
            scope = scope,
            previewCard = ui.previewCard,
            ambientView = ui.ivPreviewAmbient,
            ambientScrim = ui.previewAmbientScrim,
            currentView = ui.ivPreviewCover,
            incomingView = ui.ivPreviewCoverIncoming,
        )
        ui.btnImageTab.setOnClickListener { selectResourceTab(tabAt(0), userInitiated = true) }
        ui.btnCoverTab.setOnClickListener { selectResourceTab(tabAt(1), userInitiated = true) }
        ui.btnAudioTab.setOnClickListener { selectResourceTab(tabAt(2), userInitiated = true) }
        ui.checkLiveVideo.setOnCheckedChangeListener { button, _ ->
            if (button.isPressed) UiMotion.performHaptic(button, UiMotion.Haptic.TICK)
            refreshSelectionUi()
        }
    }

    fun dispose() {
        if (this::previewImages.isInitialized) previewImages.dispose()
    }

    fun render(inputText: String, preview: PyResolveResult) {
        val mediaTypeText = formatMediaType(preview.mediaType)
        val selectedResourceCount = preview.resources.size
        val title = preview.title.orEmpty().ifBlank { host.getString(R.string.main_preview_title_fallback) }
        val author = preview.author.orEmpty().ifBlank { currentTitle }
        val uiState = PreviewUiPolicy.stateFor(preview)
        if (uiState.tabs.isEmpty()) {
            clear()
            host.showError(host.getString(R.string.main_error_no_resources))
            return
        }

        currentPreview = preview
        currentPreviewInput = inputText
        ui.tvPreviewTitle.text = title
        ui.tvPreviewMeta.text = host.getString(
            R.string.main_preview_meta_format,
            author,
            mediaTypeText,
            selectedResourceCount,
        )
        configureTabButtons(uiState.tabs, preview)
        host.refreshActionButton()
        selectedResourceTab = uiState.defaultTab
        ui.checkLiveVideo.isChecked = false
        selectResourceTab(selectedResourceTab, userInitiated = false)
        UiMotion.revealFromBelow(ui.previewSection)
        UiMotion.performHaptic(ui.previewSection, UiMotion.Haptic.CONFIRM)
    }

    fun clear() {
        currentPreview = null
        currentPreviewInput = null
        UiMotion.concealBelow(ui.previewSection)
        previewImages.clear()
        ui.videoPreview.stopPlayback()
        ui.videoPreview.visibility = View.GONE
        ui.thumbContainer.removeAllViews()
        selectedPreviewIndex = 0
        ui.checkLiveVideo.isChecked = false
        ui.checkLiveVideo.visibility = View.GONE
        ui.previewTabIndicator.visibility = View.INVISIBLE
        availableResourceTabs = emptyList()
        host.hideSheets()
        host.refreshActionButton()
    }

    private fun selectResourceTab(tab: ResourceTab?, userInitiated: Boolean) {
        val selectedTab = tab ?: return
        val preview = currentPreview ?: return
        val resources = PreviewUiPolicy.resourcesFor(preview, selectedTab)
        if (resources.isEmpty()) return

        val changed = selectedResourceTab != selectedTab
        selectedResourceTab = selectedTab
        ui.btnImageTab.isSelected = tabAt(0) == selectedTab
        ui.btnCoverTab.isSelected = tabAt(1) == selectedTab
        ui.btnAudioTab.isSelected = tabAt(2) == selectedTab
        val selectedButton = listOf(
            ui.btnImageTab,
            ui.btnCoverTab,
            ui.btnAudioTab,
        )[availableResourceTabs.indexOf(selectedTab)]
        val updateIndicator = {
            if (currentPreview === preview && selectedResourceTab == selectedTab) {
                UiMotion.animateTabIndicator(
                    ui.previewTabIndicator,
                    selectedButton,
                    animated = userInitiated && changed,
                )
            }
        }
        if (userInitiated) updateIndicator() else ui.previewTabButtons.post { updateIndicator() }
        updatePreviewLabels(0, resources.size, selectedTab)
        selectedPreviewIndex = 0
        renderThumbnails(resources, selectedTab)
        updatePreviewResource(0, resources, selectedTab)
        refreshSelectionUi()
        if (userInitiated && changed) {
            UiMotion.performHaptic(selectedButton, UiMotion.Haptic.TICK)
        }
    }

    private fun configureTabButtons(tabs: List<ResourceTab>, preview: PyResolveResult) {
        availableResourceTabs = tabs
        val buttons = listOf(ui.btnImageTab, ui.btnCoverTab, ui.btnAudioTab)
        buttons.forEachIndexed { index, button ->
            val tab = tabs.getOrNull(index)
            button.visibility = if (tab == null) View.GONE else View.VISIBLE
            if (tab != null) {
                val count = PreviewUiPolicy.resourcesFor(preview, tab).size
                button.text = when (tab) {
                    ResourceTab.VIDEO -> host.getString(R.string.main_preview_tab_primary_video, count)
                    ResourceTab.IMAGE -> host.getString(R.string.main_preview_tab_image, count)
                    ResourceTab.COVER -> host.getString(R.string.main_preview_tab_cover, count)
                    ResourceTab.AUDIO -> host.getString(R.string.main_preview_tab_audio, count)
                }
            }
        }
    }

    private fun tabAt(index: Int): ResourceTab? = availableResourceTabs.getOrNull(index)

    private fun updatePreviewLabels(
        index: Int,
        total: Int,
        tab: ResourceTab,
    ) {
        ui.tvPreviewCounter.text = when (tab) {
            ResourceTab.IMAGE -> host.getString(
                R.string.main_preview_counter_image_format,
                index + 1,
                total,
            )
            ResourceTab.VIDEO -> host.getString(
                R.string.main_preview_counter_video_format,
                index + 1,
                total,
            )
            ResourceTab.COVER -> host.getString(R.string.main_preview_counter_cover)
            ResourceTab.AUDIO -> host.getString(R.string.main_preview_tab_audio, total)
        }
    }

    private fun refreshSelectionUi() {
        val preview = currentPreview ?: return
        val showLive = PreviewUiPolicy.shouldShowLiveOption(preview, selectedResourceTab)
        if (!showLive && ui.checkLiveVideo.isChecked) {
            ui.checkLiveVideo.isChecked = false
        }
        ui.checkLiveVideo.visibility = if (showLive) View.VISIBLE else View.GONE

        val resourceCount = PreviewUiPolicy.resourcesFor(preview, selectedResourceTab).size
        val includeLive = showLive && ui.checkLiveVideo.isChecked
        ui.btnSaveSheet.text = when (selectedResourceTab) {
            ResourceTab.VIDEO -> host.getString(R.string.main_save_video)
            ResourceTab.IMAGE -> if (includeLive) {
                host.getString(R.string.main_save_images_live)
            } else {
                host.getString(R.string.main_save_images, resourceCount)
            }
            ResourceTab.COVER -> host.getString(R.string.main_save_cover)
            ResourceTab.AUDIO -> host.getString(R.string.main_save_audio)
        }
    }

    fun buildDownloadRequest(): DownloadRequest? {
        val preview = currentPreview ?: return null
        if (preview.schemaVersion != 2) return null
        val sourceUrl = preview.sourceUrl?.takeIf { it.isNotBlank() }
            ?: currentPreviewInput.orEmpty()
        val sourceId = preview.sourceId.orEmpty()
        if (sourceUrl.isBlank() || sourceId.isBlank()) return null
        val snapshotResources = preview.resources.asSequence()
            .filter { it.id.isNotBlank() && it.mediaType in setOf("video", "image", "cover", "audio") }
            .mapNotNull { resource ->
                val downloadUrls = resource.downloadUrls
                    .filter { it.startsWith("https://") }
                    .distinct()
                    .take(8)
                if (downloadUrls.isEmpty()) return@mapNotNull null
                DownloadSnapshotResource(
                    id = resource.id,
                    index = resource.index,
                    type = resource.mediaType,
                    title = resource.title,
                    downloadUrls = downloadUrls,
                    width = resource.width,
                    height = resource.height,
                    durationMs = resource.durationMs,
                    formatHint = resource.formatHint,
                    liveVideo = resource.liveVideo?.let { live ->
                        DownloadSnapshotLiveVideo(
                            available = live.available,
                            downloadUrls = live.downloadUrls
                                .filter { it.startsWith("https://") }
                                .distinct()
                                .take(8),
                            width = live.width,
                            height = live.height,
                            durationMs = live.durationMs,
                            formatHint = live.formatHint,
                        )
                    },
                )
            }
            .take(100)
            .toList()
        return DownloadRequest(
            source = DownloadSource(
                platform = when (currentDownloadType) {
                    DownloadType.DOU_YIN -> "douyin"
                    DownloadType.XIAO_HONG_SHU -> "xiaohongshu"
                    DownloadType.TWITTER -> "x"
                },
                url = sourceUrl,
                id = sourceId,
            ),
            expectedWorkType = preview.mediaType.orEmpty(),
            selection = DownloadSelection(
                resourceType = selectedResourceTab.resourceType,
                includeLiveVideo = isLiveVideoSelected,
            ),
            snapshot = if (
                currentDownloadType == DownloadType.DOU_YIN && snapshotResources.isNotEmpty()
            ) {
                DownloadSnapshot(
                    sourceId = sourceId,
                    title = preview.title.orEmpty(),
                    author = preview.author.orEmpty(),
                    workType = preview.mediaType.orEmpty(),
                    resources = snapshotResources,
                )
            } else {
                null
            },
        )
    }

    private fun renderThumbnails(resources: List<ResolvedResource>, tab: ResourceTab) {
        previewImages.clearThumbnails()
        ui.thumbContainer.removeAllViews()
        resources.forEachIndexed { index, resource ->
            val thumb = FrameLayout(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    host.dp(62),
                ).also {
                    if (index > 0) it.topMargin = host.dp(5)
                }
                setBackgroundResource(
                    if (index == selectedPreviewIndex) R.drawable.bg_thumb_selected
                    else R.drawable.bg_thumb
                )
                setOnClickListener {
                    val changed = selectedPreviewIndex != index
                    updatePreviewResource(index, resources, tab)
                    if (changed) {
                        UiMotion.performHaptic(this, UiMotion.Haptic.TICK)
                    }
                }
            }
            val imageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val fallback = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                text = when (resource.mediaType) {
                    "video" -> "▶"
                    "cover" -> host.getString(R.string.main_preview_resource_cover)
                    "audio" -> host.getString(R.string.main_preview_resource_audio)
                    else -> (index + 1).toString()
                }
            }
            thumb.addView(imageView)
            thumb.addView(fallback)
            ui.thumbContainer.addView(thumb)
            val thumbUrl = thumbnailUrl(resource, currentPreview)
            if (thumbUrl.isNotBlank()) {
                previewImages.loadThumbnail(
                    imageUrl = thumbUrl,
                    imageView = imageView,
                    fallback = fallback,
                    headers = previewRequestHeaders(),
                )
            }
        }
    }

    private fun updatePreviewResource(
        index: Int,
        resources: List<ResolvedResource>,
        tab: ResourceTab,
    ) {
        val preview = currentPreview ?: return
        val resource = resources.getOrNull(index) ?: return
        selectedPreviewIndex = index
        updateThumbnailSelection(index)
        updatePreviewLabels(index, resources.size, tab)
        if (tab == ResourceTab.VIDEO) {
            playPreviewVideo(resource)
        } else {
            stopPreviewVideo()
            loadPreviewImage(preview, resource, resources, index)
        }
    }

    private fun formatMediaType(mediaType: String?): String = when (mediaType) {
        "video" -> host.getString(R.string.main_media_type_video)
        "gallery" -> host.getString(R.string.main_media_type_gallery)
        "live_photo" -> host.getString(R.string.main_media_type_live_photo)
        else -> host.getString(R.string.main_media_type_unknown)
    }

    private fun loadPreviewImage(
        preview: PyResolveResult,
        resource: ResolvedResource? = null,
        resources: List<ResolvedResource> = emptyList(),
        index: Int = 0,
    ) {
        val imageUrl = thumbnailUrl(resource, preview)
        if (imageUrl.isBlank()) {
            previewImages.hideForVideo()
            return
        }
        val adjacentUrls = listOf(index - 1, index + 1)
            .mapNotNull(resources::getOrNull)
            .map { thumbnailUrl(it, preview) }
            .filter { it.isNotBlank() && it != imageUrl }
        previewImages.load(
            imageUrl = imageUrl,
            adjacentUrls = adjacentUrls,
            headers = previewRequestHeaders(),
        )
    }

    internal fun loadPreviewImageForTesting(imageUrl: String) {
        ui.previewSection.visibility = View.VISIBLE
        previewImages.load(
            imageUrl = imageUrl,
            adjacentUrls = emptyList(),
            headers = emptyMap(),
        )
    }

    private fun playPreviewVideo(resource: ResolvedResource) {
        val videoUrl = resource.downloadUrls.firstOrNull().orEmpty()
        previewImages.hideForVideo()
        if (videoUrl.isBlank()) {
            stopPreviewVideo()
            ui.videoPreview.showUnavailable()
            return
        }
        ui.videoPreview.visibility = View.VISIBLE
        ui.videoPreview.setVideo(
            uri = Uri.parse(videoUrl),
            headers = previewRequestHeaders(),
            autoPlay = true,
        )
    }

    private fun stopPreviewVideo() {
        ui.videoPreview.stopPlayback()
        ui.videoPreview.clearVideoSize()
        ui.videoPreview.visibility = View.GONE
    }

    private fun thumbnailUrl(resource: ResolvedResource?, preview: PyResolveResult?): String {
        val direct = resource?.previewUrls?.firstOrNull().orEmpty()
            .ifBlank { resource?.downloadUrls?.firstOrNull().orEmpty() }
        if (resource?.mediaType == "image" || resource?.mediaType == "cover") return direct
        return preview?.coverUrl.orEmpty().ifBlank {
            preview?.resources
                ?.firstOrNull { it.mediaType == "image" || it.mediaType == "cover" }
                ?.downloadUrls
                ?.firstOrNull()
                .orEmpty()
        }
    }

    private fun previewRequestHeaders(): Map<String, String> =
        PreviewRequestPolicy.headersFor(currentDownloadType)

    private fun updateThumbnailSelection(index: Int) {
        for (childIndex in 0 until ui.thumbContainer.childCount) {
            ui.thumbContainer.getChildAt(childIndex).setBackgroundResource(
                if (childIndex == index) R.drawable.bg_thumb_selected else R.drawable.bg_thumb
            )
        }
        ui.thumbScroll.post {
            val selected = ui.thumbContainer.getChildAt(index) ?: return@post
            val targetY = (selected.top - (ui.thumbScroll.height - selected.height) / 2)
                .coerceAtLeast(0)
            ui.thumbScroll.smoothScrollTo(0, targetY)
        }
    }
}
