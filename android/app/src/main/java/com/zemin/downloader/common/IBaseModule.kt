package com.zemin.downloader.common

import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.impl.DownloadType

/**
 * @author maozemin@coocaa.com
 * @desc
 */
interface IBaseModule {
    val downloadType: DownloadType
}

interface IBaseBusinessModule : IBaseModule {
    override val downloadType: DownloadType
        get() = currentDownloadType
}