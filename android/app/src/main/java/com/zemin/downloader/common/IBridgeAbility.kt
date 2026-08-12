package com.zemin.downloader.common

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
interface IBridgeAbility : IBaseModule {
    var initialized: Boolean

    val loginModule: ILoginModule

    val storeModule: IStoreModule

    val downloadModule: IDownloadModule

    suspend fun init(): Boolean
}