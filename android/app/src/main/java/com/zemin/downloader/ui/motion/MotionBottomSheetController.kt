package com.zemin.downloader.ui.motion

import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior

class MotionBottomSheetController(
    private val container: View,
    private val scrim: View,
    private val sheet: View,
) {
    private val behavior = BottomSheetBehavior.from(sheet)
    private var draggedByUser = false

    val isShowing: Boolean
        get() = container.visibility == View.VISIBLE

    init {
        behavior.isHideable = true
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        scrim.alpha = 0f
        scrim.setOnClickListener { hide() }
        sheet.setOnClickListener { }
        behavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_DRAGGING -> draggedByUser = true
                        BottomSheetBehavior.STATE_EXPANDED -> {
                            if (draggedByUser) UiMotion.performHaptic(sheet, UiMotion.Haptic.TICK)
                            draggedByUser = false
                        }
                        BottomSheetBehavior.STATE_HIDDEN -> {
                            if (draggedByUser) UiMotion.performHaptic(sheet, UiMotion.Haptic.TICK)
                            draggedByUser = false
                            scrim.alpha = 0f
                            container.visibility = View.GONE
                        }
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    scrim.alpha = (1f + slideOffset.coerceAtMost(0f)).coerceIn(0f, 1f)
                }
            }
        )
    }

    fun show() {
        if (isShowing) return
        if (!UiMotion.animationsEnabled()) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            scrim.alpha = 1f
            sheet.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            return
        }
        container.visibility = View.VISIBLE
        sheet.visibility = View.VISIBLE
        container.post {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun hide() {
        if (container.visibility != View.VISIBLE) return
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        if (!UiMotion.animationsEnabled()) {
            scrim.alpha = 0f
            container.visibility = View.GONE
        }
    }

    fun hideImmediately() {
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        scrim.alpha = 0f
        container.visibility = View.GONE
    }
}
