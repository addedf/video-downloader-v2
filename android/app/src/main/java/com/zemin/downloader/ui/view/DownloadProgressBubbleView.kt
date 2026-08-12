package com.zemin.downloader.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.zemin.downloader.R
import com.zemin.downloader.ui.motion.UiMotion
import kotlin.math.PI
import kotlin.math.cos

class DownloadProgressBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val compactWidthPx = dp(ProgressBubblePolicy.COMPACT_WIDTH_DP.toFloat()).toInt()
    private val desiredExpandedWidthPx = dp(ProgressBubblePolicy.EXPANDED_WIDTH_DP.toFloat()).toInt()
    private var availableHorizontalSpacePx = desiredExpandedWidthPx
    private val activeProgressColor = ContextCompat.getColor(context, R.color.dy_cyan)
    private val successProgressColor = ContextCompat.getColor(context, R.color.dy_success)
    private val errorProgressColor = ContextCompat.getColor(context, R.color.dy_error)

    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CONTAINER_COLOR
        style = Paint.Style.FILL
    }
    private val containerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            dp(2f),
            0f,
            dp(54f),
            intArrayOf(TOP_HIGHLIGHT_COLOR, MID_HIGHLIGHT_COLOR, BOTTOM_SHADE_COLOR),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TRACK_COLOR
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.5f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.5f)
    }
    private val primaryTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val detailTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DETAIL_TEXT_COLOR
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val resultIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.8f)
    }
    private val downloadIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3.2f)
    }
    private val containerBounds = RectF()
    private val arcBounds = RectF()

    private var state: ProgressBubbleState? = null
    private var dockSide = ProgressBubbleDockSide.RIGHT
    private var displayedProgress = 0f
    private var rotationDegrees = 0f
    private var indeterminateSweep = DEFAULT_INDETERMINATE_SWEEP
    private var loadingIconOffsetY = 0f
    private var arcTransitionStartAngle = -90f
    private var arcTransitionStartSweep = DEFAULT_INDETERMINATE_SWEEP
    private var arcTransitionFraction = 1f
    private var resultRevealFraction = 1f
    private var expansionFraction = 0f
    private var expanded = false
    private var dragging = false
    private var hiding = false
    private var automaticExpansionEnabled = true
    private var pendingResultFeedback = false
    private var displayPrimaryText = ""
    private var displayDetailText = ""

    private var widthAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null
    private var arcTransitionAnimator: ValueAnimator? = null
    private var resultAnimator: ValueAnimator? = null
    private val autoCollapseRunnable = Runnable {
        if (!dragging && state?.isResult != true) setExpanded(false, animated = true)
    }
    private val spinnerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SPINNER_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val phase = it.animatedValue as Float
            rotationDegrees = phase * 360f
            indeterminateSweep = MIN_INDETERMINATE_SWEEP +
                (MAX_INDETERMINATE_SWEEP - MIN_INDETERMINATE_SWEEP) *
                ((1f - cos(phase * 2f * PI).toFloat()) / 2f)
            loadingIconOffsetY = cos(phase * 2f * PI).toFloat() * dp(LOADING_ICON_TRAVEL_DP)
            invalidate()
        }
    }

    val compactInteractionWidth: Int
        get() = compactWidthPx

    val shouldOpenHistoryOnClick: Boolean
        get() = state?.isResult == true

    val isExpandedForTest: Boolean
        get() = expanded

    internal val dockSideForTest: ProgressBubbleDockSide
        get() = dockSide

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
    }

    fun setAvailableHorizontalSpace(availableWidthPx: Int) {
        val previousWidth = currentExpandedWidthPx()
        availableHorizontalSpacePx = availableWidthPx.coerceAtLeast(0)
        if (previousWidth == currentExpandedWidthPx()) return
        updateDisplayText()
        if (expanded) setExpanded(true, animated = false)
    }

    fun setAutomaticExpansionEnabled(enabled: Boolean) {
        automaticExpansionEnabled = enabled
    }

    fun showResolving(primaryText: String, detailText: String) {
        showIndeterminateState(ProgressBubbleStage.RESOLVING, primaryText, detailText)
    }

    fun showPreparing(primaryText: String, detailText: String) {
        showIndeterminateState(ProgressBubbleStage.PREPARING, primaryText, detailText)
    }

    fun showDownloading(primaryText: String, detailText: String) {
        showIndeterminateState(ProgressBubbleStage.DOWNLOADING, primaryText, detailText)
    }

    fun showFinalizing(primaryText: String, detailText: String) {
        showIndeterminateState(ProgressBubbleStage.FINALIZING, primaryText, detailText)
    }

    fun showProgress(value: Int, primaryText: String, detailText: String) {
        val progress = ProgressBubblePolicy.normalizedProgress(value) ?: 0
        applyState(
            ProgressBubbleState(
                stage = ProgressBubbleStage.DOWNLOADING,
                progress = progress,
                primaryText = "$primaryText · $progress%",
                detailText = detailText,
                accessibilityText = "$primaryText，$progress%，$detailText",
            )
        )
    }

    fun showSuccess(primaryText: String, detailText: String) {
        applyState(
            ProgressBubbleState(
                stage = ProgressBubbleStage.SUCCESS,
                progress = 100,
                primaryText = primaryText,
                detailText = detailText,
                accessibilityText = "$primaryText，$detailText",
            )
        )
    }

    fun showError(primaryText: String, detailText: String, accessibilityDetail: String = detailText) {
        applyState(
            ProgressBubbleState(
                stage = ProgressBubbleStage.ERROR,
                progress = 100,
                primaryText = primaryText,
                detailText = detailText,
                accessibilityText = "$primaryText，$accessibilityDetail",
            )
        )
    }

    fun toggleDetails() {
        if (expanded) {
            removeCallbacks(autoCollapseRunnable)
            setExpanded(false, animated = true)
        } else {
            expandTemporarily(ProgressBubblePolicy.USER_DETAIL_COLLAPSE_MS)
        }
    }

    fun beginDragFeedback() {
        removeCallbacks(autoCollapseRunnable)
        setExpanded(false, animated = false)
        dragging = true
        UiMotion.liftForDrag(this, DRAG_ELEVATION_DP)
    }

    fun positionAtDock(
        side: ProgressBubbleDockSide,
        leftMargin: Int,
        rightMargin: Int,
        topMargin: Int,
        withImpact: Boolean,
    ) {
        dockSide = side
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val horizontalGravity = if (side == ProgressBubbleDockSide.LEFT) {
            if (isRtl) Gravity.END else Gravity.START
        } else {
            if (isRtl) Gravity.START else Gravity.END
        }
        val edgeMargin = if (side == ProgressBubbleDockSide.LEFT) leftMargin else rightMargin
        params.gravity = Gravity.TOP or horizontalGravity
        params.marginStart = if (horizontalGravity == Gravity.START) edgeMargin else 0
        params.marginEnd = if (horizontalGravity == Gravity.END) edgeMargin else 0
        params.leftMargin = 0
        params.rightMargin = 0
        params.topMargin = topMargin
        translationX = 0f
        translationY = 0f
        layoutParams = params
        dragging = false
        invalidateOutline()

        if (withImpact && !hiding) UiMotion.settleAtEdge(this, RESTING_ELEVATION_DP)
        if (pendingResultFeedback && !hiding) {
            pendingResultFeedback = false
            playResultFeedback()
            if (automaticExpansionEnabled) setExpanded(true, animated = true)
        }
    }

    fun hide() {
        hiding = true
        pendingResultFeedback = false
        elevation = dp(RESTING_ELEVATION_DP)
        removeCallbacks(autoCollapseRunnable)
        spinnerAnimator.cancel()
        progressAnimator?.cancel()
        arcTransitionAnimator?.cancel()
        resultAnimator?.cancel()
        widthAnimator?.cancel()
        setExpanded(false, animated = false)
        UiMotion.fadeOut(this)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoCollapseRunnable)
        spinnerAnimator.cancel()
        progressAnimator?.cancel()
        arcTransitionAnimator?.cancel()
        resultAnimator?.cancel()
        widthAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        val containerInset = dp(2f)
        containerBounds.set(
            containerInset,
            containerInset,
            width - containerInset,
            height - containerInset,
        )
        val radius = containerBounds.height() / 2f
        canvas.drawRoundRect(containerBounds, radius, radius, containerPaint)
        canvas.drawRoundRect(containerBounds, radius, radius, containerHighlightPaint)
        canvas.drawRoundRect(containerBounds, radius, radius, borderPaint)

        drawDetails(canvas, centerY)
        drawProgressCore(canvas, centerY)
    }

    private fun showIndeterminateState(
        stage: ProgressBubbleStage,
        primaryText: String,
        detailText: String,
    ) {
        applyState(
            ProgressBubbleState(
                stage = stage,
                progress = null,
                primaryText = primaryText,
                detailText = detailText,
                accessibilityText = "$primaryText，$detailText",
            )
        )
    }

    private fun applyState(nextState: ProgressBubbleState) {
        val previousState = state
        val previousExpandedWidth = currentExpandedWidthPx()
        if (
            nextState.progress == null &&
            (nextState.stage == ProgressBubbleStage.RESOLVING ||
                nextState.stage == ProgressBubbleStage.PREPARING) &&
            previousState?.stage != nextState.stage
        ) {
            displayedProgress = 0f
        }
        val transitionedFromUnknown = previousState?.progress == null && nextState.progress != null
        val currentArcStart = -90f + rotationDegrees
        val currentArcSweep = indeterminateSweep
        state = nextState
        contentDescription = nextState.accessibilityText
        updateDisplayText()
        revealIfNeeded()

        if (nextState.progress == null) {
            progressAnimator?.cancel()
            arcTransitionAnimator?.cancel()
            arcTransitionFraction = 1f
            startSpinnerIfNeeded()
        } else {
            spinnerAnimator.cancel()
            if (transitionedFromUnknown && !nextState.isResult) {
                startArcTransition(currentArcStart, currentArcSweep)
            } else {
                arcTransitionAnimator?.cancel()
                arcTransitionFraction = 1f
            }
            if (nextState.isResult) {
                if (nextState.stage == ProgressBubbleStage.SUCCESS) {
                    animateProgressTo(100f, RESULT_PROGRESS_DURATION_MS)
                } else {
                    progressAnimator?.cancel()
                    displayedProgress = 100f
                }
                startResultReveal()
                if (dragging) {
                    pendingResultFeedback = true
                } else {
                    playResultFeedback()
                }
            } else {
                animateProgressTo(nextState.progress.toFloat())
            }
        }

        if (
            automaticExpansionEnabled &&
            ProgressBubblePolicy.shouldAutoExpand(previousState, nextState) &&
            !dragging
        ) {
            val collapseDelay = ProgressBubblePolicy.autoCollapseDelay(nextState.stage)
            if (collapseDelay == null) {
                removeCallbacks(autoCollapseRunnable)
                setExpanded(true, animated = true)
            } else {
                expandTemporarily(collapseDelay)
            }
        } else if (expanded && previousExpandedWidth != currentExpandedWidthPx()) {
            setExpanded(true, animated = true)
        }
        invalidate()
    }

    private fun revealIfNeeded() {
        hiding = false
        if (visibility != VISIBLE) {
            UiMotion.popIn(this)
        } else {
            if (!dragging) animate().cancel()
            alpha = 1f
        }
    }

    private fun startSpinnerIfNeeded() {
        if (!UiMotion.animationsEnabled()) {
            rotationDegrees = 0f
            indeterminateSweep = DEFAULT_INDETERMINATE_SWEEP
            invalidate()
            return
        }
        if (!spinnerAnimator.isRunning) spinnerAnimator.start()
    }

    private fun startArcTransition(startAngle: Float, startSweep: Float) {
        arcTransitionAnimator?.cancel()
        arcTransitionStartAngle = startAngle
        arcTransitionStartSweep = startSweep
        if (!UiMotion.animationsEnabled()) {
            arcTransitionFraction = 1f
            return
        }
        arcTransitionFraction = 0f
        arcTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ARC_TRANSITION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                arcTransitionFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateProgressTo(target: Float, durationMs: Long = PROGRESS_DURATION_MS) {
        progressAnimator?.cancel()
        if (!UiMotion.animationsEnabled()) {
            displayedProgress = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(displayedProgress, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startResultReveal() {
        resultAnimator?.cancel()
        if (!UiMotion.animationsEnabled()) {
            resultRevealFraction = 1f
            return
        }
        resultRevealFraction = 0f
        resultAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RESULT_REVEAL_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                resultRevealFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun playResultFeedback() {
        when (state?.stage) {
            ProgressBubbleStage.SUCCESS -> UiMotion.emphasize(this)
            ProgressBubbleStage.ERROR -> UiMotion.reject(
                this,
                direction = if (dockSide == ProgressBubbleDockSide.RIGHT) 1f else -1f,
            )
            else -> Unit
        }
    }

    private fun expandTemporarily(durationMs: Long) {
        removeCallbacks(autoCollapseRunnable)
        setExpanded(true, animated = true)
        postDelayed(autoCollapseRunnable, durationMs)
    }

    private fun setExpanded(value: Boolean, animated: Boolean) {
        expanded = value
        val targetWidth = if (value) currentExpandedWidthPx() else compactWidthPx
        val currentWidth = layoutParams.width.takeIf { it > 0 } ?: width
        widthAnimator?.cancel()
        if (!animated || !UiMotion.animationsEnabled() || currentWidth == targetWidth) {
            updateWidth(targetWidth)
            return
        }
        widthAnimator = ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = WIDTH_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { updateWidth(it.animatedValue as Int) }
            start()
        }
    }

    private fun updateWidth(nextWidth: Int) {
        layoutParams = layoutParams.apply { width = nextWidth }
        val targetExpandedWidth = currentExpandedWidthPx()
        expansionFraction = if (targetExpandedWidth == compactWidthPx) {
            0f
        } else {
            ((nextWidth - compactWidthPx).toFloat() / (targetExpandedWidth - compactWidthPx))
                .coerceIn(0f, 1f)
        }
        invalidate()
    }

    private fun updateDisplayText() {
        val currentState = state ?: return
        val availableTextWidth = (
            currentExpandedWidthPx() - compactWidthPx - dp(TEXT_INSET_DP)
            ).coerceAtLeast(0f)
        displayPrimaryText = TextUtils.ellipsize(
            currentState.primaryText,
            primaryTextPaint,
            availableTextWidth,
            TextUtils.TruncateAt.END,
        ).toString()
        displayDetailText = TextUtils.ellipsize(
            currentState.detailText,
            detailTextPaint,
            availableTextWidth,
            TextUtils.TruncateAt.END,
        ).toString()
    }

    private fun currentExpandedWidthPx(): Int {
        val desiredWidthPx = dp(
            ProgressBubblePolicy.desiredExpandedWidth(state?.stage).toFloat()
        ).toInt()
        return ProgressBubblePolicy.expandedWidth(
            desiredWidth = desiredWidthPx,
            availableWidth = availableHorizontalSpacePx,
        ).coerceAtLeast(compactWidthPx)
    }

    private fun drawDetails(canvas: Canvas, centerY: Float) {
        if (expansionFraction <= 0f || displayPrimaryText.isEmpty()) return
        val textLeft = if (dockSide == ProgressBubbleDockSide.RIGHT) {
            dp(TEXT_INSET_DP)
        } else {
            compactWidthPx.toFloat()
        }
        val textRight = if (dockSide == ProgressBubbleDockSide.RIGHT) {
            width - compactWidthPx.toFloat()
        } else {
            width - dp(TEXT_INSET_DP)
        }
        if (textRight <= textLeft) return

        val previousPrimaryAlpha = primaryTextPaint.alpha
        val previousDetailAlpha = detailTextPaint.alpha
        primaryTextPaint.alpha = (255 * expansionFraction).toInt()
        detailTextPaint.alpha = (255 * expansionFraction).toInt()
        val saveCount = canvas.save()
        canvas.clipRect(textLeft, 0f, textRight, height.toFloat())
        canvas.drawText(displayPrimaryText, textLeft, centerY - dp(3f), primaryTextPaint)
        canvas.drawText(displayDetailText, textLeft, centerY + dp(11.5f), detailTextPaint)
        canvas.restoreToCount(saveCount)
        primaryTextPaint.alpha = previousPrimaryAlpha
        detailTextPaint.alpha = previousDetailAlpha
    }

    private fun drawProgressCore(canvas: Canvas, centerY: Float) {
        val currentState = state ?: return
        val centerX = if (dockSide == ProgressBubbleDockSide.RIGHT) {
            width - compactWidthPx / 2f
        } else {
            compactWidthPx / 2f
        }
        val radius = dp(20f)
        arcBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        progressPaint.color = when (currentState.stage) {
            ProgressBubbleStage.SUCCESS -> successProgressColor
            ProgressBubbleStage.ERROR -> errorProgressColor
            else -> activeProgressColor
        }

        if (currentState.progress == null) {
            canvas.drawArc(
                arcBounds,
                -90f + rotationDegrees,
                indeterminateSweep,
                false,
                progressPaint,
            )
        } else {
            val targetSweep = displayedProgress * 3.6f
            val startAngle = lerp(arcTransitionStartAngle, -90f, arcTransitionFraction)
            val sweep = lerp(arcTransitionStartSweep, targetSweep, arcTransitionFraction)
            canvas.drawArc(arcBounds, startAngle, sweep, false, progressPaint)
        }

        when (currentState.stage) {
            ProgressBubbleStage.RESOLVING,
            ProgressBubbleStage.PREPARING,
            ProgressBubbleStage.FINALIZING -> drawDownloadIcon(
                canvas,
                centerX,
                centerY + loadingIconOffsetY,
                showTray = currentState.stage == ProgressBubbleStage.FINALIZING,
            )
            ProgressBubbleStage.DOWNLOADING -> {
                if (currentState.progress == null) {
                    drawDownloadIcon(canvas, centerX, centerY + loadingIconOffsetY)
                } else {
                    val label = PERCENT_LABELS[displayedProgress.toInt().coerceIn(0, 100)]
                    drawCenterText(canvas, centerX, centerY, label, 11f)
                }
            }
            ProgressBubbleStage.SUCCESS -> drawSuccess(canvas, centerX, centerY)
            ProgressBubbleStage.ERROR -> drawCenterText(canvas, centerX, centerY, "!", 15f)
        }
    }

    private fun drawDownloadIcon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        showTray: Boolean = false,
    ) {
        val arrowTipY = centerY + dp(6.5f)
        canvas.drawLine(centerX, centerY - dp(9f), centerX, arrowTipY, downloadIconPaint)
        canvas.drawLine(centerX - dp(7f), centerY, centerX, arrowTipY, downloadIconPaint)
        canvas.drawLine(centerX + dp(7f), centerY, centerX, arrowTipY, downloadIconPaint)
        if (showTray) {
            canvas.drawLine(
                centerX - dp(8.5f),
                centerY + dp(10f),
                centerX + dp(8.5f),
                centerY + dp(10f),
                downloadIconPaint,
            )
        }
    }

    private fun drawCenterText(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        text: String,
        textSizeSp: Float,
    ) {
        centerTextPaint.textSize = sp(textSizeSp)
        val baseline = centerY - (centerTextPaint.ascent() + centerTextPaint.descent()) / 2f
        canvas.drawText(text, centerX, baseline, centerTextPaint)
    }

    private fun drawSuccess(canvas: Canvas, centerX: Float, centerY: Float) {
        val firstStartX = centerX - dp(8f)
        val firstStartY = centerY
        val jointX = centerX - dp(2f)
        val jointY = centerY + dp(6f)
        val endX = centerX + dp(9f)
        val endY = centerY - dp(7f)
        val firstFraction = (resultRevealFraction / CHECK_FIRST_SEGMENT_END).coerceIn(0f, 1f)
        canvas.drawLine(
            firstStartX,
            firstStartY,
            lerp(firstStartX, jointX, firstFraction),
            lerp(firstStartY, jointY, firstFraction),
            resultIconPaint,
        )
        if (resultRevealFraction > CHECK_FIRST_SEGMENT_END) {
            val secondFraction = ((resultRevealFraction - CHECK_FIRST_SEGMENT_END) /
                (1f - CHECK_FIRST_SEGMENT_END)).coerceIn(0f, 1f)
            canvas.drawLine(
                jointX,
                jointY,
                lerp(jointX, endX, secondFraction),
                lerp(jointY, endY, secondFraction),
                resultIconPaint,
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private companion object {
        const val WIDTH_DURATION_MS = 220L
        const val PROGRESS_DURATION_MS = 180L
        const val ARC_TRANSITION_DURATION_MS = 220L
        const val RESULT_REVEAL_DURATION_MS = 180L
        const val RESULT_PROGRESS_DURATION_MS = 120L
        const val SPINNER_DURATION_MS = 1_100L
        const val MIN_INDETERMINATE_SWEEP = 40f
        const val MAX_INDETERMINATE_SWEEP = 110f
        const val DEFAULT_INDETERMINATE_SWEEP = 92f
        const val CHECK_FIRST_SEGMENT_END = 0.42f
        const val RESTING_ELEVATION_DP = 10f
        const val DRAG_ELEVATION_DP = 16f
        const val TEXT_INSET_DP = 12f
        const val LOADING_ICON_TRAVEL_DP = 1.6f
        const val CONTAINER_COLOR = 0xEB303137.toInt()
        const val TOP_HIGHLIGHT_COLOR = 0x1FFFFFFF
        const val MID_HIGHLIGHT_COLOR = 0x08FFFFFF
        const val BOTTOM_SHADE_COLOR = 0x12000000
        const val BORDER_COLOR = 0x38FFFFFF
        const val TRACK_COLOR = 0x38FFFFFF
        const val DETAIL_TEXT_COLOR = 0xADFFFFFF.toInt()
        val PERCENT_LABELS = Array(101) { "$it%" }
    }
}
