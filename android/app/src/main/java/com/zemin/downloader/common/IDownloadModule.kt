package com.zemin.downloader.common

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.zemin.downloader.common.bean.DownloadRequest
import com.zemin.downloader.common.util.MediaStorageManager

/**
 * @author maozemin@coocaa.com
 * @desc: 通用下载桥接接口
 */
interface IDownloadModule : IBaseBusinessModule {
    val python: Python

    val pyModuleName: String

    val pyBridgeConfig: PyBridgeConfig

    suspend fun warmUp(): PyObject?

    suspend fun resolve(inputText: String): PyResolveResult

    suspend fun download(
        inputText: String,
        request: DownloadRequest? = null,
        progressListener: DownloadProgressListener? = null,
    ): PyDownloadResult

    suspend fun refreshCookies(cookieString: String): PyObject?
}

interface DownloadProgressListener {
    fun onProgress(
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
    )
}

data class PyBridgeConfig(val cookieString: String?) {
    val appDir: String get() = MediaStorageManager.getAppFileDir().absolutePath

    val outDownloadDir: String get() = MediaStorageManager.getPythonDownloadDir().absolutePath
}
