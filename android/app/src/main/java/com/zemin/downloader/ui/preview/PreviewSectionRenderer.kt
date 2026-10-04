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
    private val selectedResourceIds = mutableSetOf<String>()
    private var selectionEnabled = true
    private var updatingSelectionUi = false

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
        ui.videoPreview.setBottomOverlayInset(host.dp(56))
        ui.btnImageTab.setOnClickListener { selectResourceTab(tabAt(0), userInitiated = true) }
        ui.btnCoverTab.setOnClickListener { selectResourceTab(tabAt(1), userInitiated = true) }
        ui.btnAudioTab.setOnClickListener { selectResourceTab(tabAt(2), userInitiated = true) }
        ui.checkLiveVideo.setOnCheckedChangeListener { button, _ ->
            if (button.isPressed) UiMotion.performHaptic(button, UiMotion.Haptic.TICK)
            if (!updatingSelectionUi) refreshSelectionUi()
        }
        ui.checkSelectAll.setOnCheckedChangeListener { button, checked ->
            if (!updatingSelectionUi) {
                val resources = currentPreview?.let { PreviewUiPolicy.resourcesFor(it, selectedResourceTab) }.orEmpty()
                resources.forEach { resource ->
                    if (checked) selectedResourceIds.add(resource.id) else selectedResourceIds.remove(resource.id)
                }
                if (button.isPressed) UiMotion.performHaptic(button, UiMotion.Haptic.TICK)
                refreshSelectionUi()
            }
        }
        ui.checkPreviewSelected.setOnCheckedChangeListener { button, checked ->
            if (!updatingSelectionUi) {
                val resource = currentPreview?.let { PreviewUiPolicy.resourcesFor(it, selectedResourceTab) }
                    ?.getOrNull(selectedPreviewIndex) ?: return@setOnCheckedChangeListener
                if (checked) selectedResourceIds.add(resource.id) else selectedResourceIds.remove(resource.id)
                if (button.isPressed) UiMotion.performHaptic(button, UiMotion.Haptic.TICK)
                refreshSelectionUi()
            }
        }
    }

    fun dispose() {
        if (this::previewImages.isInitialized) previewImages.dispose()
    }

    fun render(inputText: String, preview: PyResolveResult, preserveSelection: Boolean = false) {
        val mediaTypeText = formatMediaType(preview.mediaType)
        val selectedResourceCount = preview.resources.size
        val title = preview.title.orEmpty().ifBlank { host.getString(R.string.main_preview_title_fallback) }
        val author = preview.author.orEmpty().ifBlank { currentTitle }
        val uiState = PreviewUiPolicy.stateFor(preview)
        if (uiState.tabs.isEmpty() && preview.collection == null) {
            clear()
            host.showError(host.getString(R.string.main_error_no_resources))
            return
        }

        val previousResourceIds = currentPreview?.resources.orEmpty().map { it.id }.toSet()
        val resourceIds = preview.resources.map { it.id }.toSet()
        val keepSelection = preserveSelection && currentPreview?.sourceId == preview.sourceId
        if (keepSelection) {
            selectedResourceIds.retainAll(resourceIds)
            selectedResourceIds.addAll(resourceIds - previousResourceIds)
        } else {
            selectedResourceIds.clear()
            selectedResourceIds.addAll(resourceIds)
        }
        currentPreview = preview
        currentPreviewInput = inputText
        ui.tvCollectionStatus.visibility = if (preview.collection == null) View.GONE else View.VISIBLE
        ui.tvCollectionStatus.text = host.getString(
            R.string.x_collection_status, preview.resources.size, preview.message,
        )
        val canContinue = !preview.collection?.nextCursor.isNullOrBlank()
        ui.btnContinueCollection.visibility = if (canContinue) View.VISIBLE else View.GONE
        ui.btnContinueCollection.text = host.getString(R.string.x_continue_collection)
        ui.btnContinueCollection.setOnClickListener {
            host.downloadFlow.resolveAndRenderPreview(inputText, continueCollection = true)
        }

        ui.tvPreviewTitle.text = title
        ui.tvPreviewMeta.text = host.getString(
            R.string.main_preview_meta_format,
            author,
            mediaTypeText,
            selectedResourceCount,
        )
        configureTabButtons(uiState.tabs, preview)
        host.refreshActionButton()
        if (!keepSelection || selectedResourceTab !in uiState.tabs) {
            selectedResourceTab = uiState.defaultTab
        }
        updatingSelectionUi = true
        if (!keepSelection) ui.checkLiveVideo.isChecked = false
        updatingSelectionUi = false
        selectResourceTab(selectedResourceTab, userInitiated = false)
        UiMotion.revealFromBelow(ui.previewSection)
        UiMotion.performHaptic(ui.previewSection, UiMotion.Haptic.CONFIRM)
    }

    fun clear() {
        currentPreview = null
        currentPreviewInput = null
        selectedResourceIds.clear()
        UiMotion.concealBelow(ui.previewSection)
        previewImages.clear()
        ui.videoPreview.stopPlayback()
        ui.videoPreview.visibility = View.GONE
        ui.thumbContainer.removeAllViews()
        ui.checkPreviewSelected.visibility = View.GONE
        ui.checkPreviewSelected.isEnabled = false
        ui.checkPreviewSelected.isChecked = false
        selectedPreviewIndex = 0
        ui.checkLiveVideo.isChecked = false
        ui.checkLiveVideo.visibility = View.GONE
        ui.previewTabIndicator.visibility = View.INVISIBLE
        availableResourceTabs = emptyList()
        ui.btnSaveSheet.isEnabled = false
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
                    ResourceTab.ALL -> host.getString(R.string.x_tab_all, count)
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
            ResourceTab.ALL -> host.getString(R.string.x_media_counter, index + 1, total)
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

        val resources = PreviewUiPolicy.resourcesFor(preview, selectedResourceTab)
        val resourceCount = resources.count { it.id in selectedResourceIds }
        updatingSelectionUi = true
        ui.checkSelectAll.isChecked = resources.isNotEmpty() && resourceCount == resources.size
        ui.checkSelectAll.isEnabled = selectionEnabled
        val currentResource = resources.getOrNull(selectedPreviewIndex)
        ui.checkPreviewSelected.visibility = if (currentResource == null) View.GONE else View.VISIBLE
        ui.checkPreviewSelected.isChecked = currentResource?.id in selectedResourceIds
        ui.checkPreviewSelected.isEnabled = selectionEnabled && currentResource != null
        ui.checkPreviewSelected.contentDescription = currentResource?.let {
            resourceSelectionDescription(it, selectedPreviewIndex)
        }
        updatingSelectionUi = false
        ui.tvSelectionSummary.text = host.getString(R.string.main_selection_summary, resourceCount, resources.size)
        ui.checkLiveVideo.isEnabled = selectionEnabled && resourceCount > 0
        ui.btnSaveSheet.isEnabled = selectionEnabled && resourceCount > 0
        val includeLive = showLive && ui.checkLiveVideo.isChecked
        ui.btnSaveSheet.text = when (selectedResourceTab) {
            ResourceTab.ALL -> host.getString(
                if (includeLive) R.string.profile_save_all_live else R.string.x_save_all, resourceCount,
            )
            ResourceTab.VIDEO -> host.getString(R.string.x_save_videos, resourceCount)
            ResourceTab.IMAGE -> if (includeLive) {
                host.getString(R.string.main_save_images_live, resourceCount)
            } else {
                host.getString(R.string.main_save_images, resourceCount)
            }
            ResourceTab.COVER -> host.getString(R.string.main_save_cover)
            ResourceTab.AUDIO -> host.getString(R.string.main_save_audio)
        }
    }

    fun setSelectionEnabled(enabled: Boolean) {
        selectionEnabled = enabled
        refreshSelectionUi()
    }

    fun buildDownloadRequest(): DownloadRequest? {
        val preview = currentPreview ?: return null
        if (preview.schemaVersion != 2) return null
        val resourceIds = PreviewUiPolicy.resourcesFor(preview, selectedResourceTab)
            .filter { it.id in selectedResourceIds }
            .map { it.id }
        if (resourceIds.isEmpty() || resourceIds.any { it.isBlank() }) return null
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
            .take(if (preview.collection != null || currentDownloadType == DownloadType.TWITTER) Int.MAX_VALUE else 100)
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
                resourceIds = resourceIds,
            ),
            snapshot = if (
                currentDownloadType in setOf(DownloadType.DOU_YIN, DownloadType.TWITTER) &&
                    snapshotResources.isNotEmpty()
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
        val pageStart = selectedPreviewIndex / THUMB_PAGE_SIZE * THUMB_PAGE_SIZE
        resources.withIndex().drop(pageStart).take(THUMB_PAGE_SIZE).forEach { (index, resource) ->
            val thumb = FrameLayout(context).apply {
                tag = index
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
                    host.dp(62),
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val fallback = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    host.dp(62),
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
            thumb.addView(TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                )
                text = (index + 1).toString()
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(host.dp(4), 0, host.dp(4), 0)
                setBackgroundResource(R.drawable.bg_counter)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
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
        if (pageStart > 0) addThumbnailPageButton(host.getString(R.string.x_preview_previous),
            pageStart - THUMB_PAGE_SIZE, resources, tab)
        if (pageStart + THUMB_PAGE_SIZE < resources.size) addThumbnailPageButton(
            host.getString(R.string.x_preview_next), pageStart + THUMB_PAGE_SIZE, resources, tab)
    }

    private fun resourceSelectionDescription(resource: ResolvedResource, index: Int): String = host.getString(
        R.string.main_select_resource,
        host.getString(when (resource.mediaType) {
            "video" -> R.string.main_preview_resource_video
            "cover" -> R.string.main_preview_resource_cover
            "audio" -> R.string.main_preview_resource_audio
            else -> R.string.main_preview_resource_image
        }),
        index + 1,
    )

    private fun addThumbnailPageButton(label: String, index: Int, resources: List<ResolvedResource>, tab: ResourceTab) {
        ui.thumbContainer.addView(android.widget.Button(context).apply {
            text = label
            textSize = 10f
            setOnClickListener {
                selectedPreviewIndex = index
                renderThumbnails(resources, tab)
                updatePreviewResource(index, resources, tab)
            }
        })
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
        refreshSelectionUi()
        val live = resource.liveVideo?.takeIf { it.available && it.downloadUrls.isNotEmpty() }
        when {
            resource.mediaType == "video" -> playPreviewVideo(resource)
            live != null -> playPreviewVideo(
                resource.copy(downloadUrls = live.downloadUrls),
                ambientImageUrl = thumbnailUrl(resource, preview),
            ) {
                stopPreviewVideo()
                loadPreviewImage(preview, resource, resources, index)
            }
            else -> {
                stopPreviewVideo()
                loadPreviewImage(preview, resource, resources, index)
            }
        }
    }

    private fun formatMediaType(mediaType: String?): String = when (mediaType) {
        "video" -> host.getString(R.string.main_media_type_video)
        "gallery" -> host.getString(R.string.main_media_type_gallery)
        "mixed" -> host.getString(R.string.x_media_mixed)
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

    private fun playPreviewVideo(
        resource: ResolvedResource,
        ambientImageUrl: String? = null,
        onError: (() -> Unit)? = null,
    ) {
        val videoUrl = resource.downloadUrls.firstOrNull().orEmpty()
        val useAmbientBackground = !ambientImageUrl.isNullOrBlank()
        if (useAmbientBackground) {
            previewImages.loadAmbient(
                imageUrl = requireNotNull(ambientImageUrl),
                adjacentUrls = emptyList(),
                headers = previewRequestHeaders(),
            )
        } else {
            previewImages.hideForVideo()
        }
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
            useAmbientBackground = useAmbientBackground,
            onError = onError,
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
                if (ui.thumbContainer.getChildAt(childIndex).tag == index) R.drawable.bg_thumb_selected
                else R.drawable.bg_thumb
            )
        }
        ui.thumbScroll.post {
            val selected = ui.thumbContainer.findViewWithTag<View>(index) ?: return@post
            val targetY = (selected.top - (ui.thumbScroll.height - selected.height) / 2)
                .coerceAtLeast(0)
            ui.thumbScroll.smoothScrollTo(0, targetY)
        }
    }
    private companion object {
        const val THUMB_PAGE_SIZE = 40
    }

}
