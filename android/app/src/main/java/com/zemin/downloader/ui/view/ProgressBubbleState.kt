package com.zemin.downloader.ui.view

internal enum class ProgressBubbleStage {
    RESOLVING,
    PREPARING,
    DOWNLOADING,
    FINALIZING,
    SUCCESS,
    ERROR,
}

enum class ProgressBubbleDockSide { LEFT, RIGHT }

internal data class ProgressBubbleState(
    val stage: ProgressBubbleStage,
    val progress: Int?,
    val primaryText: String,
    val detailText: String,
    val accessibilityText: String,
) {
    val isResult: Boolean
        get() = stage == ProgressBubbleStage.SUCCESS || stage == ProgressBubbleStage.ERROR
}

internal object ProgressBubblePolicy {
    const val COMPACT_WIDTH_DP = 56
    const val EXPANDED_WIDTH_DP = 188
    const val SUCCESS_EXPANDED_WIDTH_DP = 176
    const val HEIGHT_DP = 56
    const val STAGE_AUTO_COLLAPSE_MS = 900L
    const val USER_DETAIL_COLLAPSE_MS = 2_200L
    const val SUCCESS_HIDE_DELAY_MS = 1_500L
    const val ERROR_HIDE_DELAY_MS = 3_000L

    fun normalizedProgress(progress: Int?): Int? = progress?.coerceIn(0, 100)

    fun shouldAutoExpand(
        previous: ProgressBubbleState?,
        current: ProgressBubbleState,
    ): Boolean {
        if (previous == null || previous.stage != current.stage) return true
        return previous.progress == null && current.progress != null
    }

    fun autoCollapseDelay(stage: ProgressBubbleStage): Long? = when (stage) {
        ProgressBubbleStage.RESOLVING,
        ProgressBubbleStage.PREPARING,
        ProgressBubbleStage.DOWNLOADING,
        ProgressBubbleStage.FINALIZING -> STAGE_AUTO_COLLAPSE_MS
        ProgressBubbleStage.SUCCESS,
        ProgressBubbleStage.ERROR -> null
    }

    fun resultHideDelay(stage: ProgressBubbleStage): Long = when (stage) {
        ProgressBubbleStage.ERROR -> ERROR_HIDE_DELAY_MS
        else -> SUCCESS_HIDE_DELAY_MS
    }

    fun expandedWidth(desiredWidth: Int, availableWidth: Int): Int =
        desiredWidth.coerceAtMost(availableWidth.coerceAtLeast(0))

    fun desiredExpandedWidth(stage: ProgressBubbleStage?): Int =
        if (stage == ProgressBubbleStage.SUCCESS) SUCCESS_EXPANDED_WIDTH_DP else EXPANDED_WIDTH_DP
}
