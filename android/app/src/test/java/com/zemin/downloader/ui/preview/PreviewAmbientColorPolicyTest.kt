package com.zemin.downloader.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAmbientColorPolicyTest {
    @Test
    fun transparentImageFallsBackToCanvasColor() {
        val baseColor = 0xffd7dbe0.toInt()

        val result = PreviewAmbientColorPolicy.fromPixels(
            pixels = IntArray(100),
            width = 10,
            height = 10,
            baseColor = baseColor,
        )

        assertEquals(baseColor, result)
    }

    @Test
    fun outerPixelsDriveAmbientColorInsteadOfCenterPixels() {
        val width = 10
        val height = 10
        val red = 0xffff0000.toInt()
        val blue = 0xff0000ff.toInt()
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x == 0 || x == width - 1 || y == 0 || y == height - 1) red else blue
        }

        val result = PreviewAmbientColorPolicy.fromPixels(
            pixels = pixels,
            width = width,
            height = height,
            baseColor = 0xffd7dbe0.toInt(),
        )

        assertNotEquals(0xffd7dbe0.toInt(), result)
        assertTrue(red(result) > blue(result))
    }

    @Test
    fun invalidDimensionsKeepTheExistingCanvasColor() {
        val baseColor = 0xffd7dbe0.toInt()

        assertEquals(
            baseColor,
            PreviewAmbientColorPolicy.fromPixels(IntArray(4), 4, 0, baseColor),
        )
    }

    private fun red(color: Int): Int = color ushr 16 and 0xff
    private fun blue(color: Int): Int = color and 0xff
}
