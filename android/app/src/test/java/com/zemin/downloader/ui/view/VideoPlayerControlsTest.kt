package com.zemin.downloader.ui.view

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerControlsTest {
    @Test
    fun seekTarget_clampsToVideoBounds() {
        assertEquals(0, VideoPlayerControls.seekTarget(2_000, 10_000, -5_000))
        assertEquals(10_000, VideoPlayerControls.seekTarget(2_000, 10_000, 15_000))
        assertEquals(7_000, VideoPlayerControls.seekTarget(2_000, 10_000, 5_000))
    }

    @Test
    fun playbackSpeed_cyclesThroughSupportedSpeeds() {
        assertEquals(1.25f, VideoPlayerControls.nextSpeed(1f))
        assertEquals(1f, VideoPlayerControls.nextSpeed(0.75f))
    }

    @Test
    fun formatTime_supportsMinutesAndHours() {
        assertEquals("00:02", VideoPlayerControls.formatTime(2_900))
        assertEquals("01:01", VideoPlayerControls.formatTime(61_000))
        assertEquals("1:01:01", VideoPlayerControls.formatTime(3_661_000))
    }
}
