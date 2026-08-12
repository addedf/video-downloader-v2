package com.zemin.downloader.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.zemin.downloader.R
import com.zemin.downloader.ui.motion.UiMotion

class DyTabButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(26f).toInt()
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        UiMotion.bindPressFeedback(this, pressedScale = 0.97f)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        setTextColor(ContextCompat.getColor(context, if (isSelected) R.color.dy_primary else R.color.dy_text_muted))
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
