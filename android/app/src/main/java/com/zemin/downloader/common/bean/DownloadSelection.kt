package com.zemin.downloader.common.bean

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class DownloadRequest(
    @Json(name = "schema_version") val schemaVersion: Int = 2,
    val source: DownloadSource,
    @Json(name = "expected_work_type") val expectedWorkType: String,
    val selection: DownloadSelection,
)

@JsonClass(generateAdapter = false)
data class DownloadSource(
    val platform: String = "douyin",
    val url: String,
    val id: String,
)

@JsonClass(generateAdapter = false)
data class DownloadSelection(
    @Json(name = "resource_type") val resourceType: String,
    @Json(name = "resource_ids") val resourceIds: List<String> = emptyList(),
    @Json(name = "include_live_video") val includeLiveVideo: Boolean = false,
)
