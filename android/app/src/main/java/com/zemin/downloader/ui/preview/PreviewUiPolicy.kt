package com.zemin.downloader.ui.preview

import com.zemin.downloader.common.PyResolveResult

enum class ResourceTab(val resourceType: String) {
    ALL("all"),
    VIDEO("video"),
    IMAGE("image"),
    COVER("cover"),
    AUDIO("audio"),
}

data class PreviewUiState(
    val tabs: List<ResourceTab>,
    val defaultTab: ResourceTab,
)

object PreviewUiPolicy {
    fun stateFor(preview: PyResolveResult): PreviewUiState {
        if (preview.mediaType == "mixed" || preview.collection != null) {
            val tabs = listOf(ResourceTab.ALL, ResourceTab.VIDEO, ResourceTab.IMAGE)
                .filter { resourcesFor(preview, it).isNotEmpty() }
            return PreviewUiState(tabs, tabs.firstOrNull() ?: ResourceTab.ALL)
        }
        val primary = if (preview.mediaType == "video") ResourceTab.VIDEO else ResourceTab.IMAGE
        val tabs = buildList {
            if (resourcesFor(preview, primary).isNotEmpty()) add(primary)
            if (resourcesFor(preview, ResourceTab.COVER).isNotEmpty()) add(ResourceTab.COVER)
            if (resourcesFor(preview, ResourceTab.AUDIO).isNotEmpty()) add(ResourceTab.AUDIO)
        }
        val safeTabs = tabs.ifEmpty {
            listOfNotNull(
                ResourceTab.IMAGE.takeIf { resourcesFor(preview, it).isNotEmpty() },
                ResourceTab.VIDEO.takeIf { resourcesFor(preview, it).isNotEmpty() },
            )
        }
        return PreviewUiState(
            tabs = safeTabs,
            defaultTab = safeTabs.firstOrNull() ?: primary,
        )
    }

    fun shouldShowLiveOption(preview: PyResolveResult, activeTab: ResourceTab): Boolean =
        (preview.mediaType == "live_photo" || preview.collection != null) &&
            activeTab in setOf(ResourceTab.IMAGE, ResourceTab.ALL) &&
            preview.capabilities.hasLiveVideo &&
            preview.counts.liveVideos > 0

    fun resourcesFor(preview: PyResolveResult, tab: ResourceTab) =
        preview.resources.filter {
            if (tab == ResourceTab.ALL) it.mediaType in setOf("video", "image")
            else it.mediaType == tab.resourceType
        }
}
