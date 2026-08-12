package com.zemin.downloader.common

import com.zemin.downloader.common.bean.ApiMetric
import com.zemin.downloader.common.bean.DownloadMetric

interface IDownloadResult

open class PyDownloadResult(
    val ok: Boolean,
    val message: String,
    val error: String?,
    val outputDir: String?,
    val files: List<String> = emptyList(),
    val success: Int,
    val failed: Int,
    val skipped: Int,
    val timings: Map<String, Int>,
    val downloadMetrics: List<DownloadMetric>,
    val apiMetrics: List<ApiMetric>
) : IDownloadResult

open class PyResolveResult(
    val ok: Boolean,
    val message: String,
    val error: String?,
    val sourceUrl: String?,
    val sourceId: String?,
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val mediaType: String?,
    val resources: List<ResolvedResource> = emptyList(),
    val schemaVersion: Int = 1,
    val capabilities: ResolveCapabilities = ResolveCapabilities(),
    val counts: ResolveCounts = ResolveCounts(),
) : IDownloadResult

data class ResolvedResource(
    val id: String = "",
    val index: Int = 0,
    val title: String = "",
    val mediaType: String = "",
    val previewUrls: List<String> = emptyList(),
    val downloadUrls: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val formatHint: String? = null,
    val liveVideo: ResolvedLiveVideo? = null,
    val selected: Boolean = true,
)

data class ResolvedLiveVideo(
    val available: Boolean = false,
    val downloadUrls: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val formatHint: String? = null,
)

data class ResolveCapabilities(
    val hasVideo: Boolean = false,
    val hasImages: Boolean = false,
    val hasCover: Boolean = false,
    val hasAudio: Boolean = false,
    val hasLiveVideo: Boolean = false,
)

data class ResolveCounts(
    val videos: Int = 0,
    val images: Int = 0,
    val covers: Int = 0,
    val audios: Int = 0,
    val liveVideos: Int = 0,
)
