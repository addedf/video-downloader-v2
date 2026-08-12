package com.zemin.downloader.ui.preview

internal object PreviewImageMotionSpec {
    const val FOREGROUND_DURATION_MS = 160L
    const val MEMORY_CACHE_DURATION_MS = 110L
    const val BACKGROUND_DURATION_MS = 200L
    const val INCOMING_START_SCALE = 0.985f

    fun foregroundDuration(memoryCacheHit: Boolean): Long =
        if (memoryCacheHit) MEMORY_CACHE_DURATION_MS else FOREGROUND_DURATION_MS
}
