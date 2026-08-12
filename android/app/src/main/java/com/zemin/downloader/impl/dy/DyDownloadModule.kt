package com.zemin.downloader.impl.dy

import com.zemin.downloader.common.base.BasePyDownloadModule

/**
 * 抖音python桥接器
 */
class DyDownloadModule : BasePyDownloadModule() {
    override val pyModuleName: String = "dy.cli.dy_android_entry"
}
