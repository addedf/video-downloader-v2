package com.zemin.downloader.impl.x

import com.zemin.downloader.common.IStoreModule
import com.zemin.downloader.common.base.BaseBridgeAbility
import com.zemin.downloader.impl.DownloadType

/**
 * X (Twitter) 平台桥接能力。
 */
class XBridgeAbility : BaseBridgeAbility() {
    override val TAG = "XBridgeAbility"

    override val downloadType: DownloadType = DownloadType.TWITTER

    override val loginModule = XLoginModule()

    override val storeModule: IStoreModule = XStoreModule()

    override val downloadModule = XDownloadModule()
}
