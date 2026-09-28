package com.zemin.downloader.impl.x

import com.zemin.downloader.common.base.BasePyDownloadModule

/**
 * X 平台 Python 桥接：入口为 python/x/x_android_entry.py。
 */
class XDownloadModule : BasePyDownloadModule() {
    override val pyModuleName: String = "x.x_android_entry"
}
