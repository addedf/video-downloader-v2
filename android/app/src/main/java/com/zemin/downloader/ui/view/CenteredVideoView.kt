package com.zemin.downloader.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.VideoView
import kotlin.math.roundToInt

class CenteredVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : VideoView(context, attrs, defStyleAttr) {
    private var sourceWidth = 0
    private var sourceHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        requestLayout()
    }

    fun clearVideoSize() {
        sourceWidth = 0
        sourceHeight = 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec)

        if (sourceWidth <= 0 || sourceHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val availableRatio = availableWidth.toFloat() / availableHeight
        val measuredWidth: Int
        val measuredHeight: Int
        if (sourceRatio > availableRatio) {
            measuredWidth = availableWidth
            measuredHeight = (availableWidth / sourceRatio).roundToInt()
        } else {
            measuredHeight = availableHeight
            measuredWidth = (availableHeight * sourceRatio).roundToInt()
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}
