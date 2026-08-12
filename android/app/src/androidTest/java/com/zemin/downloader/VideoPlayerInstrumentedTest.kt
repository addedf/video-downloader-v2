package com.zemin.downloader

import android.content.Intent
import android.graphics.Rect
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.view.VideoPlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPlayerInstrumentedTest {
    @Test
    fun controlsRequireTapAndBackExitsFullscreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity

        instrumentation.runOnMainSync {
            activity.findViewById<View>(R.id.previewSection).visibility = View.VISIBLE
            activity.findViewById<VideoPlayerView>(R.id.videoPreview).apply {
                visibility = View.VISIBLE
                showUnavailable()
            }
        }
        instrumentation.waitForIdleSync()

        val videoPlayer = activity.findViewById<VideoPlayerView>(R.id.videoPreview)
        val controls = activity.findViewById<View>(R.id.playerControls)
        assertEquals(View.INVISIBLE, controls.visibility)

        instrumentation.runOnMainSync {
            activity.findViewById<View>(R.id.playerSurface).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(View.VISIBLE, controls.visibility)
        assertBottomControlsInsidePlayer(activity, videoPlayer)

        instrumentation.runOnMainSync {
            activity.findViewById<View>(R.id.btnPlayerFullscreen).performClick()
        }
        instrumentation.waitForIdleSync()
        assertTrue(videoPlayer.isFullscreen)
        assertBottomControlsInsidePlayer(activity, videoPlayer)

        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        instrumentation.waitForIdleSync()
        assertFalse(videoPlayer.isFullscreen)

        instrumentation.runOnMainSync { activity.finish() }
    }

    private fun assertBottomControlsInsidePlayer(activity: MainActivity, player: VideoPlayerView) {
        val playerBounds = Rect().also { assertTrue(player.getGlobalVisibleRect(it)) }
        BOTTOM_CONTROL_IDS.forEach { id ->
            val controlBounds = Rect()
            val control = activity.findViewById<View>(id)
            assertTrue("Control $id should be visible", control.getGlobalVisibleRect(controlBounds))
            assertTrue(
                "Control $id should fit inside the player",
                playerBounds.contains(controlBounds),
            )
        }
    }

    private companion object {
        val BOTTOM_CONTROL_IDS = intArrayOf(
            R.id.playerSeekBar,
            R.id.tvPlayerTime,
            R.id.btnPlayerSpeed,
            R.id.btnPlayerMute,
            R.id.btnPlayerFullscreen,
        )
    }
}
