package com.zemin.downloader.ui

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.zemin.downloader.R
import com.zemin.downloader.common.util.formatBytes
import com.zemin.downloader.databinding.ActivityMainBinding
import com.zemin.downloader.ui.motion.UiMotion
import com.zemin.downloader.ui.view.ProgressBubbleDockSide
import com.zemin.downloader.ui.view.ProgressBubblePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 悬浮进度气泡：停靠边界、贴边吸附、自动展开可行性、隐藏调度与进度文案。
 * Activity 只负责把 WindowInsets 与生命周期事件转发进来。
 */
class ProgressBubbleController(
    private val host: MainActivity,
    private val scope: CoroutineScope,
) {
    private val ui get() = host.ui

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var positioned = false
    private var dockSide = ProgressBubbleDockSide.RIGHT
    private var hideJob: Job? = null
    private val lastProgressUiUpdatedAt = AtomicLong(PROGRESS_RECORD_INIT_TIME)

    val view get() = ui.progressBubble

    fun setup() {
        ui.progressBubble.setOnClickListener {
            if (ui.progressBubble.shouldOpenHistoryOnClick) {
                host.showMineSheet()
            } else {
                ui.progressBubble.toggleDetails()
            }
        }
        UiMotion.bindEdgeSnap(
            view = ui.progressBubble,
            boundsProvider = {
                val bubble = ui.progressBubble
                val minX = insetLeft + host.dp(8).toFloat()
                val maxX = (
                    ui.root.width - insetRight - bubble.compactInteractionWidth - host.dp(8)
                    )
                    .coerceAtLeast(minX.toInt()).toFloat()
                RectF(
                    minX,
                    minY().toFloat(),
                    maxX,
                    maxY().toFloat(),
                )
            },
            onDragStarted = {
                positioned = true
                ui.progressBubble.beginDragFeedback()
            },
            onEdgeSettled = { edge ->
                dockSide = if (edge == UiMotion.HorizontalEdge.LEFT) {
                    ProgressBubbleDockSide.LEFT
                } else {
                    ProgressBubbleDockSide.RIGHT
                }
                positionBubble(withImpact = true)
            },
        )
    }

    fun dispose() {
        hideJob?.cancel()
    }

    fun cancelHide() {
        hideJob?.cancel()
        hideJob = null
    }

    fun scheduleHide(delayMs: Long) {
        cancelHide()
        hideJob = scope.launch {
            delay(delayMs)
            ui.progressBubble.hide()
            hideJob = null
        }
    }

    fun showResolving(primaryText: String, detailText: String) =
        ui.progressBubble.showResolving(primaryText = primaryText, detailText = detailText)

    fun showPreparing(primaryText: String, detailText: String) =
        ui.progressBubble.showPreparing(primaryText = primaryText, detailText = detailText)

    fun showFinalizing(primaryText: String, detailText: String) =
        ui.progressBubble.showFinalizing(primaryText = primaryText, detailText = detailText)

    fun showSuccess(primaryText: String, detailText: String) =
        ui.progressBubble.showSuccess(primaryText = primaryText, detailText = detailText)

    fun showError(primaryText: String, detailText: String, accessibilityDetail: String) =
        ui.progressBubble.showError(
            primaryText = primaryText,
            detailText = detailText,
            accessibilityDetail = accessibilityDetail,
        )

    fun hide() = ui.progressBubble.hide()

    fun onProgress(
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
    ) {
        val downloadedText = formatBytes(downloadedBytes)
        val sizeText = if (totalBytes > EMPTY_BYTE_COUNT) {
            host.getString(R.string.main_progress_size_format, downloadedText, formatBytes(totalBytes))
        } else {
            host.getString(R.string.main_progress_size_unknown)
        }
        val speedText = if (speedBytesPerSecond > EMPTY_BYTE_COUNT) {
            host.getString(R.string.main_progress_speed_format, formatBytes(speedBytesPerSecond))
        } else {
            host.getString(R.string.main_progress_speed_unknown)
        }
        val detailText = "$sizeText · $speedText"
        cancelHide()
        if (totalBytes > EMPTY_BYTE_COUNT) {
            ui.progressBubble.showProgress(
                value = percent,
                primaryText = host.getString(R.string.main_status_downloading),
                detailText = detailText,
            )
        } else {
            ui.progressBubble.showDownloading(
                primaryText = host.getString(R.string.main_status_downloading),
                detailText = detailText,
            )
        }
    }

    /** 应记录下一次 UI 刷新的时间戳（下载进度按间隔节流用）。 */
    fun markProgressRecorded() {
        lastProgressUiUpdatedAt.set(PROGRESS_RECORD_INIT_TIME)
    }

    fun shouldDispatchProgressUpdate(downloadedBytes: Long, totalBytes: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val isComplete = totalBytes > EMPTY_BYTE_COUNT && downloadedBytes >= totalBytes

        while (true) {
            val lastUpdatedAt = lastProgressUiUpdatedAt.get()
            val interval = now - lastUpdatedAt
            if (!isComplete && lastUpdatedAt > 0L && interval < PROGRESS_UI_UPDATE_INTERVAL_MS) {
                return false
            }
            if (lastProgressUiUpdatedAt.compareAndSet(lastUpdatedAt, now)) {
                return true
            }
        }
    }

    fun onInsetsChanged() {
        ui.root.post {
            ui.progressBubble.setAvailableHorizontalSpace(
                ui.root.width - insetLeft - insetRight - host.dp(16)
            )
            val topMargin = if (positioned) {
                ui.progressBubble.y.roundToInt().coerceIn(minY(), maxY())
            } else {
                dockSide = preferredSide()
                minY()
            }
            positionAtDock(topMargin, withImpact = false)
            positioned = true
        }
    }

    /** 平台切换等布局变化后重估自动展开的可行性。 */
    fun refreshAutoExpansion() {
        ui.root.post {
            val topMargin = ui.progressBubble.y.roundToInt()
                .coerceIn(minY(), maxY())
            ui.progressBubble.setAutomaticExpansionEnabled(
                isAutomaticExpansionSafe(dockSide, topMargin)
            )
        }
    }

    private fun positionBubble(withImpact: Boolean) {
        val topMargin = ui.progressBubble.y.roundToInt()
            .coerceIn(minY(), maxY())
        ui.progressBubble.setAutomaticExpansionEnabled(
            isAutomaticExpansionSafe(dockSide, topMargin)
        )
        positionAtDock(topMargin, withImpact)
    }

    private fun positionAtDock(topMargin: Int, withImpact: Boolean) {
        ui.progressBubble.positionAtDock(
            side = dockSide,
            leftMargin = insetLeft + host.dp(8),
            rightMargin = insetRight + host.dp(8),
            topMargin = topMargin,
            withImpact = withImpact,
        )
    }

    fun updateInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insetTop = top
        insetBottom = bottom
        insetLeft = left
        insetRight = right
    }

    private fun minY(): Int = insetTop

    private fun maxY(): Int {
        val bottomBoundary = ui.bottomNav.top.takeIf { it > 0 }
            ?: (ui.root.height - insetBottom)
        return (bottomBoundary - ui.progressBubble.height - host.dp(8))
            .coerceAtLeast(minY())
    }

    private fun preferredSide(): ProgressBubbleDockSide =
        if (ui.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            ProgressBubbleDockSide.LEFT
        } else {
            ProgressBubbleDockSide.RIGHT
        }

    private fun isAutomaticExpansionSafe(
        side: ProgressBubbleDockSide,
        topMargin: Int,
    ): Boolean {
        if (side != preferredSide() || ui.root.width <= 0) return false
        val availableWidth = ui.root.width - insetLeft - insetRight - host.dp(16)
        val expandedWidth = ProgressBubblePolicy.expandedWidth(
            desiredWidth = host.dp(ProgressBubblePolicy.EXPANDED_WIDTH_DP),
            availableWidth = availableWidth,
        )
        val bubbleLeft = if (side == ProgressBubbleDockSide.LEFT) {
            insetLeft + host.dp(8)
        } else {
            ui.root.width - insetRight - host.dp(8) - expandedWidth
        }
        val bubbleRight = bubbleLeft + expandedWidth
        val title = ui.tvAppTitle
        val titleTextWidth = title.paint.measureText(title.text.toString())
        val isRtl = title.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val titleTextLeft = if (isRtl) {
            title.x + title.width - title.paddingRight - titleTextWidth
        } else {
            title.x + title.paddingLeft
        }
        val titleTextRight = titleTextLeft + titleTextWidth
        val horizontalClear = if (side == ProgressBubbleDockSide.LEFT) {
            bubbleRight + host.dp(12) <= titleTextLeft
        } else {
            bubbleLeft >= titleTextRight + host.dp(12)
        }
        val bubbleHeight = ui.progressBubble.height.takeIf { it > 0 }
            ?: host.dp(ProgressBubblePolicy.HEIGHT_DP)
        val visibleBubbleBottom = topMargin + bubbleHeight - host.dp(2)
        val downloadSectionTop = (ui.contentPanel.y + ui.downloadSection.y).roundToInt()
        return horizontalClear && visibleBubbleBottom <= downloadSectionTop
    }

    private companion object {
        const val PROGRESS_RECORD_INIT_TIME = 0L
        const val PROGRESS_UI_UPDATE_INTERVAL_MS = 200L
        const val EMPTY_BYTE_COUNT = 0L
    }
}
