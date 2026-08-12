package com.zemin.downloader.ui.util

import android.content.Intent
import com.zemin.downloader.appContext

/**
 * @author maozemin@coocaa.com
 * @desc
 */
val URL_PATTERN = Regex("https?://\\S+")

fun extractSharedText(intent: Intent?): String {
    if (intent == null) return ""
    return when (intent.action) {
        Intent.ACTION_SEND -> {
            val rawText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(
                Intent.EXTRA_TEXT
            )?.toString() ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            normalizeSharedText(rawText)
        }

        Intent.ACTION_VIEW -> {
            normalizeSharedText(intent.dataString.orEmpty())
        }

        else -> ""
    }
}

fun normalizeSharedText(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val url = URL_PATTERN.find(trimmed)?.value?.trimSupportedUrlEnd()
    return url ?: trimmed
}


fun dp(value: Int): Int {
    return (value * appContext.resources.displayMetrics.density).toInt()
}
