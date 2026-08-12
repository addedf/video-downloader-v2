package com.zemin.downloader.common.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.zemin.downloader.R
import com.zemin.downloader.appContext
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolveCapabilities
import com.zemin.downloader.common.ResolveCounts
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.ResolvedLiveVideo
import com.zemin.downloader.common.bean.PyResolveResponse

object ResolveResultParser {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(PyResolveResponse::class.java)

    fun parse(raw: String): PyResolveResult {
        val response = adapter.fromJson(raw) ?: PyResolveResponse()
        val error = response.error?.takeIf { it.isNotBlank() }
        if (response.schemaVersion != null && response.schemaVersion != 2) {
            return PyResolveResult(
                ok = false,
                message = "不支持的解析协议版本：${response.schemaVersion}",
                error = "不支持的解析协议版本：${response.schemaVersion}",
                sourceUrl = null,
                sourceId = null,
                title = null,
                author = null,
                coverUrl = null,
                mediaType = null,
            )
        }
        if (response.schemaVersion == 2) return parseV2(response, error)
        return PyResolveResult(
            ok = response.ok,
            message = response.message?.takeIf { it.isNotBlank() } ?: error ?: appContext.getString(
                R.string.main_task_complete
            ),
            error = error,
            sourceUrl = response.sourceUrl,
            sourceId = response.sourceId,
            title = response.title,
            author = response.author,
            coverUrl = response.coverUrl,
            mediaType = response.mediaType,
            resources = response.resources.map {
                ResolvedResource(
                    title = it.title,
                    mediaType = it.mediaType,
                    downloadUrls = it.downloadUrls,
                    selected = it.selected,
                )
            },
        )
    }

    private fun parseV2(response: PyResolveResponse, error: String?): PyResolveResult {
        val work = response.work
        val groups = work?.resources
        val resources = listOfNotNull(
            groups?.videos,
            groups?.images,
            groups?.covers,
            groups?.audios,
        ).flatten().map { item ->
            ResolvedResource(
                id = item.id,
                index = item.index,
                title = item.title,
                mediaType = item.type,
                previewUrls = item.previewUrls,
                downloadUrls = item.downloadUrls,
                width = item.width,
                height = item.height,
                durationMs = item.durationMs,
                formatHint = item.formatHint,
                liveVideo = item.liveVideo?.let {
                    ResolvedLiveVideo(
                        available = it.available,
                        downloadUrls = it.downloadUrls,
                        width = it.width,
                        height = it.height,
                        durationMs = it.durationMs,
                        formatHint = it.formatHint,
                    )
                },
            )
        }
        val capabilities = work?.capabilities
        val counts = work?.counts
        val resourceError = if (response.ok && resources.isEmpty()) {
            "解析结果中没有可保存的资源"
        } else {
            null
        }
        val effectiveError = error ?: resourceError
        val coverUrl = groups?.covers?.firstOrNull()?.previewUrls?.firstOrNull()
            ?: groups?.covers?.firstOrNull()?.downloadUrls?.firstOrNull()
            ?: groups?.images?.firstOrNull()?.previewUrls?.firstOrNull()
        return PyResolveResult(
            ok = response.ok && effectiveError == null,
            message = effectiveError ?: response.message?.takeIf { it.isNotBlank() } ?: appContext.getString(
                R.string.main_task_complete
            ),
            error = effectiveError,
            sourceUrl = response.source?.resolvedUrl ?: response.source?.inputUrl,
            sourceId = response.source?.id,
            title = work?.title,
            author = work?.author?.name,
            coverUrl = coverUrl,
            mediaType = work?.type,
            resources = resources,
            schemaVersion = 2,
            capabilities = ResolveCapabilities(
                hasVideo = capabilities?.hasVideo == true && groups?.videos?.isNotEmpty() == true,
                hasImages = capabilities?.hasImages == true && groups?.images?.isNotEmpty() == true,
                hasCover = capabilities?.hasCover == true && groups?.covers?.isNotEmpty() == true,
                hasAudio = capabilities?.hasAudio == true && groups?.audios?.isNotEmpty() == true,
                hasLiveVideo = capabilities?.hasLiveVideo == true &&
                    groups?.images?.any { it.liveVideo?.available == true } == true,
            ),
            counts = ResolveCounts(
                videos = groups?.videos?.size ?: counts?.videos ?: 0,
                images = groups?.images?.size ?: counts?.images ?: 0,
                covers = groups?.covers?.size ?: counts?.covers ?: 0,
                audios = groups?.audios?.size ?: counts?.audios ?: 0,
                liveVideos = groups?.images?.count { it.liveVideo?.available == true }
                    ?: counts?.liveVideos ?: 0,
            ),
        )
    }
}
