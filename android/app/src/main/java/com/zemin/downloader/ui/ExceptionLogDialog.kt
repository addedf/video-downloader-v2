package com.zemin.downloader.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zemin.downloader.R
import com.zemin.downloader.common.util.ExceptionLogRecord
import com.zemin.downloader.common.util.ExceptionLogStore
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.ui.view.DyActionButton

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

/** 「我的」面板里的异常日志列表 + 详情 BottomSheet。 */
fun showExceptionLogSheet(context: Context, onEmpty: () -> Unit) {
    val logs = ExceptionLogStore.getAll()
    if (logs.isEmpty()) {
        toast(context.getString(R.string.main_exception_log_empty))
        onEmpty()
        return
    }

    var selectedIndex = 0
    val detail = TextView(context).apply {
        setTextColor(context.getColor(R.color.dy_primary_light))
        textSize = 12f
        setPadding(12, 12, 12, 12)
        text = ExceptionLogStore.formatForCopy(logs.first())
    }

    fun copyLog(log: ExceptionLogRecord) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                context.getString(R.string.main_exception_log_title),
                ExceptionLogStore.formatForCopy(log),
            )
        )
        toast(context.getString(R.string.main_exception_log_copied))
    }

    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    logs.forEachIndexed { index, log ->
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundResource(R.drawable.bg_section_download)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 8) }
        }
        val item = TextView(context).apply {
            text = "${log.displayTime} · ${log.operation} · ${log.channel}\n${log.stage}\n${log.parseException.take(120)}"
            setTextColor(context.getColor(R.color.dy_primary_light))
            textSize = 13f
            setOnClickListener {
                selectedIndex = index
                detail.text = ExceptionLogStore.formatForCopy(log)
            }
        }
        val copy = TextView(context).apply {
            text = context.getString(R.string.main_exception_log_copy_one)
            setTextColor(context.getColor(R.color.dy_primary_light))
            textSize = 13f
            setPadding(0, 10, 0, 0)
            setOnClickListener { copyLog(log) }
        }
        row.addView(item)
        row.addView(copy)
        list.addView(row)
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 18))
        addView(View(context).apply {
            setBackgroundResource(R.drawable.bg_sheet_grabber)
            layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 14)
            }
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.main_exception_log_dialog_title)
            setTextColor(context.getColor(R.color.dy_primary))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.main_exception_log_dialog_hint)
            setTextColor(context.getColor(R.color.dy_text_muted))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 4); bottomMargin = dp(context, 10) }
        })
        addView(ScrollView(context).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 180),
            )
        })
        addView(ScrollView(context).apply {
            addView(detail)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 260),
            )
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.main_exception_log_redaction_note)
            setTextColor(context.getColor(R.color.dy_text_muted))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 8) }
        })
        val close = DyActionButton(context).apply {
            text = context.getString(R.string.main_exception_log_close)
            setStyle(DyActionButton.Style.GHOST)
        }
        val copyCurrent = DyActionButton(context).apply {
            text = context.getString(R.string.main_exception_log_copy)
            setStyle(DyActionButton.Style.PRIMARY)
            setOnClickListener { copyLog(logs[selectedIndex]) }
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 44),
            ).apply { topMargin = dp(context, 10) }
            addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(context, 8)
            })
            addView(copyCurrent, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        })
    }

    val dialog = BottomSheetDialog(context)
    (content.getChildAt(content.childCount - 1) as? LinearLayout)?.let { actions ->
        actions.getChildAt(0).setOnClickListener { dialog.dismiss() }
    }
    dialog.setContentView(content)
    dialog.show()
}
