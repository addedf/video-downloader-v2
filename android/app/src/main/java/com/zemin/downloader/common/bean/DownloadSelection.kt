package com.zemin.downloader.common.bean

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class DownloadRequest(
    @Json(name = "schema_version") val schemaVersion: Int = 2,
    val source: DownloadSource,
    @Json(name = "expected_work_type") val expectedWorkType: String,
    val selection: DownloadSelection,
    val snapshot: DownloadSnapshot? = null,
)

@JsonClass(generateAdapter = false)
data class DownloadSource(
    val platform: String = "douyin",
    val url: String,
    val id: String,
)

/**
 * 用户的保存选择。resourceIds 为 null 时兼容整类保存；空列表表示未选择资源。
 */
@JsonClass(generateAdapter = false)
data class DownloadSelection(
    @Json(name = "resource_type") val resourceType: String,
    @Json(name = "include_live_video") val includeLiveVideo: Boolean = false,
    @Json(name = "resource_ids") val resourceIds: List<String>? = null,
)

/**
 * Minimal, already-resolved media snapshot carried from preview to download.
 *
 * Douyin public share pages can intermittently return a JavaScript WAF page.
 * Reusing the preview's validated HTTPS candidates avoids resolving the same
 * work a second time when the user taps Save.
 */
@JsonClass(generateAdapter = false)
data class DownloadSnapshot(
    @Json(name = "source_id") val sourceId: String,
    val title: String,
    val author: String,
    @Json(name = "work_type") val workType: String,
    val resources: List<DownloadSnapshotResource>,
)

@JsonClass(generateAdapter = false)
data class DownloadSnapshotResource(
    val id: String,
    val index: Int,
    val type: String,
    val title: String,
    @Json(name = "download_urls") val downloadUrls: List<String>,
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "duration_ms") val durationMs: Long? = null,
    @Json(name = "format_hint") val formatHint: String? = null,
    @Json(name = "live_video") val liveVideo: DownloadSnapshotLiveVideo? = null,
)

@JsonClass(generateAdapter = false)
data class DownloadSnapshotLiveVideo(
    val available: Boolean = false,
    @Json(name = "download_urls") val downloadUrls: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "duration_ms") val durationMs: Long? = null,
    @Json(name = "format_hint") val formatHint: String? = null,
)
