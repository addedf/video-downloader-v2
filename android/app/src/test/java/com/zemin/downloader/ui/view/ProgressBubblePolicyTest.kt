package com.zemin.downloader.ui.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressBubblePolicyTest {
    @Test
    fun progressIsClampedToValidRange() {
        assertNull(ProgressBubblePolicy.normalizedProgress(null))
        assertEquals(0, ProgressBubblePolicy.normalizedProgress(-4))
        assertEquals(72, ProgressBubblePolicy.normalizedProgress(72))
        assertEquals(100, ProgressBubblePolicy.normalizedProgress(140))
    }

    @Test
    fun firstDeterminateProgressExpandsButRegularUpdatesDoNot() {
        val unknown = state(progress = null)
        val firstProgress = state(progress = 8)
        val laterProgress = state(progress = 42)

        assertTrue(ProgressBubblePolicy.shouldAutoExpand(unknown, firstProgress))
        assertFalse(ProgressBubblePolicy.shouldAutoExpand(firstProgress, laterProgress))
    }

    @Test
    fun resultStatesRemainExpandedUntilTheirDifferentHideDeadlines() {
        assertEquals(
            ProgressBubblePolicy.STAGE_AUTO_COLLAPSE_MS,
            ProgressBubblePolicy.autoCollapseDelay(ProgressBubbleStage.FINALIZING),
        )
        assertNull(ProgressBubblePolicy.autoCollapseDelay(ProgressBubbleStage.SUCCESS))
        assertNull(ProgressBubblePolicy.autoCollapseDelay(ProgressBubbleStage.ERROR))
        assertEquals(
            ProgressBubblePolicy.SUCCESS_HIDE_DELAY_MS,
            ProgressBubblePolicy.resultHideDelay(ProgressBubbleStage.SUCCESS),
        )
        assertEquals(
            ProgressBubblePolicy.ERROR_HIDE_DELAY_MS,
            ProgressBubblePolicy.resultHideDelay(ProgressBubbleStage.ERROR),
        )
    }

    @Test
    fun expandedWidthNeverExceedsSafeHorizontalSpace() {
        assertEquals(188, ProgressBubblePolicy.expandedWidth(188, 344))
        assertEquals(180, ProgressBubblePolicy.expandedWidth(188, 180))
    }

    @Test
    fun successUsesShorterWidthWithoutConstrainingOtherStates() {
        assertEquals(
            176,
            ProgressBubblePolicy.desiredExpandedWidth(ProgressBubbleStage.SUCCESS),
        )
        assertEquals(
            188,
            ProgressBubblePolicy.desiredExpandedWidth(ProgressBubbleStage.DOWNLOADING),
        )
        assertEquals(
            188,
            ProgressBubblePolicy.desiredExpandedWidth(ProgressBubbleStage.ERROR),
        )
    }

    private fun state(progress: Int?) = ProgressBubbleState(
        stage = ProgressBubbleStage.DOWNLOADING,
        progress = progress,
        primaryText = "正在下载",
        detailText = "1 MB / 10 MB",
        accessibilityText = "正在下载",
    )
}
