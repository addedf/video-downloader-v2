package com.zemin.downloader.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.common.core.StoreModule
import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.common.core.currentTitle
import com.zemin.downloader.common.base.BaseActivity
import com.zemin.downloader.common.util.ExceptionLogStore
import com.zemin.downloader.common.util.DownloadHistoryStore
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.databinding.ActivityMainBinding
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.download.DownloadFlowController
import com.zemin.downloader.ui.motion.MotionBottomSheetController
import com.zemin.downloader.ui.motion.UiMotion
import com.zemin.downloader.ui.preview.PreviewSectionRenderer
import com.zemin.downloader.ui.util.PlatformResolver
import com.zemin.downloader.ui.util.extractSharedText
import com.zemin.downloader.ui.view.DyActionButton
import com.zemin.downloader.update.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
    // Activity fields are initialized before Context.attachBaseContext. Defer construction because
    // AppUpdateManager reads applicationContext and SharedPreferences in its initializer.
    private val appUpdateManager by lazy(LazyThreadSafetyMode.NONE) { AppUpdateManager(this) }

    lateinit var bubble: ProgressBubbleController
        private set
    lateinit var previewSection: PreviewSectionRenderer
        private set
    lateinit var downloadFlow: DownloadFlowController
        private set

    val ui: ActivityMainBinding get() = binding
    val currentPlatformTitle: String get() = currentTitle

    private var suppressInputChangeHandling = false
    private var lastClipboardPromptUrl: String? = null
    private var pendingClipboardInput: String? = null
    private var systemInsetTop = 0
    private var systemInsetBottom = 0
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private lateinit var mineSheetController: MotionBottomSheetController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bubble = ProgressBubbleController(this, lifecycleScope)
        previewSection = PreviewSectionRenderer(this, lifecycleScope)
        downloadFlow = DownloadFlowController(this)
        setupMotion()
        bubble.setup()
        previewSection.setup()
        binding.videoPreview.bindFullscreen(this)
        readSharedText(intent)
        appUpdateManager.checkOnStart()
        binding.btnDownload.setOnClickListener {
            val input = binding.etUrl.text.toString().trim()
            when {
                input.isEmpty() -> {
                    showError(getString(R.string.main_toast_empty_input, currentTitle))
                }
                downloadFlow.isDownloading -> {
                    showError(getString(R.string.main_toast_task_running))
                }
                else -> {
                    UiMotion.performHaptic(binding.btnDownload, UiMotion.Haptic.TICK)
                    downloadFlow.resolveAndRenderPreview(input)
                }
            }
        }
        binding.btnClear.setOnClickListener {
            UiMotion.performHaptic(binding.btnClear, UiMotion.Haptic.TICK)
            clearLinkAndCancelDownload()
        }
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressInputChangeHandling) return
                val input = s?.toString()?.trim().orEmpty()
                if (input != previewSection.input) previewSection.clear()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.btnHistoryOpen.setOnClickListener { downloadFlow.openLatestHistoryFile() }
        binding.btnHistoryShare.setOnClickListener { downloadFlow.shareLatestHistoryFile() }
        binding.btnHistoryRetry.setOnClickListener { downloadFlow.retryLatestHistory() }
        binding.btnHistoryClear.setOnClickListener {
            DownloadHistoryStore.clear()
            downloadFlow.refreshHistoryUi()
            toast(getString(R.string.main_toast_history_cleared))
        }
        binding.btnCopyLink.setOnClickListener {
            copyCurrentPreviewLink()
            UiMotion.performHaptic(binding.btnCopyLink, UiMotion.Haptic.CONFIRM)
        }
        binding.btnSaveSheet.setOnClickListener {
            UiMotion.performHaptic(binding.btnSaveSheet, UiMotion.Haptic.TICK)
            downloadFlow.saveCurrentPreview()
        }
        binding.btnMine.setOnClickListener {
            UiMotion.performHaptic(binding.btnMine, UiMotion.Haptic.TICK)
            showMineSheet()
        }
        binding.btnLogin.setOnClickListener {
            UiMotion.performHaptic(binding.btnLogin, UiMotion.Haptic.TICK)
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.btnExceptionLogs.setOnClickListener { showExceptionLogsDialog() }
        binding.btnExceptionLogsClear.setOnClickListener {
            ExceptionLogStore.clear()
            refreshExceptionLogUi()
            toast(getString(R.string.main_exception_log_cleared))
        }
        binding.btnCloseMineSheet.setOnClickListener { hideSheets() }
        binding.dialogMask.setOnClickListener { hideClipboardDialog() }
        binding.clipboardDialogPanel.setOnClickListener { }
        binding.btnClipboardDismiss.setOnClickListener { hideClipboardDialog() }
        binding.btnClipboardParse.setOnClickListener {
            val input = pendingClipboardInput.orEmpty()
            UiMotion.performHaptic(binding.btnClipboardParse, UiMotion.Haptic.TICK)
            hideClipboardDialog()
            if (input.isNotBlank()) {
                setInputText(input)
                previewSection.clear()
                downloadFlow.resolveAndRenderPreview(input)
            }
        }
        styleActionButtons()
        downloadFlow.refreshHistoryUi()
        refreshActionButton()
    }

    override fun onResume() {
        super.onResume()
        binding.videoPreview.onHostResume()
        refreshLoginUi()
        scheduleClipboardCheck()
    }

    override fun onPause() {
        binding.videoPreview.onHostPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedText(intent)
    }

    override fun onDestroy() {
        bubble.dispose()
        previewSection.dispose()
        binding.videoPreview.stopPlayback()
        mineSheetController.hideImmediately()
        hideClipboardDialog(immediate = true)
        super.onDestroy()
    }

    /** androidTest 直接驱动预览加载用。 */
    internal fun loadPreviewImageForTesting(imageUrl: String) {
        previewSection.loadPreviewImageForTesting(imageUrl)
    }

    private fun copyCurrentPreviewLink() {
        val source = previewSection.preview?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: previewSection.input?.takeIf { it.isNotBlank() }
            ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), source))
        toast(getString(R.string.main_toast_link_copied))
    }

    private fun setupMotion() {
        mineSheetController = MotionBottomSheetController(
            container = binding.sheetMask,
            scrim = binding.sheetScrim,
            sheet = binding.mineSheet,
        )
        UiMotion.bindPressFeedback(binding.btnMine)
        UiMotion.bindPressFeedback(binding.checkLiveVideo, pressedScale = 0.98f)
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.dialogMask.visibility == View.VISIBLE -> hideClipboardDialog()
                mineSheetController.isShowing -> hideSheets()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    fun showMineSheet() {
        refreshLoginUi()
        downloadFlow.refreshHistoryUi()
        refreshExceptionLogUi()
        mineSheetController.show()
    }

    private fun refreshExceptionLogUi() {
        val logs = ExceptionLogStore.getAll()
        binding.exceptionSection.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        if (logs.isNotEmpty()) {
            binding.tvExceptionSummary.text = getString(
                R.string.main_exception_log_count_format,
                logs.size,
            )
        }
    }

    private fun showExceptionLogsDialog() {
        showExceptionLogSheet(this) { refreshExceptionLogUi() }
    }

    private fun refreshLoginUi() {
        val visible = currentDownloadType == DownloadType.DOU_YIN
        binding.loginSection.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val status = if (StoreModule.loggedIn()) {
                getString(R.string.main_login_status_logged_in)
            } else {
                getString(R.string.main_login_status_logged_out)
            }
            binding.tvLoginStatus.text = getString(R.string.main_login_status, status)
        }
    }

    internal fun hideSheets() {
        mineSheetController.hide()
    }

    private fun showClipboardDialog(input: String) {
        pendingClipboardInput = input
        binding.tvClipboardDialogMessage.text = getString(
            R.string.main_clipboard_dialog_message_format,
            input,
        )
        UiMotion.showDialog(binding.dialogMask, binding.clipboardDialogPanel)
    }

    private fun hideClipboardDialog(immediate: Boolean = false) {
        if (immediate) {
            binding.dialogMask.animate().cancel()
            binding.clipboardDialogPanel.animate().cancel()
            binding.dialogMask.visibility = View.GONE
            pendingClipboardInput = null
            return
        }
        UiMotion.hideDialog(binding.dialogMask, binding.clipboardDialogPanel) {
            pendingClipboardInput = null
        }
    }

    private fun styleActionButtons() {
        binding.btnClear.setStyle(DyActionButton.Style.SECONDARY)
        binding.btnDownload.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnCopyLink.setStyle(DyActionButton.Style.GHOST)
        binding.btnSaveSheet.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnClipboardDismiss.setStyle(DyActionButton.Style.GHOST)
        binding.btnClipboardParse.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnHistoryOpen.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnHistoryShare.setStyle(DyActionButton.Style.SECONDARY)
        binding.btnHistoryRetry.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnHistoryClear.setStyle(DyActionButton.Style.SECONDARY)
        binding.btnCloseMineSheet.setStyle(DyActionButton.Style.GHOST)
    }

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun applySystemBarInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemInsetTop = systemBars.top
            systemInsetBottom = systemBars.bottom
            systemInsetLeft = systemBars.left
            systemInsetRight = systemBars.right
            bubble.updateInsets(systemBars.top, systemBars.bottom, systemBars.left, systemBars.right)

            binding.tvAppTitle.layoutParams = binding.tvAppTitle.layoutParams.apply {
                height = dp(APP_HEADER_HEIGHT_DP) + systemBars.top
            }
            binding.tvAppTitle.setPadding(
                dp(16) + systemBars.left,
                systemBars.top,
                dp(16) + systemBars.right,
                0,
            )
            binding.contentPanel.setPadding(
                dp(8) + systemBars.left,
                dp(8),
                dp(8) + systemBars.right,
                dp(CONTENT_BOTTOM_NAV_SPACE_DP) + systemBars.bottom,
            )
            binding.bottomNav.layoutParams = binding.bottomNav.layoutParams.apply {
                height = dp(BOTTOM_NAV_HEIGHT_DP) + systemBars.bottom
            }
            binding.bottomNav.setPadding(
                dp(8) + systemBars.left,
                dp(4),
                dp(8) + systemBars.right,
                dp(4) + systemBars.bottom,
            )
            bubble.onInsetsChanged()
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    override fun onAbilityChanged(downloadType: DownloadType) {
        refreshPlatformUi()
        refreshLoginUi()
    }

    private fun clearLinkAndCancelDownload() {
        binding.etUrl.text?.clear()
        previewSection.clear()
        toast(getString(R.string.main_status_link_cleared))
    }

    internal fun setInputText(text: String) {
        suppressInputChangeHandling = true
        binding.etUrl.setText(text)
        binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
        suppressInputChangeHandling = false
    }

    private fun readSharedText(intent: Intent?) {
        val sharedText = extractSharedText(intent)
        if (sharedText.isNotBlank()) {
            val result = PlatformResolver.resolve(sharedText)
            val inputText = result?.normalizedInput ?: sharedText
            setInputText(inputText)
            previewSection.clear()
            if (result == null) toast(getString(R.string.main_toast_supported_platforms_only))
        }
    }

    private fun scheduleClipboardCheck() {
        if (downloadFlow.isDownloading) return
        lifecycleScope.launch {
            delay(CLIPBOARD_CHECK_DELAY_MS)
            checkClipboardForSupportedLink()
        }
    }

    private fun checkClipboardForSupportedLink() {
        if (downloadFlow.isDownloading) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return
        val description = clipboard.primaryClipDescription ?: return
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            return
        }
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val result = PlatformResolver.resolve(text) ?: return
        if (result.url == lastClipboardPromptUrl) return
        val currentInput = binding.etUrl.text?.toString()?.trim().orEmpty()
        if (currentInput == result.normalizedInput) return
        lastClipboardPromptUrl = result.url
        showClipboardDialog(result.normalizedInput)
    }

    private fun refreshPlatformUi() {
        binding.tvAppTitle.text =
            getString(R.string.main_platform_selector_title_format, currentTitle)
        binding.tvInputTitle.text = when (currentDownloadType) {
            DownloadType.DOU_YIN -> getString(R.string.main_input_title_douyin)
            DownloadType.XIAO_HONG_SHU -> getString(R.string.main_input_title_xhs)
            DownloadType.TWITTER -> getString(R.string.main_input_title_format, getString(R.string.name_x))
        }
        binding.etUrl.hint = getString(R.string.main_share_input_hint)
        bubble.refreshAutoExpansion()
    }

    internal fun setUiEnabled(enabled: Boolean) {
        binding.etUrl.isEnabled = enabled
        binding.btnDownload.isEnabled = enabled
        // 清除按钮保持常可用：下载/解析进行中也允许清空输入放弃当前任务。
        binding.btnClear.isEnabled = true
    }

    internal fun showDownloadFailure(message: String?) {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.main_error_unknown)
        bubble.showError(
            primaryText = getString(R.string.main_progress_error),
            detailText = getString(R.string.main_progress_error_detail),
            accessibilityDetail = detail,
        )
        showError(detail)
    }

    internal fun showError(message: String?) {
        if (message.isNullOrEmpty()) return
        UiMotion.reject(binding.downloadSection)
        UiMotion.performHaptic(binding.downloadSection, UiMotion.Haptic.REJECT)
        toast(message)
    }

    internal fun showUnsupportedLink() {
        previewSection.clear()
        showError(getString(R.string.main_toast_supported_platforms_only))
    }

    internal fun refreshActionButton() {
        binding.btnDownload.text = getString(R.string.main_button_download)
    }

    private companion object {
        const val CLIPBOARD_CHECK_DELAY_MS = 500L
        const val APP_HEADER_HEIGHT_DP = 48
        const val BOTTOM_NAV_HEIGHT_DP = 58
        const val CONTENT_BOTTOM_NAV_SPACE_DP = 64
    }
}
