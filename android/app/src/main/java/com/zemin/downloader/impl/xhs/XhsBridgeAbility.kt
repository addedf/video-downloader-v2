package com.zemin.downloader.impl.xhs

import com.zemin.downloader.common.IStoreModule
import com.zemin.downloader.common.base.BaseBridgeAbility
import com.zemin.downloader.impl.DownloadType

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
class XhsBridgeAbility : BaseBridgeAbility() {
    override val TAG = "XhsBridgeAbility"

    override val downloadType: DownloadType = DownloadType.XIAO_HONG_SHU

    override val loginModule = XhsLoginModule()

    override val storeModule: IStoreModule = XhsStoreModule()

    override val downloadModule = XhsDownloadModule()
}