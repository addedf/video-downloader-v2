package com.zemin.downloader.ui.motion

import android.animation.ValueAnimator
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap
import kotlin.math.abs

object UiMotion {
    enum class Haptic { TICK, CONFIRM, REJECT }
    enum class HorizontalEdge { LEFT, RIGHT }

    private const val SPRING_SCALE_X = 1
    private const val SPRING_SCALE_Y = 2
    private const val SPRING_TRANSLATION_X = 3
    private const val SPRING_TRANSLATION_Y = 4
    private const val SPRING_POSITION_X = 5
    private val springs = WeakHashMap<View, MutableMap<Int, SpringAnimation>>()

    fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun bindPressFeedback(view: View, pressedScale: Float = MotionSpec.PRESS_SCALE) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(target, pressedScale)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(target)
            }
            false
        }
    }

    fun revealFromBelow(view: View) {
        view.animate().cancel()
        cancelSpring(view, SPRING_TRANSLATION_Y)
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        view.visibility = View.VISIBLE
        if (!animationsEnabled()) {
            resetTransform(view)
            return
        }
        view.alpha = 0f
        view.translationY = view.dp(12f)
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.animate()
            .alpha(1f)
            .setDuration(MotionSpec.ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        startSpring(
            view,
            SPRING_TRANSLATION_Y,
            DynamicAnimation.TRANSLATION_Y,
            0f,
            MotionSpec.LIGHT_STIFFNESS,
            MotionSpec.LIGHT_DAMPING,
        )
        springScaleToRest(view, MotionSpec.LIGHT_DAMPING)
    }

    fun concealBelow(view: View, endVisibility: Int = View.GONE) {
        if (view.visibility != View.VISIBLE) {
            view.visibility = endVisibility
            resetTransform(view)
            return
        }
        view.animate().cancel()
        cancelSpring(view, SPRING_TRANSLATION_Y)
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        if (!animationsEnabled()) {
            view.visibility = endVisibility
            resetTransform(view)
            return
        }
        view.animate()
            .alpha(0f)
            .translationY(view.dp(8f))
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(MotionSpec.EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = endVisibility
                resetTransform(view)
            }
            .start()
    }

    fun showDialog(mask: View, panel: View) {
        mask.animate().cancel()
        panel.animate().cancel()
        mask.visibility = View.VISIBLE
        if (!animationsEnabled()) {
            mask.alpha = 1f
            resetTransform(panel)
            return
        }
        mask.alpha = 0f
        panel.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.translationY = panel.dp(8f)
        mask.animate()
            .alpha(1f)
            .setDuration(MotionSpec.ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionSpec.ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        springScaleToRest(panel, MotionSpec.EMPHASIS_DAMPING)
    }

    fun hideDialog(mask: View, panel: View, onHidden: () -> Unit = {}) {
        if (mask.visibility != View.VISIBLE) {
            onHidden()
            return
        }
        mask.animate().cancel()
        panel.animate().cancel()
        cancelSpring(panel, SPRING_SCALE_X)
        cancelSpring(panel, SPRING_SCALE_Y)
        if (!animationsEnabled()) {
            mask.visibility = View.GONE
            resetTransform(mask)
            resetTransform(panel)
            onHidden()
            return
        }
        panel.animate()
            .alpha(0f)
            .translationY(panel.dp(6f))
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(MotionSpec.EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        mask.animate()
            .alpha(0f)
            .setDuration(MotionSpec.EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                mask.visibility = View.GONE
                resetTransform(mask)
                resetTransform(panel)
                onHidden()
            }
            .start()
    }

    fun contentChanged(view: View) {
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        if (!animationsEnabled()) {
            resetTransform(view)
            return
        }
        view.alpha = 0.72f
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.animate()
            .alpha(1f)
            .setDuration(MotionSpec.ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        springScaleToRest(view, MotionSpec.LIGHT_DAMPING)
    }

    fun popIn(view: View) {
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        view.visibility = View.VISIBLE
        if (!animationsEnabled()) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.scaleX = 0.78f
        view.scaleY = 0.78f
        view.animate()
            .alpha(1f)
            .setDuration(MotionSpec.ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        springScaleToRest(view, MotionSpec.EMPHASIS_DAMPING)
    }

    fun fadeOut(view: View, endVisibility: Int = View.GONE) {
        if (view.visibility != View.VISIBLE) {
            view.visibility = endVisibility
            return
        }
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        if (!animationsEnabled()) {
            view.visibility = endVisibility
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(MotionSpec.EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = endVisibility
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            .start()
    }

    fun emphasize(view: View) {
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        if (!animationsEnabled()) {
            resetTransform(view)
            return
        }
        view.scaleX = MotionSpec.RESULT_EMPHASIS_SCALE
        view.scaleY = MotionSpec.RESULT_EMPHASIS_SCALE
        springScaleToRest(view, MotionSpec.EMPHASIS_DAMPING)
    }

    fun reject(view: View, direction: Float = 1f) {
        view.animate().cancel()
        cancelSpring(view, SPRING_TRANSLATION_X)
        if (!animationsEnabled()) {
            view.translationX = 0f
            return
        }
        view.translationX = -view.dp(7f) * direction
        startSpring(
            view,
            SPRING_TRANSLATION_X,
            DynamicAnimation.TRANSLATION_X,
            0f,
            MotionSpec.LIGHT_STIFFNESS + 260f,
            0.48f,
            startVelocity = view.dp(880f) * direction,
        )
    }

    fun animateTabIndicator(indicator: View, target: View, animated: Boolean) {
        if (target.width <= 0) {
            target.post { animateTabIndicator(indicator, target, animated) }
            return
        }
        indicator.layoutParams = indicator.layoutParams.apply { width = target.width }
        val targetX = target.left.toFloat()
        val canAnimate = animated && animationsEnabled() && indicator.visibility == View.VISIBLE
        indicator.visibility = View.VISIBLE
        if (!canAnimate) {
            cancelSpring(indicator, SPRING_POSITION_X)
            indicator.translationX = targetX
            return
        }
        startSpring(
            indicator,
            SPRING_POSITION_X,
            DynamicAnimation.TRANSLATION_X,
            targetX,
            MotionSpec.LIGHT_STIFFNESS,
            MotionSpec.LIGHT_DAMPING,
        )
    }

    fun performHaptic(view: View, haptic: Haptic) {
        val feedback = when (haptic) {
            Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.CONFIRM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            Haptic.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
        view.performHapticFeedback(feedback)
    }

    fun bindEdgeSnap(
        view: View,
        boundsProvider: () -> RectF,
        onDragStarted: () -> Unit,
        onEdgeSettled: (HorizontalEdge) -> Unit = {},
    ) {
        view.setOnTouchListener(
            EdgeSnapTouchListener(
                view = view,
                boundsProvider = boundsProvider,
                onDragStarted = onDragStarted,
                onEdgeSettled = onEdgeSettled,
            )
        )
    }

    fun liftForDrag(view: View, elevationDp: Float) {
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        view.elevation = view.dp(elevationDp)
        if (!animationsEnabled()) {
            view.scaleX = MotionSpec.DRAG_LIFT_SCALE
            view.scaleY = MotionSpec.DRAG_LIFT_SCALE
            return
        }
        view.animate()
            .scaleX(MotionSpec.DRAG_LIFT_SCALE)
            .scaleY(MotionSpec.DRAG_LIFT_SCALE)
            .setDuration(MotionSpec.PRESS_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun settleAtEdge(view: View, elevationDp: Float) {
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        view.elevation = view.dp(elevationDp)
        if (!animationsEnabled()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = MotionSpec.EDGE_IMPACT_SCALE_X
        view.scaleY = MotionSpec.EDGE_IMPACT_SCALE_Y
        springScaleToRest(view, MotionSpec.EMPHASIS_DAMPING)
    }

    private fun press(view: View, pressedScale: Float) {
        view.animate().cancel()
        cancelSpring(view, SPRING_SCALE_X)
        cancelSpring(view, SPRING_SCALE_Y)
        if (!animationsEnabled()) return
        view.animate()
            .scaleX(pressedScale)
            .scaleY(pressedScale)
            .setDuration(MotionSpec.PRESS_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun release(view: View) {
        view.animate().cancel()
        if (!animationsEnabled()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        springScaleToRest(view, MotionSpec.LIGHT_DAMPING)
    }

    private fun springScaleToRest(view: View, damping: Float) {
        startSpring(
            view,
            SPRING_SCALE_X,
            DynamicAnimation.SCALE_X,
            1f,
            MotionSpec.LIGHT_STIFFNESS,
            damping,
        )
        startSpring(
            view,
            SPRING_SCALE_Y,
            DynamicAnimation.SCALE_Y,
            1f,
            MotionSpec.LIGHT_STIFFNESS,
            damping,
        )
    }

    private fun startSpring(
        view: View,
        key: Int,
        property: FloatPropertyCompat<View>,
        finalPosition: Float,
        stiffness: Float,
        damping: Float,
        startVelocity: Float = 0f,
    ): SpringAnimation? {
        cancelSpring(view, key)
        if (!animationsEnabled()) {
            property.setValue(view, finalPosition)
            return null
        }
        val animation = SpringAnimation(view, property).apply {
            spring = SpringForce(finalPosition).apply {
                this.stiffness = stiffness
                dampingRatio = damping
            }
            setStartVelocity(startVelocity)
        }
        springs.getOrPut(view) { mutableMapOf() }[key] = animation
        animation.addEndListener { _, _, _, _ ->
            springs[view]?.let { running ->
                if (running[key] === animation) running.remove(key)
                if (running.isEmpty()) springs.remove(view)
            }
        }
        animation.start()
        return animation
    }

    private fun cancelSpring(view: View, key: Int) {
        springs[view]?.remove(key)?.cancel()
        if (springs[view]?.isEmpty() == true) springs.remove(view)
    }

    private fun resetTransform(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun View.dp(value: Float): Float = value * resources.displayMetrics.density

    private class EdgeSnapTouchListener(
        private val view: View,
        private val boundsProvider: () -> RectF,
        private val onDragStarted: () -> Unit,
        private val onEdgeSettled: (HorizontalEdge) -> Unit,
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragged = false
        private var velocityTracker: VelocityTracker? = null
        private var xFling: FlingAnimation? = null
        private var yFling: FlingAnimation? = null

        override fun onTouch(target: View, event: MotionEvent): Boolean {
            addRawMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xFling?.cancel()
                    yFling?.cancel()
                    cancelSpring(view, SPRING_POSITION_X)
                    cancelSpring(view, SPRING_TRANSLATION_Y)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    press(target, MotionSpec.COMPACT_PRESS_SCALE)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragged && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        dragged = true
                        onDragStarted()
                        startX = target.x - deltaX
                        startY = target.y - deltaY
                    }
                    if (dragged) {
                        val bounds = boundsProvider()
                        target.x = (startX + deltaX).coerceIn(bounds.left, bounds.right)
                        target.y = (startY + deltaY).coerceIn(bounds.top, bounds.bottom)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    release(target)
                    if (dragged) {
                        flingAndSnap(velocityX, velocityY)
                    } else {
                        target.performClick()
                    }
                    recycleTracker()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    release(target)
                    if (dragged) flingAndSnap(0f, 0f)
                    recycleTracker()
                    return true
                }
            }
            return false
        }

        private fun addRawMovement(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                recycleTracker()
                velocityTracker = VelocityTracker.obtain()
            }
            val rawEvent = MotionEvent.obtain(event)
            rawEvent.setLocation(event.rawX, event.rawY)
            velocityTracker?.addMovement(rawEvent)
            rawEvent.recycle()
        }

        private fun flingAndSnap(velocityX: Float, velocityY: Float) {
            val bounds = boundsProvider()
            val targetX = MotionSpec.edgeTarget(view.x, velocityX, bounds.left, bounds.right)
            val targetEdge = if (targetX <= (bounds.left + bounds.right) / 2f) {
                HorizontalEdge.LEFT
            } else {
                HorizontalEdge.RIGHT
            }
            if (bounds.right > bounds.left && abs(velocityX) >= MotionSpec.FLING_VELOCITY_THRESHOLD) {
                xFling = FlingAnimation(view, DynamicAnimation.X).apply {
                    setStartVelocity(velocityX)
                    friction = MotionSpec.FLING_FRICTION
                    setMinValue(bounds.left)
                    setMaxValue(bounds.right)
                    addEndListener { _, canceled, _, endVelocity ->
                        if (!canceled) springToHorizontalEdge(targetX, endVelocity, targetEdge)
                    }
                    start()
                }
            } else {
                springToHorizontalEdge(targetX, velocityX, targetEdge)
            }

            if (bounds.bottom > bounds.top && abs(velocityY) >= MotionSpec.FLING_VELOCITY_THRESHOLD) {
                yFling = FlingAnimation(view, DynamicAnimation.Y).apply {
                    setStartVelocity(velocityY)
                    friction = MotionSpec.FLING_FRICTION
                    setMinValue(bounds.top)
                    setMaxValue(bounds.bottom)
                    start()
                }
            } else {
                view.y = view.y.coerceIn(bounds.top, bounds.bottom)
            }
        }

        private fun springToHorizontalEdge(
            targetX: Float,
            velocityX: Float,
            targetEdge: HorizontalEdge,
        ) {
            val animation = startSpring(
                view,
                SPRING_POSITION_X,
                DynamicAnimation.X,
                targetX,
                MotionSpec.LIGHT_STIFFNESS,
                MotionSpec.LIGHT_DAMPING,
                velocityX,
            )
            if (animation == null) {
                onEdgeSettled(targetEdge)
                performHaptic(view, Haptic.TICK)
            } else {
                animation.addEndListener { _, canceled, _, _ ->
                    if (!canceled) {
                        onEdgeSettled(targetEdge)
                        performHaptic(view, Haptic.TICK)
                    }
                }
            }
        }

        private fun recycleTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }
    }
}
