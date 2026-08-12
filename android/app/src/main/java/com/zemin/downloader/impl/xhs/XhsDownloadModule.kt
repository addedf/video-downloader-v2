package com.zemin.downloader.impl.xhs

import com.zemin.downloader.common.base.BasePyDownloadModule

/**
 * 小红书python桥接器
 */
class XhsDownloadModule : BasePyDownloadModule() {
    override val pyModuleName: String = "xhs.cli.xhs_android_entry"
}
