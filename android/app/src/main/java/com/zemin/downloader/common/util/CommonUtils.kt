package com.zemin.downloader.common.util

import android.widget.Toast
import com.zemin.downloader.appContext
import kotlin.math.roundToInt

fun compactFileName(name: String): String {
    val stem = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "")
    val compact =
        stem.replace(Regex("^\\d{4}-\\d{2}-\\d{2}_?"), "").replace(Regex("_?\\d{15,20}$"), "")
            .ifBlank { stem }
    val shortName = ellipsizeMiddle(compact, 24)
    return if (extension.isBlank()) shortName else "$shortName.$extension"
}

fun ellipsizeMiddle(value: String, maxLength: Int): String {
    if (value.length <= maxLength) return value
    if (maxLength <= 3) return value.take(maxLength)
    val keep = maxLength - 3
    val prefix = keep / 2
    val suffix = keep - prefix
    return value.take(prefix) + "..." + value.takeLast(suffix)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).roundToInt() / 10.0}KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).roundToInt() / 10.0}MB"
    return "${(mb / 102.4).roundToInt() / 10.0}GB"
}

fun formatSpeed(kbps: Int): String {
    if (kbps <= 0) return "未知"
    return if (kbps < 1024) {
        "${kbps}KB/s"
    } else {
        "${(kbps / 102.4).roundToInt() / 10.0}MB/s"
    }
}

fun formatDuration(ms: Int): String {
    return if (ms >= 1000) {
        "${(ms / 100.0).roundToInt() / 10.0}s"
    } else {
        "${ms}ms"
    }
}

fun toast(message: String) {
    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
}