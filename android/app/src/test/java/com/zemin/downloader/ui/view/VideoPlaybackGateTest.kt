package com.zemin.downloader.ui.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackGateTest {
    @Test
    fun clearingVideoPreventsLatePreparedCallbackFromStartingOldAudio() {
        val gate = VideoPlaybackGate()
        gate.activateSource(autoPlay = true)

        gate.clearSource()

        assertFalse(gate.hasActiveSource())
        assertFalse(gate.shouldAutoPlayOnPrepared())
    }

    @Test
    fun backgroundingCancelsPendingAutoplay() {
        val gate = VideoPlaybackGate()
        gate.activateSource(autoPlay = true)

        gate.onHostPause()
        gate.onHostResume()

        assertTrue(gate.hasActiveSource())
        assertFalse(gate.shouldAutoPlayOnPrepared())
    }
}
