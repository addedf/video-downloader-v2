package com.zemin.downloader.ui.view

import java.util.Locale

object VideoPlayerControls {
    const val REWIND_MS = 5_000
    const val FORWARD_MS = 15_000

    private val playbackSpeeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    fun seekTarget(positionMs: Int, durationMs: Int, deltaMs: Int): Int {
        if (durationMs <= 0) return 0
        return (positionMs.toLong() + deltaMs)
            .coerceIn(0L, durationMs.toLong())
            .toInt()
    }

    fun nextSpeed(currentSpeed: Float): Float {
        val currentIndex = playbackSpeeds.indexOfFirst { speed ->
            kotlin.math.abs(speed - currentSpeed) < 0.01f
        }
        return playbackSpeeds[(currentIndex + 1).mod(playbackSpeeds.size)]
    }

    fun formatTime(positionMs: Int): String {
        val totalSeconds = positionMs.coerceAtLeast(0) / 1_000
        val hours = totalSeconds / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatSpeed(speed: Float): String = when (speed) {
        1f -> "1.0x"
        else -> "${speed}x"
    }
}
