package com.zemin.downloader.impl.dy

import com.zemin.downloader.common.base.BaseBridgeAbility
import com.zemin.downloader.impl.DownloadType

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
class DyBridgeAbility : BaseBridgeAbility() {
    override val TAG = "DyBridgeAbility"

    override val downloadType: DownloadType = DownloadType.DOU_YIN

    override val loginModule = DyLoginModule()

    override val storeModule = DyStoreModule()

    override val downloadModule = DyDownloadModule()
}