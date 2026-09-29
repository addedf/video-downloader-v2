package com.zemin.downloader.impl

object BridgeAbilityConfig {

    fun getDefaultDownloadType() = DownloadType.DOU_YIN

    /** 参与桥接的平台全集。新增平台枚举后必须同步进这里，否则持久化的选择会在启动时被打回默认。 */
    fun getAllAbility(): List<DownloadType> = DownloadType.entries.toList()
}
