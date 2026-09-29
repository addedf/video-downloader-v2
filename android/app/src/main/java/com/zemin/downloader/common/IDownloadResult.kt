package com.zemin.downloader.common

import com.zemin.downloader.common.bean.ApiMetric
import com.zemin.downloader.common.bean.DownloadMetric
import com.zemin.downloader.common.bean.PyDiagnosticsResponse

interface IDownloadResult

data class PyDownloadResult(
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
    val apiMetrics: List<ApiMetric>,
    val diagnostics: PyDiagnosticsResponse? = null
) : IDownloadResult

data class PyResolveResult(
    val ok: Boolean,
    val message: String,
    val error: String? = null,
    val sourceUrl: String? = null,
    val sourceId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val mediaType: String? = null,
    val resources: List<ResolvedResource> = emptyList(),
    val schemaVersion: Int = 2,
    val capabilities: ResolveCapabilities = ResolveCapabilities(),
    val counts: ResolveCounts = ResolveCounts(),
    val timings: Map<String, Int> = emptyMap(),
    val diagnostics: PyDiagnosticsResponse? = null,
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
