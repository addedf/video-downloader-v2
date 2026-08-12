package com.zemin.downloader.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.zemin.downloader.R

class DyPreviewCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val rect = RectF()
    private val cornerRadius = dp(8f)
    @ColorInt
    var canvasColor: Int = ContextCompat.getColor(context, R.color.dy_preview_canvas)
        private set

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    fun setCanvasColor(@ColorInt color: Int) {
        if (canvasColor == color) return
        canvasColor = color
        invalidate()
    }

    fun resetCanvasColor() {
        setCanvasColor(ContextCompat.getColor(context, R.color.dy_preview_canvas))
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        fillPaint.color = canvasColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        strokePaint.color = ContextCompat.getColor(context, R.color.dy_stroke)
        val inset = strokePaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        rect.inset(-inset, -inset)
        super.onDraw(canvas)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
