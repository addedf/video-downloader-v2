package com.zemin.downloader.common.bean

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class PyResolveResponse(
    @Json(name = "schema_version") val schemaVersion: Int? = null,
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    @Json(name = "source_url") val sourceUrl: String? = null,
    @Json(name = "source_id") val sourceId: String? = null,
    val title: String? = null,
    val author: String? = null,
    @Json(name = "cover_url") val coverUrl: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    val resources: List<PyResolveResourceResponse> = emptyList(),
    val source: PyResolveSourceResponse? = null,
    val work: PyResolveWorkResponse? = null,
)

@JsonClass(generateAdapter = false)
data class PyResolveResourceResponse(
    val title: String = "",
    @Json(name = "media_type") val mediaType: String = "",
    @Json(name = "download_urls") val downloadUrls: List<String> = emptyList(),
    val selected: Boolean = true,
)

@JsonClass(generateAdapter = false)
data class PyResolveSourceResponse(
    val platform: String? = null,
    @Json(name = "input_url") val inputUrl: String? = null,
    @Json(name = "resolved_url") val resolvedUrl: String? = null,
    val id: String? = null,
)

@JsonClass(generateAdapter = false)
data class PyResolveWorkResponse(
    val type: String? = null,
    val title: String? = null,
    val author: PyResolveAuthorResponse? = null,
    val capabilities: PyResolveCapabilitiesResponse = PyResolveCapabilitiesResponse(),
    val counts: PyResolveCountsResponse = PyResolveCountsResponse(),
    val resources: PyResolveResourceGroupsResponse = PyResolveResourceGroupsResponse(),
)

@JsonClass(generateAdapter = false)
data class PyResolveAuthorResponse(
    val id: String? = null,
    val name: String? = null,
)

@JsonClass(generateAdapter = false)
data class PyResolveCapabilitiesResponse(
    @Json(name = "has_video") val hasVideo: Boolean = false,
    @Json(name = "has_images") val hasImages: Boolean = false,
    @Json(name = "has_cover") val hasCover: Boolean = false,
    @Json(name = "has_audio") val hasAudio: Boolean = false,
    @Json(name = "has_live_video") val hasLiveVideo: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class PyResolveCountsResponse(
    val videos: Int = 0,
    val images: Int = 0,
    val covers: Int = 0,
    val audios: Int = 0,
    @Json(name = "live_videos") val liveVideos: Int = 0,
)

@JsonClass(generateAdapter = false)
data class PyResolveResourceGroupsResponse(
    val videos: List<PyResolveV2ResourceResponse> = emptyList(),
    val images: List<PyResolveV2ResourceResponse> = emptyList(),
    val covers: List<PyResolveV2ResourceResponse> = emptyList(),
    val audios: List<PyResolveV2ResourceResponse> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class PyResolveV2ResourceResponse(
    val id: String = "",
    val index: Int = 0,
    val type: String = "",
    val title: String = "",
    @Json(name = "preview_urls") val previewUrls: List<String> = emptyList(),
    @Json(name = "download_urls") val downloadUrls: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "duration_ms") val durationMs: Long? = null,
    @Json(name = "format_hint") val formatHint: String? = null,
    @Json(name = "live_video") val liveVideo: PyResolveLiveVideoResponse? = null,
)

@JsonClass(generateAdapter = false)
data class PyResolveLiveVideoResponse(
    val available: Boolean = false,
    @Json(name = "download_urls") val downloadUrls: List<String> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    @Json(name = "duration_ms") val durationMs: Long? = null,
    @Json(name = "format_hint") val formatHint: String? = null,
)
