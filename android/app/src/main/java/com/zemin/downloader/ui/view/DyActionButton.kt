package com.zemin.downloader.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.zemin.downloader.R
import com.zemin.downloader.ui.motion.UiMotion

class DyActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    enum class Style { PRIMARY, SECONDARY, GHOST }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()
    private val cornerRadius = dp(8f)
    private val strokeWidth = dp(1f)
    private var buttonStyle = Style.PRIMARY

    init {
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(34f).toInt()
        setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        UiMotion.bindPressFeedback(this)
        setStyle(Style.PRIMARY)
    }

    fun setStyle(style: Style) {
        buttonStyle = style
        val textColor = when (style) {
            Style.PRIMARY -> Color.WHITE
            Style.SECONDARY -> Color.BLACK
            Style.GHOST -> color(R.color.dy_primary_light)
        }
        setTextColor(textColor)
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (isEnabled) 1f else 0.45f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val pressed = isPressed || isSelected
        paint.color = when (buttonStyle) {
            Style.PRIMARY -> color(if (pressed) R.color.dy_primary_light else R.color.dy_primary)
            Style.SECONDARY -> color(if (pressed) R.color.dy_cyan_dark else R.color.dy_cyan)
            Style.GHOST -> color(if (pressed) R.color.dy_surface_strong else R.color.dy_surface)
        }
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
        if (buttonStyle == Style.GHOST) {
            strokePaint.color = color(R.color.dy_stroke)
            strokePaint.strokeWidth = strokeWidth
            val inset = strokeWidth / 2f
            bounds.inset(inset, inset)
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, strokePaint)
            bounds.inset(-inset, -inset)
        }
        super.onDraw(canvas)
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
