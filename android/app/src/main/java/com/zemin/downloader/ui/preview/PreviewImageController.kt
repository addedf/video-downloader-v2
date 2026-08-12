package com.zemin.downloader.ui.preview

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.decode.DataSource
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.lifecycle
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.ViewSizeResolver
import coil3.toBitmap
import com.zemin.downloader.R
import com.zemin.downloader.ui.motion.UiMotion
import com.zemin.downloader.ui.view.DyPreviewCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal class PreviewImageController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val previewCard: DyPreviewCardView,
    private val ambientView: ImageView,
    private val ambientScrim: View,
    private val currentView: ImageView,
    private val incomingView: ImageView,
    private val imageLoader: ImageLoader = SingletonImageLoader.get(context),
) {
    private var generation = 0L
    private var previewJob: Job? = null
    private var fallbackColorAnimator: ValueAnimator? = null
    private var ambientTransitionCleanup: Runnable? = null
    private var ambientDrawable: Drawable? = null
    private var fallbackCanvasColor: Int? = null
    private val thumbnailRequests = mutableListOf<Disposable>()
    private val prefetchRequests = mutableListOf<Disposable>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurRadius = dp(26f)
            ambientView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    Shader.TileMode.CLAMP,
                )
            )
            ambientView.colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0.68f) }
            )
        }
    }

    fun load(
        imageUrl: String,
        adjacentUrls: List<String>,
        headers: Map<String, String>,
    ) {
        generation++
        val requestGeneration = generation
        previewJob?.cancel()
        clearPrefetch()
        settleForeground()
        settleAmbient()
        showStoredImage()

        val request = requestBuilder(
            imageUrl = imageUrl,
            headers = headers,
            scale = Scale.FILL,
            allowHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
            .size(ViewSizeResolver(previewCard))
            .build()

        previewJob = scope.launch {
            val result = imageLoader.execute(request)
            if (requestGeneration != generation || result !is SuccessResult) return@launch
            showLoadedImage(
                image = result.image,
                memoryCacheHit = result.dataSource == DataSource.MEMORY_CACHE,
                requestGeneration = requestGeneration,
            )
        }
        prefetch(adjacentUrls, headers)
    }

    fun loadThumbnail(
        imageUrl: String,
        imageView: ImageView,
        fallback: TextView,
        headers: Map<String, String>,
    ) {
        val request = requestBuilder(
            imageUrl = imageUrl,
            headers = headers,
            scale = Scale.FILL,
            allowHardware = true,
        )
            .size(ViewSizeResolver(imageView))
            .target(
                onStart = {
                    imageView.setImageDrawable(null)
                    fallback.visibility = View.VISIBLE
                },
                onError = { fallback.visibility = View.VISIBLE },
                onSuccess = { image ->
                    imageView.setImageDrawable(image.asDrawable(context.resources))
                    fallback.visibility = View.GONE
                },
            )
            .build()
        thumbnailRequests += imageLoader.enqueue(request)
    }

    fun clearThumbnails() {
        thumbnailRequests.forEach(Disposable::dispose)
        thumbnailRequests.clear()
    }

    fun hideForVideo() {
        generation++
        previewJob?.cancel()
        previewJob = null
        clearPrefetch()
        settleForeground()
        settleAmbient()
        currentView.visibility = View.GONE
        incomingView.visibility = View.GONE
        ambientView.visibility = View.GONE
        ambientScrim.visibility = View.GONE
        previewCard.resetCanvasColor()
    }

    fun clear() {
        generation++
        previewJob?.cancel()
        previewJob = null
        clearPrefetch()
        clearThumbnails()
        fallbackColorAnimator?.cancel()
        fallbackColorAnimator = null
        ambientTransitionCleanup?.let(ambientView::removeCallbacks)
        ambientTransitionCleanup = null
        currentView.animate().cancel()
        incomingView.animate().cancel()
        currentView.setImageDrawable(null)
        incomingView.setImageDrawable(null)
        ambientView.setImageDrawable(null)
        resetTransform(currentView)
        resetTransform(incomingView)
        currentView.visibility = View.GONE
        incomingView.visibility = View.GONE
        ambientView.visibility = View.GONE
        ambientScrim.visibility = View.GONE
        ambientDrawable = null
        fallbackCanvasColor = null
        previewCard.resetCanvasColor()
    }

    fun dispose() {
        clear()
    }

    private fun showLoadedImage(
        image: Image,
        memoryCacheHit: Boolean,
        requestGeneration: Long,
    ) {
        val hasOutgoing = currentView.drawable != null && currentView.visibility == View.VISIBLE
        updateAmbient(image, hasOutgoing, requestGeneration)

        incomingView.animate().cancel()
        currentView.animate().cancel()
        incomingView.setImageDrawable(image.asDrawable(context.resources))
        incomingView.visibility = View.VISIBLE
        ambientScrim.visibility = View.VISIBLE

        if (!UiMotion.animationsEnabled()) {
            commitIncoming(requestGeneration)
            return
        }

        val duration = PreviewImageMotionSpec.foregroundDuration(memoryCacheHit)
        incomingView.alpha = 0f
        incomingView.scaleX = PreviewImageMotionSpec.INCOMING_START_SCALE
        incomingView.scaleY = PreviewImageMotionSpec.INCOMING_START_SCALE
        if (hasOutgoing) {
            currentView.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        incomingView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { commitIncoming(requestGeneration) }
            .start()
    }

    private fun commitIncoming(requestGeneration: Long) {
        if (requestGeneration != generation) return
        val drawable = incomingView.drawable ?: return
        currentView.setImageDrawable(drawable)
        resetTransform(currentView)
        currentView.visibility = View.VISIBLE
        incomingView.setImageDrawable(null)
        resetTransform(incomingView)
        incomingView.visibility = View.GONE
    }

    private fun settleForeground() {
        currentView.animate().cancel()
        incomingView.animate().cancel()
        incomingView.drawable?.let(currentView::setImageDrawable)
        if (currentView.drawable != null) currentView.visibility = View.VISIBLE
        incomingView.setImageDrawable(null)
        incomingView.visibility = View.GONE
        resetTransform(currentView)
        resetTransform(incomingView)
    }

    private fun updateAmbient(image: Image, animate: Boolean, requestGeneration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previewCard.resetCanvasColor()
            val next = image.asDrawable(context.resources)
            val previous = ambientDrawable
            ambientDrawable = next
            ambientView.visibility = View.VISIBLE
            if (!animate || !UiMotion.animationsEnabled() || previous == null) {
                ambientView.setImageDrawable(next)
                return
            }

            val transition = TransitionDrawable(arrayOf(previous, next)).apply {
                isCrossFadeEnabled = true
            }
            ambientView.setImageDrawable(transition)
            transition.startTransition(PreviewImageMotionSpec.BACKGROUND_DURATION_MS.toInt())
            val cleanup = Runnable {
                if (requestGeneration == generation) ambientView.setImageDrawable(next)
            }
            ambientTransitionCleanup = cleanup
            ambientView.postDelayed(cleanup, PreviewImageMotionSpec.BACKGROUND_DURATION_MS)
        } else {
            ambientView.visibility = View.GONE
            val baseColor = ContextCompat.getColor(context, R.color.dy_preview_canvas)
            val targetColor = runCatching {
                val bitmap = image.toBitmap(
                    width = AMBIENT_SAMPLE_SIZE,
                    height = AMBIENT_SAMPLE_SIZE,
                    config = Bitmap.Config.ARGB_8888,
                )
                val pixels = IntArray(AMBIENT_SAMPLE_SIZE * AMBIENT_SAMPLE_SIZE)
                bitmap.getPixels(
                    pixels,
                    0,
                    AMBIENT_SAMPLE_SIZE,
                    0,
                    0,
                    AMBIENT_SAMPLE_SIZE,
                    AMBIENT_SAMPLE_SIZE,
                )
                PreviewAmbientColorPolicy.fromPixels(
                    pixels,
                    AMBIENT_SAMPLE_SIZE,
                    AMBIENT_SAMPLE_SIZE,
                    baseColor,
                )
            }.getOrDefault(baseColor)
            fallbackCanvasColor = targetColor
            animateCanvasColor(targetColor, animate && UiMotion.animationsEnabled())
        }
    }

    private fun animateCanvasColor(targetColor: Int, animated: Boolean) {
        fallbackColorAnimator?.cancel()
        if (!animated) {
            previewCard.setCanvasColor(targetColor)
            return
        }
        fallbackColorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            previewCard.canvasColor,
            targetColor,
        ).apply {
            duration = PreviewImageMotionSpec.BACKGROUND_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { previewCard.setCanvasColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun settleAmbient() {
        ambientTransitionCleanup?.let(ambientView::removeCallbacks)
        ambientTransitionCleanup = null
        ambientDrawable?.let(ambientView::setImageDrawable)
        fallbackColorAnimator?.end()
        fallbackColorAnimator = null
    }

    private fun showStoredImage() {
        if (currentView.drawable == null) return
        currentView.visibility = View.VISIBLE
        ambientScrim.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ambientView.visibility = if (ambientDrawable == null) View.GONE else View.VISIBLE
            previewCard.resetCanvasColor()
        } else {
            ambientView.visibility = View.GONE
            fallbackCanvasColor?.let(previewCard::setCanvasColor)
        }
    }

    private fun prefetch(urls: List<String>, headers: Map<String, String>) {
        clearPrefetch()
        urls.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .forEach { url ->
                val request = requestBuilder(
                    imageUrl = url,
                    headers = headers,
                    scale = Scale.FILL,
                    allowHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                )
                    .size(ViewSizeResolver(previewCard))
                    .build()
                prefetchRequests += imageLoader.enqueue(request)
            }
    }

    private fun clearPrefetch() {
        prefetchRequests.forEach(Disposable::dispose)
        prefetchRequests.clear()
    }

    private fun requestBuilder(
        imageUrl: String,
        headers: Map<String, String>,
        scale: Scale,
        allowHardware: Boolean,
    ): ImageRequest.Builder {
        val requestFingerprint = requestFingerprint(imageUrl, headers)
        val networkHeaders = NetworkHeaders.Builder().apply {
            headers.forEach { (name, value) -> this[name] = value }
        }.build()
        return ImageRequest.Builder(context)
            .data(imageUrl)
            .httpHeaders(networkHeaders)
            .memoryCacheKeyExtra(REQUEST_FINGERPRINT_CACHE_KEY, requestFingerprint)
            .diskCacheKey(requestFingerprint)
            .scale(scale)
            .precision(Precision.INEXACT)
            .allowHardware(allowHardware)
            .lifecycle(lifecycleOwner)
    }

    private fun requestFingerprint(imageUrl: String, headers: Map<String, String>): String {
        val canonicalRequest = buildString {
            append(imageUrl)
            append('\n')
            headers.entries
                .sortedBy { it.key.lowercase(Locale.ROOT) }
                .forEach { (name, value) ->
                    append(name.lowercase(Locale.ROOT))
                    append(':')
                    append(value)
                    append('\n')
                }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_CHARS[value ushr 4])
                append(HEX_CHARS[value and 0x0f])
            }
        }
    }

    private fun resetTransform(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    private companion object {
        const val AMBIENT_SAMPLE_SIZE = 64
        const val REQUEST_FINGERPRINT_CACHE_KEY = "request-fingerprint"
        const val HEX_CHARS = "0123456789abcdef"
    }
}
