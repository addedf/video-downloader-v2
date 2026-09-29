package com.zemin.downloader.common.util

import android.widget.Toast
import com.zemin.downloader.appContext
import kotlin.math.roundToInt

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).roundToInt() / 10.0}KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).roundToInt() / 10.0}MB"
    return "${(mb / 102.4).roundToInt() / 10.0}GB"
}

fun toast(message: String) {
    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
}
