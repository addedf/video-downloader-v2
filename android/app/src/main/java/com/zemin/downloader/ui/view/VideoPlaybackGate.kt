package com.zemin.downloader.ui.view

internal class VideoPlaybackGate {
    private var sourceActive = false
    private var hostResumed = true
    private var autoPlayRequested = false

    fun activateSource(autoPlay: Boolean) {
        sourceActive = true
        autoPlayRequested = autoPlay
    }

    fun clearSource() {
        sourceActive = false
        autoPlayRequested = false
    }

    fun onHostResume() {
        hostResumed = true
    }

    fun onHostPause() {
        hostResumed = false
        autoPlayRequested = false
    }

    fun hasActiveSource(): Boolean = sourceActive

    fun shouldAutoPlayOnPrepared(): Boolean =
        sourceActive && hostResumed && autoPlayRequested
}
