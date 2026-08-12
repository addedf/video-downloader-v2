package com.zemin.downloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.view.DownloadProgressBubbleView
import com.zemin.downloader.ui.view.ProgressBubblePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionInstrumentedTest {
    @Test
    fun previewUsesAmbientCropBehindCompleteForegroundImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val ambient = activity.findViewById<ImageView>(R.id.ivPreviewAmbient)
        val foreground = activity.findViewById<ImageView>(R.id.ivPreviewCover)
        val incoming = activity.findViewById<ImageView>(R.id.ivPreviewCoverIncoming)

        assertEquals(ImageView.ScaleType.CENTER_CROP, ambient.scaleType)
        assertEquals(ImageView.ScaleType.FIT_CENTER, foreground.scaleType)
        assertEquals(ImageView.ScaleType.FIT_CENTER, incoming.scaleType)
        assertEquals(1.08f, ambient.scaleX, 0.001f)
        assertEquals(1.08f, ambient.scaleY, 0.001f)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun bottomSheetOpensAndBackHidesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val sheetMask = activity.findViewById<View>(R.id.sheetMask)

        instrumentation.runOnMainSync {
            activity.findViewById<View>(R.id.btnMine).performClick()
        }
        assertTrue(waitUntil { sheetMask.visibility == View.VISIBLE })

        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertTrue(waitUntil { sheetMask.visibility == View.GONE })

        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun progressBubbleStaysInSafeLaneAndDragDisablesUnsafeAutoExpansion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val bubble = activity.findViewById<DownloadProgressBubbleView>(R.id.progressBubble)

        instrumentation.runOnMainSync {
            bubble.showResolving("解析中", "正在获取作品信息")
        }
        assertTrue(waitUntil { bubble.visibility == View.VISIBLE && bubble.width > bubble.compactInteractionWidth })
        assertEquals("解析中，正在获取作品信息", bubble.contentDescription)

        instrumentation.runOnMainSync {
            bubble.showFinalizing("正在保存", "正在写入系统相册")
        }
        assertEquals("正在保存，正在写入系统相册", bubble.contentDescription)
        instrumentation.runOnMainSync {
            bubble.showSuccess("保存完成", "文件已保存到系统相册")
        }
        val density = activity.resources.displayMetrics.density
        val successWidth = (ProgressBubblePolicy.SUCCESS_EXPANDED_WIDTH_DP * density).toInt()
        assertTrue(waitUntil { bubble.visibility == View.VISIBLE && bubble.width == successWidth })
        val bubbleLocation = IntArray(2).also(bubble::getLocationOnScreen)
        val sectionLocation = IntArray(2).also(
            activity.findViewById<View>(R.id.downloadSection)::getLocationOnScreen
        )
        assertTrue(bubbleLocation[1] + bubble.height - 2f * density <= sectionLocation[1])
        val appTitle = activity.findViewById<TextView>(R.id.tvAppTitle)
        val titleLocation = IntArray(2).also(appTitle::getLocationOnScreen)
        val titleTextRight = titleLocation[0] + appTitle.paddingLeft +
            appTitle.paint.measureText(appTitle.text.toString())
        assertTrue(bubbleLocation[0] >= titleTextRight + 12f * density)

        dragToLeftEdge(instrumentation, bubble, density)
        assertTrue(
            waitUntil(timeoutMs = 3_000L) {
                bubble.x <= 10f * density && bubble.width == bubble.compactInteractionWidth
            }
        )
        assertEquals(View.VISIBLE, bubble.visibility)
        assertNotEquals(View.LAYER_TYPE_SOFTWARE, bubble.layerType)
        val dockedX = bubble.x
        val dockedY = bubble.y
        val positionTolerancePx = 1f

        instrumentation.runOnMainSync { bubble.hide() }
        assertTrue(waitUntil { bubble.visibility == View.GONE })
        assertEquals(dockedX, bubble.x, positionTolerancePx)
        assertEquals(dockedY, bubble.y, positionTolerancePx)

        instrumentation.runOnMainSync {
            bubble.showProgress(35, "正在下载", "7 MB / 20 MB · 3.2 MB/s")
        }
        assertTrue(
            waitUntil {
                bubble.visibility == View.VISIBLE && bubble.width == bubble.compactInteractionWidth
            }
        )
        assertEquals(dockedX, bubble.x, positionTolerancePx)
        assertEquals(dockedY, bubble.y, positionTolerancePx)

        instrumentation.runOnMainSync { activity.finish() }
    }

    private fun dragToLeftEdge(
        instrumentation: android.app.Instrumentation,
        bubble: DownloadProgressBubbleView,
        density: Float,
    ) {
        val location = IntArray(2).also { bubble.getLocationOnScreen(it) }
        val startX = location[0] + bubble.width - 28f * density
        val startY = location[1] + bubble.height / 2f
        val endX = 28f * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
        )
        for (step in 1..6) {
            val fraction = step / 6f
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    downTime,
                    downTime + step * 32L,
                    MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * fraction,
                    startY,
                    0,
                )
            )
        }
        instrumentation.sendPointerSync(
            MotionEvent.obtain(
                downTime,
                downTime + 240L,
                MotionEvent.ACTION_UP,
                endX,
                startY,
                0,
            )
        )
    }

    private fun startMainActivity(
        instrumentation: android.app.Instrumentation,
    ): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    private fun clearClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(32L)
        }
        return condition()
    }
}
