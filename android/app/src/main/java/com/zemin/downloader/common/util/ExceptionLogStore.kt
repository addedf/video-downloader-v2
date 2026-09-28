package com.zemin.downloader.common.util

import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import androidx.core.content.edit
import com.zemin.downloader.appContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/** A small, redacted diagnostic history kept locally for user initiated support logs. */
object ExceptionLogStore {
    private const val KEY_LOGS = "exception_diagnostic_logs"
    private const val MAX_LOGS = 20
    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }

    fun add(record: ExceptionLogRecord) {
        val safeRecord = record.copy(
            sourceUrl = ExceptionLogRecord.redactUrl(record.sourceUrl),
            responseSummary = ExceptionLogRecord.redactText(record.responseSummary),
            parseException = ExceptionLogRecord.redactText(record.parseException),
            retryInfo = ExceptionLogRecord.redactText(record.retryInfo),
            attempts = ExceptionLogRecord.redactText(record.attempts),
            timings = ExceptionLogRecord.redactText(record.timings),
        )
        val records = listOf(safeRecord) + getAll().filterNot { it.id == safeRecord.id }
        val array = JSONArray()
        records.take(MAX_LOGS).forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_LOGS, array.toString()) }
    }

    fun getAll(): List<ExceptionLogRecord> {
        val raw = prefs.getString(KEY_LOGS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(it.toRecord()) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear() = prefs.edit { remove(KEY_LOGS) }

    fun formatForCopy(record: ExceptionLogRecord): String = buildString {
        appendLine("视频下载器异常日志")
        appendLine("时间：${record.displayTime()}")
        appendLine("平台：${record.platform}")
        appendLine("操作：${record.operation}")
        appendLine("渠道：${record.channel}")
        appendLine("阶段：${record.stage}")
        appendLine("作品链接：${record.sourceUrl}")
        appendLine("状态：${record.status}")
        appendLine("返回信息：${record.responseSummary}")
        appendLine("解析异常：${record.parseException}")
        appendLine("重试信息：${record.retryInfo}")
        if (record.timings.isNotBlank()) appendLine("阶段耗时：${record.timings}")
        if (record.attempts.isNotBlank()) appendLine("阶段记录：${record.attempts}")
    }

    private fun ExceptionLogRecord.toJson() = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("platform", platform)
        put("operation", operation)
        put("channel", channel)
        put("stage", stage)
        put("sourceUrl", sourceUrl)
        put("status", status)
        put("responseSummary", responseSummary)
        put("parseException", parseException)
        put("retryInfo", retryInfo)
        put("attempts", attempts)
        put("timings", timings)
    }

    private fun JSONObject.toRecord() = ExceptionLogRecord(
        id = optString("id"),
        createdAt = optLong("createdAt"),
        platform = optString("platform"),
        operation = optString("operation", "解析"),
        channel = optString("channel"),
        stage = optString("stage"),
        sourceUrl = optString("sourceUrl"),
        status = optString("status"),
        responseSummary = optString("responseSummary"),
        parseException = optString("parseException"),
        retryInfo = optString("retryInfo"),
        attempts = optString("attempts"),
        timings = optString("timings"),
    )

    private fun ExceptionLogRecord.displayTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(createdAt))
}

data class ExceptionLogRecord(
    val id: String,
    val createdAt: Long,
    val platform: String,
    val operation: String = "解析",
    val channel: String,
    val stage: String,
    val sourceUrl: String,
    val status: String,
    val responseSummary: String,
    val parseException: String,
    val retryInfo: String,
    val attempts: String = "",
    val timings: String = "",
) {
    val displayTime: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))

    companion object {
        fun redactUrl(value: String): String {
            val url = runCatching { Uri.parse(value) }.getOrNull() ?: return value.take(240)
            if (url.scheme.isNullOrBlank() || url.host.isNullOrBlank()) return value.take(240)
            return url.buildUpon().clearQuery().fragment(null).build().toString()
        }

        fun redactText(value: String): String {
            var text = value.replace('\r', ' ').replace('\n', ' ').trim()
            text = Pattern.compile(
                "(?i)(cookie|authorization|token|mstoken|x-bogus|a_bogus|share_sign|sessionid|sid_guard)\\s*[:=]\\s*[^\\s,;|]+"
            ).matcher(text).replaceAll("\\$1=[REDACTED]")
            text = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE)
                .matcher(text).replaceAll("[URL]")
            return text.take(800)
        }
    }
}
