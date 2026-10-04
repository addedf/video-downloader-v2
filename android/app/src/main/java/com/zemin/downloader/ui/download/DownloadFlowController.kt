package com.zemin.downloader.ui.download

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.zemin.downloader.R
import com.zemin.downloader.common.DownloadProgressListener
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.bean.DownloadRequest
import com.zemin.downloader.common.bean.PyDiagnosticsResponse
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.common.core.DownloadModule
import com.zemin.downloader.common.core.StoreModule
import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.common.core.currentType
import com.zemin.downloader.common.util.DownloadHistoryRecord
import com.zemin.downloader.common.util.DownloadHistoryStore
import com.zemin.downloader.common.util.ExceptionLogRecord
import com.zemin.downloader.common.util.ExceptionLogStore
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.motion.UiMotion
import com.zemin.downloader.ui.util.PlatformResolver
import com.zemin.downloader.ui.view.ProgressBubblePolicy
import com.zemin.downloader.ui.view.ProgressBubbleStage
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 解析与下载的编排：调 Python 引擎、记录异常/历史、驱动进度气泡。
 * 展示细节在 [com.zemin.downloader.ui.preview.PreviewSectionRenderer] 与
 * [com.zemin.downloader.ui.ProgressBubbleController]。
 */
class DownloadFlowController(private val host: MainActivity) {
    private val ui get() = host.ui

    val isDownloading: Boolean get() = downloading

    private var downloading = false

    private var resolving = false
    fun resolveAndRenderPreview(
        inputText: String,
        continueCollection: Boolean = false,
    ) {
        if (resolving || downloading) return
        val previous = host.previewSection.preview.takeIf { continueCollection }
        val cursor = previous?.collection?.nextCursor
        if (continueCollection && cursor.isNullOrBlank()) return
        val result = PlatformResolver.resolve(inputText)
        if (result == null) {
            host.showUnsupportedLink()
            return
        }

        resolving = true
        host.lifecycleScope.launch {
            if (currentDownloadType != result.downloadType) {
                BridgeAbilityManager.update(result.downloadType)
            }
            host.setUiEnabled(false)
            host.bubble.cancelHide()
            host.bubble.showResolving(
                primaryText = host.getString(R.string.main_progress_resolving),
                detailText = host.getString(R.string.main_progress_resolving_detail),
            )
            if (previous == null) host.previewSection.clear()
            try {
                val next = DownloadModule.resolve(result.normalizedInput, cursor)
                val preview = com.zemin.downloader.ui.preview.CollectionPreviewPolicy.merge(previous, next)
                if (!preview.ok) {
                    recordFailure(
                        inputText = result.normalizedInput,
                        stage = "解析结果校验",
                        error = preview.error ?: preview.message,
                        responseSummary = preview.diagnostics?.responseSummary
                            ?.takeIf { it.isNotBlank() } ?: buildResolveResponseSummary(preview),
                        retryInfo = preview.diagnostics?.retryInfo()
                            ?: "可重新解析；登录后可尝试 Cookie 兜底",
                        channelOverride = preview.diagnostics?.channel,
                        stageOverride = preview.diagnostics?.stages?.lastOrNull()?.name,
                        diagnostics = preview.diagnostics,
                    )
                    host.showError(preview.error ?: preview.message)
                    return@launch
                }
                host.setInputText(result.normalizedInput)
                if (preview.diagnostics?.channel == "cookie_fallback") {
                    recordFailure(
                        inputText = result.normalizedInput,
                        stage = preview.diagnostics?.stages?.lastOrNull()?.name
                            ?: "resolve_cookie_fallback",
                        error = "匿名解析失败，Cookie 兜底解析成功",
                        responseSummary = preview.diagnostics?.responseSummary
                            ?.takeIf { it.isNotBlank() } ?: buildResolveResponseSummary(preview),
                        retryInfo = "匿名解析失败后使用 Cookie 兜底成功",
                        channelOverride = "cookie_fallback",
                        statusOverride = "兜底成功",
                        diagnostics = preview.diagnostics,
                    )
                }
                host.previewSection.render(result.normalizedInput, preview, preserveSelection = previous != null)
            } catch (e: Exception) {
                recordFailure(
                    inputText = result.normalizedInput,
                    stage = "解析调用",
                    error = e.message ?: e::class.java.simpleName,
                    responseSummary = "客户端异常：${e::class.java.simpleName}",
                    retryInfo = "可重新解析；登录后可尝试 Cookie 兜底",
                )
                host.showError(
                    host.getString(
                        R.string.main_error_exception,
                        e.message ?: host.getString(R.string.main_error_unknown)
                    )
                )
            } finally {
                resolving = false
                host.setUiEnabled(true)
                host.bubble.hide()
            }
        }
    }

