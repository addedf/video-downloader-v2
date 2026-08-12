package com.zemin.downloader.ui.motion

internal object MotionSpec {
    const val PRESS_SCALE = 0.96f
    const val COMPACT_PRESS_SCALE = 0.94f
    const val DRAG_LIFT_SCALE = 1.04f
    const val RESULT_EMPHASIS_SCALE = 1.08f
    const val EDGE_IMPACT_SCALE_X = 0.94f
    const val EDGE_IMPACT_SCALE_Y = 1.04f
    const val LIGHT_DAMPING = 0.82f
    const val EMPHASIS_DAMPING = 0.64f
    const val LIGHT_STIFFNESS = 760f
    const val EMPHASIS_STIFFNESS = 620f
    const val FLING_FRICTION = 2.1f
    const val FLING_VELOCITY_THRESHOLD = 180f
    const val PROJECTION_SECONDS = 0.12f
    const val ENTER_DURATION_MS = 170L
    const val EXIT_DURATION_MS = 130L
    const val PRESS_DURATION_MS = 70L

    fun edgeTarget(
        currentX: Float,
        velocityX: Float,
        minX: Float,
        maxX: Float,
    ): Float {
        if (maxX <= minX) return minX
        val projectedX = currentX + velocityX * PROJECTION_SECONDS
        return if (projectedX <= (minX + maxX) / 2f) minX else maxX
    }
}
