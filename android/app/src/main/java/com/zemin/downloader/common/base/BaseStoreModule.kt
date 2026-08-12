package com.zemin.downloader.common.base

import android.net.Uri
import com.zemin.downloader.common.IStoreModule
import com.zemin.downloader.common.util.LocalStorage
import com.zemin.downloader.common.util.MediaStorageManager
import java.io.File

/**
 * @author maozemin@coocaa.com
 * @desc
 */
abstract class BaseStoreModule : IStoreModule {
    override fun loggedIn(): Boolean {
        return hasCookie()
    }

    override fun hasCookie(): Boolean {
        return !getCookieString().isNullOrEmpty()
    }

    override fun getCookieString(): String? {
        return LocalStorage.getCookieString(downloadType.type)
    }

    override fun getAppOutDir(): String {
        return MediaStorageManager.getAppFileDir().absolutePath
    }

    override fun getDownloadDir(): String {
        return MediaStorageManager.getPythonDownloadDir().absolutePath
    }

    override fun registerMediaFile(file: File): Uri? {
        return MediaStorageManager.registerMediaFile(file)
    }

    override fun cleanupDownloadCache() {
        MediaStorageManager.cleanupPythonDownloadCache()
    }

    override fun cleanupDownloadSidecars() {
        MediaStorageManager.cleanupPythonDownloadSidecars()
    }

    override fun deleteTemporaryDownloadFile(file: File) {
        MediaStorageManager.deleteTemporaryDownloadFile(file)
    }
}