    fun startDownload(
        shareText: String,
        preview: PyResolveResult? = null,
        request: DownloadRequest? = null,
    ) {
        if (downloading || resolving) return
        downloading = true
        host.setUiEnabled(false)
        host.bubble.cancelHide()
        host.bubble.showPreparing(
            primaryText = host.getString(R.string.main_progress_preparing),
            detailText = host.getString(R.string.main_progress_preparing_detail),
        )
        host.bubble.markProgressRecorded()

        host.lifecycleScope.launch {
            var progressHideDelayMs = ProgressBubblePolicy.resultHideDelay(
                ProgressBubbleStage.SUCCESS
            )
            val taskStartedAt = System.currentTimeMillis()
            val historySourceUrl = preview?.sourceUrl ?: shareText
            val historyTitle = preview?.title?.takeIf { it.isNotBlank() } ?: host.getString(
                R.string.main_input_title_douyin
            )
            val historyMediaType = request?.selection?.let { selection ->
                if (selection.resourceType == "image" && selection.includeLiveVideo) {
                    "image+live_video"
                } else {
                    selection.resourceType
                }
            } ?: preview?.mediaType ?: currentType
            try {
                withContext(Dispatchers.IO) {
                    StoreModule.cleanupDownloadCache()
                }
                val result = DownloadModule.download(
                    inputText = shareText,
                    request = request,
                    progressListener = createDownloadProgressListener(),
                )

                if (result.files.isNotEmpty()) {
                    host.bubble.showFinalizing(
                        primaryText = host.getString(R.string.main_progress_finalizing),
                        detailText = host.getString(R.string.main_progress_finalizing_detail),
                    )
                }
                val registeredUris = withContext(Dispatchers.IO) {
                    result.files.map(::File).mapNotNull { file ->
                        StoreModule.registerMediaFile(file)?.also {
                            StoreModule.deleteTemporaryDownloadFile(file)
                        }
                    }
                }

                val registrationFailed = result.files.size - registeredUris.size
                if ((result.ok || result.skipped > 0) && registrationFailed == 0) {
                    withContext(Dispatchers.IO) {
                        StoreModule.cleanupDownloadSidecars()
                    }
                    host.bubble.showSuccess(
                        primaryText = host.getString(R.string.main_progress_success),
                        detailText = host.getString(R.string.main_progress_success_detail),
                    )
                    UiMotion.performHaptic(host.bubble.view, UiMotion.Haptic.CONFIRM)
                    saveDownloadHistory(
                        sourceUrl = historySourceUrl,
                        title = historyTitle,
                        mediaType = historyMediaType,
                        status = DownloadHistoryRecord.STATUS_SUCCESS,
                        savedUris = registeredUris,
                        errorMessage = null,
                        createdAt = taskStartedAt,
                    )
                    toast(host.getString(R.string.main_toast_download_done))
                } else {
                    progressHideDelayMs = ProgressBubblePolicy.resultHideDelay(
                        ProgressBubbleStage.ERROR
                    )
                    val errorMessage = if (registrationFailed > 0) {
                        "已保存 ${registeredUris.size} 个文件，$registrationFailed 个文件写入相册失败"
                    } else result.error ?: result.message
                    recordFailure(
                        inputText = shareText,
                        operation = "下载",
                        stage = result.diagnostics?.stages?.lastOrNull()?.name ?: "下载保存",
                        error = errorMessage,
                        responseSummary = result.diagnostics?.responseSummary
                            ?.takeIf { it.isNotBlank() }
                            ?: "ok=${result.ok}; success=${result.success}; failed=${result.failed}",
                        retryInfo = result.diagnostics?.retryInfo() ?: "可重试下载",
                        channelOverride = result.diagnostics?.channel,
                        diagnostics = result.diagnostics,
                    )
                    saveDownloadHistory(
                        sourceUrl = historySourceUrl,
                        title = historyTitle,
                        mediaType = historyMediaType,
                        status = DownloadHistoryRecord.STATUS_FAILED,
                        savedUris = registeredUris,
                        errorMessage = errorMessage,
                        createdAt = taskStartedAt,
                    )
                    host.showDownloadFailure(errorMessage)
                }
            } catch (e: Exception) {
                progressHideDelayMs = ProgressBubblePolicy.resultHideDelay(
                    ProgressBubbleStage.ERROR
                )
                val errorMessage = host.getString(
                    R.string.main_error_exception,
                    e.message ?: host.getString(R.string.main_error_unknown)
                )
                recordFailure(
                    inputText = shareText,
                    operation = "下载",
                    stage = "下载调用",
                    error = errorMessage,
                    responseSummary = "客户端异常：${e::class.java.simpleName}",
                    retryInfo = "可重试下载",
                )
                saveDownloadHistory(
                    sourceUrl = historySourceUrl,
                    title = historyTitle,
                    mediaType = historyMediaType,
                    status = DownloadHistoryRecord.STATUS_FAILED,
                    savedUris = emptyList(),
                    errorMessage = errorMessage,
                    createdAt = taskStartedAt,
                )
                host.showDownloadFailure(errorMessage)
            } finally {
                downloading = false
                host.setUiEnabled(true)
                refreshHistoryUi()
                host.bubble.scheduleHide(progressHideDelayMs)
            }
        }
    }

