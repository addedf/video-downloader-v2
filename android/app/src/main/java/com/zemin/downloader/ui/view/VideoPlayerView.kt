package com.zemin.downloader.ui.view

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zemin.downloader.R
import com.zemin.downloader.ui.motion.UiMotion

class VideoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val videoSurface: CenteredVideoView
    private val controlsOverlay: View
    private val loadingIndicator: ProgressBar
    private val statusText: TextView
    private val centerControls: View
    private val playPauseButton: ImageButton
    private val rewindButton: View
    private val forwardButton: View
    private val seekBar: SeekBar
    private val timeText: TextView
    private val speedButton: TextView
    private val muteButton: ImageButton
    private val fullscreenButton: ImageButton
    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var isSeeking = false
    private var playbackSpeed = DEFAULT_SPEED
    private var muted = false
    private var autoPlay = true
    private var playbackErrorListener: (() -> Unit)? = null

    private var fullscreenActivity: ComponentActivity? = null
    private var originalParent: ViewGroup? = null
    private var originalIndex = -1
    private var originalLayoutParams: ViewGroup.LayoutParams? = null
    private var fullscreenBackCallback: OnBackPressedCallback? = null
    private var lightStatusBars = true
    private var lightNavigationBars = true
    var isFullscreen: Boolean = false
        private set

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (isPrepared) handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }
    private val hideControlsRunnable = Runnable { hideControls() }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_video_player, this, true)
        videoSurface = findViewById(R.id.playerSurface)
        controlsOverlay = findViewById(R.id.playerControls)
        loadingIndicator = findViewById(R.id.playerLoading)
        statusText = findViewById(R.id.playerStatus)
        centerControls = findViewById(R.id.playerCenterControls)
        playPauseButton = findViewById(R.id.btnPlayerPlayPause)
        rewindButton = findViewById(R.id.btnPlayerRewind)
        forwardButton = findViewById(R.id.btnPlayerForward)
        seekBar = findViewById(R.id.playerSeekBar)
        timeText = findViewById(R.id.tvPlayerTime)
        speedButton = findViewById(R.id.btnPlayerSpeed)
        muteButton = findViewById(R.id.btnPlayerMute)
        fullscreenButton = findViewById(R.id.btnPlayerFullscreen)

        setBackgroundColor(context.getColor(R.color.dy_player_canvas))
        listOf(
            playPauseButton,
            rewindButton,
            forwardButton,
            speedButton,
            muteButton,
            fullscreenButton,
        ).forEach { UiMotion.bindPressFeedback(it, pressedScale = 0.92f) }
        videoSurface.setOnClickListener { toggleControls() }
        controlsOverlay.setOnClickListener { hideControls() }
        playPauseButton.setOnClickListener { togglePlayback() }
        rewindButton.setOnClickListener { seekBy(-VideoPlayerControls.REWIND_MS) }
        forwardButton.setOnClickListener { seekBy(VideoPlayerControls.FORWARD_MS) }
        speedButton.setOnClickListener { cyclePlaybackSpeed() }
        muteButton.setOnClickListener { toggleMute() }
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        setupSeekBar()
        setupMediaListeners()
        hideControlsImmediately()
        updateControlState()
    }

    fun bindFullscreen(activity: ComponentActivity) {
        fullscreenActivity = activity
        fullscreenBackCallback?.remove()
        fullscreenBackCallback = activity.onBackPressedDispatcher.addCallback(
            owner = activity,
            enabled = false,
        ) {
            exitFullscreen()
        }
    }

    fun setVideo(
        uri: Uri,
        headers: Map<String, String> = emptyMap(),
        autoPlay: Boolean = true,
        onError: (() -> Unit)? = null,
    ) {
        resetPlaybackState()
        this.autoPlay = autoPlay
        playbackErrorListener = onError
        visibility = View.VISIBLE
        statusText.visibility = View.GONE
        centerControls.visibility = View.INVISIBLE
        loadingIndicator.visibility = View.VISIBLE
        hideControlsImmediately()
        videoSurface.setVideoURI(uri, headers)
        videoSurface.requestFocus()
    }

    fun pausePlayback() {
        if (!isPrepared || !videoSurface.isPlaying) return
        videoSurface.pause()
        updateControlState()
        showControls(scheduleHide = false)
    }

    fun stopPlayback() {
        if (isFullscreen) exitFullscreen()
        handler.removeCallbacksAndMessages(null)
        runCatching { videoSurface.stopPlayback() }
        resetPlaybackState()
        statusText.visibility = View.GONE
    }

    fun clearVideoSize() = videoSurface.clearVideoSize()

    fun showUnavailable() {
        resetPlaybackState()
        visibility = View.VISIBLE
        loadingIndicator.visibility = View.GONE
        statusText.setText(R.string.player_preview_unavailable)
        statusText.visibility = View.VISIBLE
        centerControls.visibility = View.INVISIBLE
        hideControlsImmediately()
    }

    fun exitFullscreen(): Boolean {
        if (!isFullscreen) return false
        val activity = fullscreenActivity ?: return false
        val parent = originalParent ?: return false
        val currentParent = this.parent as? ViewGroup
        currentParent?.removeView(this)
        val targetIndex = originalIndex.coerceIn(0, parent.childCount)
        val restoredLayoutParams = originalLayoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        parent.addView(this, targetIndex, restoredLayoutParams)
        originalParent = null
        originalLayoutParams = null
        originalIndex = -1
        isFullscreen = false
        fullscreenBackCallback?.isEnabled = false
        elevation = 0f
        updateFullscreenIcon()

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = lightStatusBars
        controller.isAppearanceLightNavigationBars = lightNavigationBars
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestLayout()
        showControls()
        return true
    }

    private fun setupMediaListeners() {
        videoSurface.setOnPreparedListener { player ->
            mediaPlayer = player
            isPrepared = true
            player.isLooping = true
            videoSurface.setVideoSize(player.videoWidth, player.videoHeight)
            applyVolume()
            applyPlaybackSpeed()
            loadingIndicator.visibility = View.GONE
            statusText.visibility = View.GONE
            centerControls.visibility = View.VISIBLE
            seekBar.max = player.duration.coerceAtLeast(1)
            if (autoPlay) videoSurface.start()
            updateControlState()
            handler.removeCallbacks(progressUpdater)
            handler.post(progressUpdater)
            hideControlsImmediately()
        }
        videoSurface.setOnCompletionListener {
            updateControlState()
            showControls(scheduleHide = false)
        }
        videoSurface.setOnErrorListener { _, _, _ ->
            handler.removeCallbacks(progressUpdater)
            isPrepared = false
            mediaPlayer = null
            keepScreenOn = false
            loadingIndicator.visibility = View.GONE
            statusText.setText(R.string.player_preview_failed)
            statusText.visibility = View.VISIBLE
            centerControls.visibility = View.INVISIBLE
            updateControlState()
            hideControlsImmediately()
            playbackErrorListener?.invoke()
            true
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateTime(progress, duration())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: return
                if (isPrepared) videoSurface.seekTo(target)
                isSeeking = false
                updateProgress()
                scheduleControlsHide()
            }
        })
    }

    private fun togglePlayback() {
        if (!isPrepared) return
        if (videoSurface.isPlaying) {
            videoSurface.pause()
            showControls(scheduleHide = false)
        } else {
            videoSurface.start()
            showControls()
        }
        updateControlState()
    }

    private fun seekBy(deltaMs: Int) {
        if (!isPrepared) return
        val target = VideoPlayerControls.seekTarget(
            videoSurface.currentPosition,
            duration(),
            deltaMs,
        )
        videoSurface.seekTo(target)
        updateTime(target, duration())
        showControls()
    }

    private fun cyclePlaybackSpeed() {
        playbackSpeed = VideoPlayerControls.nextSpeed(playbackSpeed)
        applyPlaybackSpeed()
        speedButton.text = VideoPlayerControls.formatSpeed(playbackSpeed)
        speedButton.contentDescription = context.getString(
            R.string.player_speed_description_format,
            speedButton.text,
        )
        showControls()
    }

    private fun applyPlaybackSpeed() {
        val player = mediaPlayer ?: return
        val wasPlaying = videoSurface.isPlaying
        runCatching {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
            if (!wasPlaying) player.pause()
        }
    }

    private fun toggleMute() {
        muted = !muted
        applyVolume()
        updateMuteIcon()
        showControls()
    }

    private fun applyVolume() {
        val volume = if (muted) 0f else 1f
        mediaPlayer?.setVolume(volume, volume)
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
            return
        }
        val activity = fullscreenActivity ?: return
        val parent = parent as? ViewGroup ?: return
        val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        originalParent = parent
        originalIndex = parent.indexOfChild(this)
        originalLayoutParams = layoutParams
        parent.removeView(this)
        host.addView(
            this,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        isFullscreen = true
        fullscreenBackCallback?.isEnabled = true
        elevation = FULLSCREEN_ELEVATION_PX

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        lightStatusBars = controller.isAppearanceLightStatusBars
        lightNavigationBars = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateFullscreenIcon()
        requestLayout()
        showControls()
    }

    private fun toggleControls() {
        if (controlsOverlay.visibility == View.VISIBLE && controlsOverlay.alpha > 0f) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls(scheduleHide: Boolean = true) {
        handler.removeCallbacks(hideControlsRunnable)
        controlsOverlay.animate().cancel()
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.animate()
            .alpha(1f)
            .setDuration(CONTROLS_FADE_MS)
            .start()
        if (scheduleHide) scheduleControlsHide()
    }

    private fun hideControls() {
        if (!isPrepared || !videoSurface.isPlaying || isSeeking) return
        controlsOverlay.animate().cancel()
        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(CONTROLS_FADE_MS)
            .withEndAction { controlsOverlay.visibility = View.INVISIBLE }
            .start()
    }

    private fun hideControlsImmediately() {
        handler.removeCallbacks(hideControlsRunnable)
        controlsOverlay.animate().cancel()
        controlsOverlay.alpha = 0f
        controlsOverlay.visibility = View.INVISIBLE
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPrepared && videoSurface.isPlaying && !isSeeking) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private fun updateProgress() {
        if (!isPrepared) return
        val duration = duration()
        val position = videoSurface.currentPosition.coerceIn(0, duration.coerceAtLeast(0))
        if (!isSeeking) seekBar.progress = position
        updateTime(position, duration)
    }

    private fun updateTime(positionMs: Int, durationMs: Int) {
        timeText.text = context.getString(
            R.string.player_time_format,
            VideoPlayerControls.formatTime(positionMs),
            VideoPlayerControls.formatTime(durationMs),
        )
    }

    private fun duration(): Int = runCatching { videoSurface.duration.coerceAtLeast(0) }.getOrDefault(0)

    private fun updateControlState() {
        val playing = isPrepared && videoSurface.isPlaying
        playPauseButton.setImageDrawable(
            AppCompatResources.getDrawable(
                context,
                if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
            ),
        )
        playPauseButton.contentDescription = context.getString(
            if (playing) R.string.player_pause else R.string.player_play,
        )
        rewindButton.isEnabled = isPrepared
        forwardButton.isEnabled = isPrepared
        seekBar.isEnabled = isPrepared
        speedButton.isEnabled = isPrepared
        muteButton.isEnabled = isPrepared
        keepScreenOn = playing
        updateTime(if (isPrepared) videoSurface.currentPosition else 0, duration())
    }

    private fun updateMuteIcon() {
        muteButton.setImageDrawable(
            AppCompatResources.getDrawable(
                context,
                if (muted) R.drawable.ic_player_volume_off else R.drawable.ic_player_volume_on,
            ),
        )
        muteButton.contentDescription = context.getString(
            if (muted) R.string.player_unmute else R.string.player_mute,
        )
    }

    private fun updateFullscreenIcon() {
        fullscreenButton.setImageDrawable(
            AppCompatResources.getDrawable(
                context,
                if (isFullscreen) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen,
            ),
        )
        fullscreenButton.contentDescription = context.getString(
            if (isFullscreen) R.string.player_exit_fullscreen else R.string.player_enter_fullscreen,
        )
    }

    private fun resetPlaybackState() {
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideControlsRunnable)
        isPrepared = false
        isSeeking = false
        mediaPlayer = null
        seekBar.max = 1
        seekBar.progress = 0
        videoSurface.clearVideoSize()
        loadingIndicator.visibility = View.GONE
        centerControls.visibility = View.INVISIBLE
        keepScreenOn = false
        hideControlsImmediately()
        updateControlState()
    }

    private companion object {
        const val DEFAULT_SPEED = 1f
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        const val CONTROLS_HIDE_DELAY_MS = 2_800L
        const val CONTROLS_FADE_MS = 160L
        const val FULLSCREEN_ELEVATION_PX = 100f
    }
}
