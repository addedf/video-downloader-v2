package com.zemin.downloader.common.util

import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import androidx.core.content.edit
import com.zemin.downloader.appContext
import org.json.JSONArray
import org.json.JSONObject

object DownloadHistoryStore {
    private const val KEY_HISTORY = "douyin_download_history"
    private const val MAX_HISTORY_SIZE = 20

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }

    fun add(record: DownloadHistoryRecord) {
        val records = listOf(record) + getAll().filterNot { it.downloadId == record.downloadId }
        save(records.take(MAX_HISTORY_SIZE))
    }

    fun getAll(): List<DownloadHistoryRecord> {
        val raw = prefs.getString(KEY_HISTORY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(item.toRecord())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun latest(): DownloadHistoryRecord? = getAll().firstOrNull()

    fun clear() {
        prefs.edit { remove(KEY_HISTORY) }
    }

    private fun save(records: List<DownloadHistoryRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_HISTORY, array.toString()) }
    }

    private fun DownloadHistoryRecord.toJson(): JSONObject {
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

    private fun JSONObject.toRecord(): DownloadHistoryRecord {
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
