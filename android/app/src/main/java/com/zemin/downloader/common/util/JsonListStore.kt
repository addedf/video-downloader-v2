package com.zemin.downloader.common.util

import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.core.content.edit
import com.zemin.downloader.appContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 以 JSON 数组存进默认 SharedPreferences 的记录列表（新记录插头、按 id 去重、超量截断）。
 * 序列化仍走 org.json：记录里含 Uri 等非 Moshi 原生类型，保持既有存储格式不变。
 */
abstract class JsonListStore<T>(
    private val prefsKey: String,
    private val maxSize: Int,
) {
    protected val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(appContext)
    }

    protected abstract fun idOf(record: T): String

    /** 入库前的加工钩子（如脱敏）。 */
    protected open fun prepare(record: T): T = record

    protected abstract fun T.toJson(): JSONObject

    protected abstract fun JSONObject.toRecord(): T?

    fun add(record: T) {
        val safeRecord = prepare(record)
        val records = listOf(safeRecord) + getAll().filterNot { idOf(it) == idOf(safeRecord) }
        save(records.take(maxSize))
    }

    fun getAll(): List<T> {
        val raw = prefs.getString(prefsKey, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { item ->
                        item.toRecord()?.let { add(it) }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear() = prefs.edit { remove(prefsKey) }

    private fun save(records: List<T>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        prefs.edit { putString(prefsKey, array.toString()) }
    }
}