    fun saveCurrentPreview() {
        if (downloading) return
        val input = host.previewSection.input.orEmpty()
        val preview = host.previewSection.preview
        if (input.isBlank() || preview == null) return
        val request = host.previewSection.buildDownloadRequest()
        if (request == null) {
            toast(host.getString(R.string.main_selection_invalid))
            return
        }
        host.hideSheets()
        val downloadInput = if (preview.collection != null) preview.sourceUrl ?: input else input
        startDownload(downloadInput, preview, request)
    }

    fun retryLatestHistory() {
        val sourceUrl = DownloadHistoryStore.latest()?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: return
        host.setInputText(sourceUrl)
        host.previewSection.clear()
        resolveAndRenderPreview(sourceUrl)
    }

    fun refreshHistoryUi() {
        val latest = DownloadHistoryStore.latest()
        ui.historySection.visibility = if (latest == null) View.GONE else View.VISIBLE
        if (latest == null) return

        ui.tvHistoryInfo.text = if (latest.isSuccess) {
            host.getString(
                R.string.main_history_success_format,
                latest.title,
                latest.sourceUrl,
                latest.savedUris.size,
            )
        } else {
            host.getString(
                R.string.main_history_failed_format,
                latest.title,
                latest.sourceUrl,
                latest.errorMessage ?: host.getString(R.string.main_error_unknown),
            )
        }
        val hasFiles = latest.savedUris.isNotEmpty()
        ui.btnHistoryOpen.isEnabled = hasFiles
        ui.btnHistoryShare.isEnabled = hasFiles
        ui.btnHistoryRetry.isEnabled = latest.sourceUrl.isNotBlank() && !downloading
    }

