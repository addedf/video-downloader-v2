package com.zemin.downloader.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewImageMotionSpecTest {
    @Test
    fun memoryCacheHitUsesShorterForegroundTransition() {
        assertEquals(
            PreviewImageMotionSpec.MEMORY_CACHE_DURATION_MS,
            PreviewImageMotionSpec.foregroundDuration(memoryCacheHit = true),
        )
        assertEquals(
            PreviewImageMotionSpec.FOREGROUND_DURATION_MS,
            PreviewImageMotionSpec.foregroundDuration(memoryCacheHit = false),
        )
    }
}
