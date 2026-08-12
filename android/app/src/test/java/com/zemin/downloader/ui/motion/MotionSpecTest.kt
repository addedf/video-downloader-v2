package com.zemin.downloader.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSpecTest {
    @Test
    fun edgeTarget_usesNearestEdgeWithoutVelocity() {
        assertEquals(8f, MotionSpec.edgeTarget(30f, 0f, 8f, 300f), 0f)
        assertEquals(300f, MotionSpec.edgeTarget(250f, 0f, 8f, 300f), 0f)
    }

    @Test
    fun edgeTarget_projectsReleaseVelocity() {
        assertEquals(300f, MotionSpec.edgeTarget(130f, 600f, 8f, 300f), 0f)
        assertEquals(8f, MotionSpec.edgeTarget(180f, -600f, 8f, 300f), 0f)
    }

    @Test
    fun edgeTarget_handlesCollapsedBounds() {
        assertEquals(24f, MotionSpec.edgeTarget(100f, 500f, 24f, 20f), 0f)
    }
}
