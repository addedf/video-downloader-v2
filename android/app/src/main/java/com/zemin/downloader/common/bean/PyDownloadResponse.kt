package com.zemin.downloader.common.bean

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class PyDownloadResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    @Json(name = "output_dir") val outputDir: String? = null,
    val files: List<String> = emptyList(),
    val success: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val timings: Map<String, Int> = emptyMap(),
    @Json(name = "download_metrics") val downloadMetrics: List<DownloadMetric> = emptyList(),
    @Json(name = "api_metrics") val apiMetrics: List<ApiMetric> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class ApiMetric(
    val name: String = "", @Json(name = "duration_ms") val durationMs: Int = 0
)

@JsonClass(generateAdapter = false)
data class DownloadMetric(
    val ok: Boolean = false,
    val host: String? = null,
    @Json(name = "final_host") val finalHost: String? = null,
    val bytes: Long = 0L,
    @Json(name = "duration_ms") val durationMs: Int = 0,
    @Json(name = "first_chunk_ms") val firstChunkMs: Int = 0,
    @Json(name = "speed_kbps") val speedKbps: Int = 0
)