    fun openLatestHistoryFile() {
        val uri = DownloadHistoryStore.latest()?.savedUris?.firstOrNull()
        if (uri == null) {
            toast(host.getString(R.string.main_toast_no_file_to_open))
            return
        }
        val mimeType = host.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            host.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(host.getString(R.string.main_toast_no_app_for_file))
        }
    }

    fun shareLatestHistoryFile() {
        val uris = DownloadHistoryStore.latest()?.savedUris.orEmpty()
        if (uris.isEmpty()) {
            toast(host.getString(R.string.main_toast_no_file_to_open))
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = host.contentResolver.getType(uris.first()) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            host.startActivity(Intent.createChooser(intent, host.getString(R.string.main_history_share)))
        } catch (_: ActivityNotFoundException) {
            toast(host.getString(R.string.main_toast_no_app_for_file))
        }
    }

    private fun createDownloadProgressListener(): DownloadProgressListener =
        object : DownloadProgressListener {
            override fun onProgress(
                percent: Int,
                downloadedBytes: Long,
                totalBytes: Long,
                speedBytesPerSecond: Long,
            ) {
                if (!host.bubble.shouldDispatchProgressUpdate(downloadedBytes, totalBytes)) return

                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.bubble.onProgress(
                        percent.coerceIn(PROGRESS_INIT, PROGRESS_COMPLETE),
                        downloadedBytes,
                        totalBytes,
                        speedBytesPerSecond,
                    )
                }
            }
        }

    private fun saveDownloadHistory(
        sourceUrl: String,
        title: String,
        mediaType: String,
        status: String,
        savedUris: List<Uri>,
        errorMessage: String?,
        createdAt: Long,
    ) {
        DownloadHistoryStore.add(
            DownloadHistoryRecord(
                downloadId = createdAt.toString(),
                sourceUrl = sourceUrl,
                title = title,
                mediaType = mediaType,
                status = status,
                savedPath = savedUris.firstOrNull()?.toString().orEmpty(),
                savedUris = savedUris,
                errorMessage = errorMessage,
                createdAt = createdAt,
                finishedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun recordFailure(
        inputText: String,
        stage: String,
        error: String?,
        responseSummary: String,
        retryInfo: String,
        channelOverride: String? = null,
        stageOverride: String? = null,
        statusOverride: String = "失败",
        operation: String = "解析",
        diagnostics: PyDiagnosticsResponse? = null,
    ) {
        val now = System.currentTimeMillis()
        val source = PlatformResolver.resolve(inputText)?.url ?: inputText
        ExceptionLogStore.add(
            ExceptionLogRecord(
                id = now.toString(),
                createdAt = now,
                platform = host.currentPlatformTitle,
                operation = operation,
                channel = ExceptionLogRecord.displayChannel(channelOverride)
                    ?: if (StoreModule.loggedIn()) "匿名解析 / Cookie 兜底可用" else "匿名解析",
                stage = stageOverride?.takeIf { it.isNotBlank() } ?: stage,
                sourceUrl = ExceptionLogRecord.redactUrl(source),
                status = statusOverride,
                responseSummary = responseSummary.take(500),
                parseException = (error ?: host.getString(R.string.main_error_unknown)).take(500),
                retryInfo = retryInfo,
                attempts = diagnostics?.stages.orEmpty().joinToString("；") { stage ->
                    buildString {
                        append(stage.name)
                        append(":")
                        append(stage.status)
                        if (stage.errorType.orEmpty().isNotBlank()) append("/${stage.errorType}")
                        if (stage.error.orEmpty().isNotBlank()) append("(${stage.error})")
                    }
                },
                timings = diagnostics?.stages.orEmpty().joinToString(", ") { stage ->
                    "${stage.name}=${stage.durationMs}ms"
                },
            )
        )
    }

    private fun buildResolveResponseSummary(preview: PyResolveResult): String {
        val timings = preview.timings.entries.joinToString(", ") { "${it.key}=${it.value}ms" }
        return "ok=${preview.ok}; message=${preview.message}; resources=${preview.resources.size}; timings=$timings"
    }

    private companion object {
        const val PROGRESS_INIT = 0
        const val PROGRESS_COMPLETE = 100
    }
}

/** diagnostics 的人类可读重试摘要，与 Python 端 diagnostics 协议字段对应。 */
private fun PyDiagnosticsResponse.retryInfo(): String =
    "重试 $retryCount 次；Cookie 兜底：${if (fallbackUsed) "已使用" else "未使用"}"
