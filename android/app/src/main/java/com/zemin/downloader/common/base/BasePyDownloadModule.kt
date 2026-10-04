package com.zemin.downloader.common.base

import androidx.annotation.WorkerThread
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.zemin.downloader.appContext
import com.zemin.downloader.common.DownloadProgressListener
import com.zemin.downloader.common.IDownloadModule
import com.zemin.downloader.common.PyBridgeConfig
import com.zemin.downloader.common.PyDownloadResult
import com.zemin.downloader.common.bean.DownloadRequest
import com.zemin.downloader.common.core.BridgeJson
import com.zemin.downloader.common.core.DownloadResultParser
import com.zemin.downloader.common.core.ResolveResultParser
import com.zemin.downloader.common.core.StoreModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val KEY_WARM_UP = "warm_up"
const val KEY_REFRESH_COOKIES = "refresh_cookies"
const val KEY_RESOLVE = "resolve"
const val KEY_DOWNLOAD = "download"

abstract class BasePyDownloadModule : IDownloadModule {
    private val downloadRequestAdapter by lazy { BridgeJson.moshi.adapter(DownloadRequest::class.java) }
    override val python: Python
        get() {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            return Python.getInstance()
        }

    override val pyBridgeConfig: PyBridgeConfig
        get() = PyBridgeConfig(cookieString = StoreModule.getCookieString())

    /**
     * 处理下载结果
     */
    @WorkerThread
    open suspend fun handleDownloadResult(result: String): PyDownloadResult {
        return DownloadResultParser.parse(result)
    }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        python.getModule(pyModuleName).callAttr(
            KEY_WARM_UP,
            pyBridgeConfig.appDir,
            pyBridgeConfig.outDownloadDir,
            pyBridgeConfig.cookieString
        )
    }

    override suspend fun refreshCookies(cookieString: String) = withContext(Dispatchers.IO) {
        python.getModule(pyModuleName).callAttr(KEY_REFRESH_COOKIES, cookieString)
    }

    override suspend fun resolve(inputText: String, cursor: String?) = withContext(Dispatchers.IO) {
        val module = python.getModule(pyModuleName)
        val response = if (cursor == null) module.callAttr(KEY_RESOLVE, inputText)
            else module.callAttr(KEY_RESOLVE, inputText, cursor)
        ResolveResultParser.parse(response.toString())
    }

    override suspend fun download(
        inputText: String,
        request: DownloadRequest?,
        progressListener: DownloadProgressListener?,
    ): PyDownloadResult = withContext(Dispatchers.IO) {
        val module = python.getModule(pyModuleName)
        val response = if (request == null) {
            module.callAttr(KEY_DOWNLOAD, inputText, progressListener)
        } else {
            module.callAttr(
                KEY_DOWNLOAD,
                inputText,
                downloadRequestAdapter.toJson(request),
                progressListener,
            )
        }
        response
            .toString().let {
                handleDownloadResult(it)
            }
    }
}
