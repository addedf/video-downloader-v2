package com.zemin.downloader.common

import android.net.Uri
import java.io.File

/**
 * @author maozemin@coocaa.com
 * @desc
 */
interface IStoreModule : IBaseBusinessModule {
    fun loggedIn(): Boolean

    fun hasCookie(): Boolean

    fun getCookieString(): String?

    fun getAppOutDir(): String

    fun getDownloadDir(): String

    fun registerMediaFile(file: File): Uri?

    fun cleanupDownloadCache()

    fun cleanupDownloadSidecars()

    fun deleteTemporaryDownloadFile(file: File)
}