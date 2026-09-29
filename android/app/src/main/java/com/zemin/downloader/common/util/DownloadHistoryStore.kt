package com.zemin.downloader.common.util

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object DownloadHistoryStore : JsonListStore<DownloadHistoryRecord>(
    prefsKey = "douyin_download_history",
    maxSize = 20,
) {
    override fun idOf(record: DownloadHistoryRecord): String = record.downloadId

    override fun DownloadHistoryRecord.toJson(): JSONObject {
        return JSONObject().apply {
            put("downloadId", downloadId)
            put("sourceUrl", sourceUrl)
            put("title", title)
            put("mediaType", mediaType)
            put("status", status)
            put("savedPath", savedPath)
            put("savedUris", JSONArray(savedUris.map(Uri::toString)))
            put("errorMessage", errorMessage)
            put("createdAt", createdAt)
            put("finishedAt", finishedAt)
        }
    }

    override fun JSONObject.toRecord(): DownloadHistoryRecord? {
        val uriArray = optJSONArray("savedUris") ?: JSONArray()
        val uris = buildList {
            for (index in 0 until uriArray.length()) {
                val value = uriArray.optString(index).takeIf { it.isNotBlank() } ?: continue
                add(Uri.parse(value))
            }
        }
        return DownloadHistoryRecord(
            downloadId = optString("downloadId"),
            sourceUrl = optString("sourceUrl"),
            title = optString("title"),
            mediaType = optString("mediaType"),
            status = optString("status"),
            savedPath = optString("savedPath"),
            savedUris = uris,
            errorMessage = optString("errorMessage").takeIf { it.isNotBlank() },
            createdAt = optLong("createdAt"),
            finishedAt = optLong("finishedAt"),
        )
    }

    fun latest(): DownloadHistoryRecord? = getAll().firstOrNull()
}

data class DownloadHistoryRecord(
    val downloadId: String,
    val sourceUrl: String,
    val title: String,
    val mediaType: String,
    val status: String,
    val savedPath: String,
    val savedUris: List<Uri> = emptyList(),
    val errorMessage: String? = null,
    val createdAt: Long,
    val finishedAt: Long,
) {
    val isSuccess: Boolean get() = status == STATUS_SUCCESS

    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}
