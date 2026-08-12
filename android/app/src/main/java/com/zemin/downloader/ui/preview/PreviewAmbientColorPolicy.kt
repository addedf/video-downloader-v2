package com.zemin.downloader.ui.preview

import kotlin.math.max
import kotlin.math.min

internal object PreviewAmbientColorPolicy {
    private const val EDGE_FRACTION = 0.1f
    private const val EDGE_COLOR_WEIGHT = 0.42f
    private const val SATURATION_FACTOR = 0.42f
    private const val MIN_VISIBLE_ALPHA = 64

    fun fromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        baseColor: Int,
    ): Int {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return baseColor

        val edgeSize = max(1, (min(width, height) * EDGE_FRACTION).toInt())
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var sampleCount = 0L

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x >= edgeSize && x < width - edgeSize &&
                    y >= edgeSize && y < height - edgeSize
                ) {
                    continue
                }
                val color = pixels[y * width + x]
                if (alpha(color) < MIN_VISIBLE_ALPHA) continue
                redTotal += red(color)
                greenTotal += green(color)
                blueTotal += blue(color)
                sampleCount++
            }
        }
        if (sampleCount == 0L) return baseColor

        val edgeRed = (redTotal / sampleCount).toInt()
        val edgeGreen = (greenTotal / sampleCount).toInt()
        val edgeBlue = (blueTotal / sampleCount).toInt()
        val luminance = (edgeRed * 0.2126f + edgeGreen * 0.7152f + edgeBlue * 0.0722f)
            .toInt()
        val mutedRed = mix(luminance, edgeRed, SATURATION_FACTOR)
        val mutedGreen = mix(luminance, edgeGreen, SATURATION_FACTOR)
        val mutedBlue = mix(luminance, edgeBlue, SATURATION_FACTOR)

        return argb(
            alpha = 255,
            red = mix(red(baseColor), mutedRed, EDGE_COLOR_WEIGHT),
            green = mix(green(baseColor), mutedGreen, EDGE_COLOR_WEIGHT),
            blue = mix(blue(baseColor), mutedBlue, EDGE_COLOR_WEIGHT),
        )
    }

    private fun mix(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).toInt().coerceIn(0, 255)

    private fun alpha(color: Int): Int = color ushr 24 and 0xff
    private fun red(color: Int): Int = color ushr 16 and 0xff
    private fun green(color: Int): Int = color ushr 8 and 0xff
    private fun blue(color: Int): Int = color and 0xff

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xff) shl 24) or
            ((red and 0xff) shl 16) or
            ((green and 0xff) shl 8) or
            (blue and 0xff)
}
