package com.zemin.downloader.common

interface IBridgeAbility : IBaseModule {
    var initialized: Boolean

    val loginModule: ILoginModule

    val storeModule: IStoreModule

    val downloadModule: IDownloadModule

    suspend fun init(): Boolean
}