package com.zemin.downloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.view.DyPreviewCardView
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewImageInstrumentedTest {
    @Test
    fun outgoingImageRemainsUntilTheNextImageIsReady() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val previewCard = activity.findViewById<DyPreviewCardView>(R.id.previewCard)
        val ambient = activity.findViewById<ImageView>(R.id.ivPreviewAmbient)
        val scrim = activity.findViewById<View>(R.id.previewAmbientScrim)
        val foreground = activity.findViewById<ImageView>(R.id.ivPreviewCover)
        val incoming = activity.findViewById<ImageView>(R.id.ivPreviewCoverIncoming)

        instrumentation.runOnMainSync {
            activity.findViewById<View>(R.id.previewSection).visibility = View.VISIBLE
        }
        assertTrue(waitUntil { previewCard.width > 0 && previewCard.height > 0 })

        instrumentation.runOnMainSync {
            activity.loadPreviewImageForTesting(dataImageUrl(Color.RED))
        }
        assertTrue(waitUntil { foreground.drawable != null && incoming.visibility == View.GONE })
        val outgoing = foreground.drawable
        assertNotNull(outgoing)

        instrumentation.runOnMainSync {
            activity.loadPreviewImageForTesting(dataImageUrl(Color.BLUE))
            assertSame(outgoing, foreground.drawable)
            assertEquals(View.VISIBLE, foreground.visibility)
        }

        assertTrue(waitUntil {
            foreground.drawable != null && foreground.drawable !== outgoing &&
                incoming.visibility == View.GONE
        })
        assertEquals(View.VISIBLE, ambient.visibility)
        assertEquals(View.VISIBLE, scrim.visibility)

        instrumentation.runOnMainSync {
            activity.finish()
        }
    }

    private fun dataImageUrl(color: Int): String {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        return "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
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

    private fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(16L)
        }
        return condition()
    }
}
