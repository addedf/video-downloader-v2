package com.zemin.downloader.common.util

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zemin.downloader.appContext
import com.zemin.downloader.common.core.currentType
import java.io.File

object MediaStorageManager {
    const val APP_FILE_DIR = "python-runtime"
    const val CACHE_DOWNLOAD_DIR = "python-downloads"
    const val MEDIA_PICTURE_DOWNLOAD_DIR = "Pictures"
    const val MEDIA_VIDEO_DOWNLOAD_DIR = "Movies"
    const val MEDIA_AUDIO_DOWNLOAD_DIR = "Music"
    const val TEMP_EXTENSION = "tmp"
    const val EXTENSION_MP4 = "mp4"
    const val EXTENSION_MOV = "mov"
    const val EXTENSION_M4A = "m4a"
    const val EXTENSION_MP3 = "mp3"
    const val EXTENSION_JPG = "jpg"
    const val EXTENSION_JPEG = "jpeg"
    const val EXTENSION_PNG = "png"
    const val EXTENSION_WEBP = "webp"
    const val EXTENSION_GIF = "gif"
    const val MIME_VIDEO_PREFIX = "video/"
    const val MIME_IMAGE_PREFIX = "image/"
    const val MIME_AUDIO_PREFIX = "audio/"
    const val MIME_VIDEO_MP4 = "video/mp4"
    const val MIME_VIDEO_QUICKTIME = "video/quicktime"
    const val MIME_AUDIO_MP4 = "audio/mp4"
    const val MIME_AUDIO_MPEG = "audio/mpeg"
    const val MIME_IMAGE_JPEG = "image/jpeg"
    const val MIME_IMAGE_PNG = "image/png"
    const val MIME_IMAGE_WEBP = "image/webp"
    const val MIME_IMAGE_GIF = "image/gif"
    const val PATH_SEPARATOR = "/"
    const val DUPLICATE_FILE_NAME_SEPARATOR = "_"
    const val FILE_EXTENSION_SEPARATOR = "."
    const val EMPTY_EXTENSION = ""

    private val MEDIA_EXTENSIONS = setOf(
        EXTENSION_MP4,
        EXTENSION_MOV,
        EXTENSION_M4A,
        EXTENSION_MP3,
        EXTENSION_JPG,
        EXTENSION_JPEG,
        EXTENSION_PNG,
        EXTENSION_WEBP,
        EXTENSION_GIF,
    )

    fun getAppFileDir(): File {
        return File(appContext.filesDir, APP_FILE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    fun getPythonDownloadDir(): File {
        return File(downloadCacheRoot(), CACHE_DOWNLOAD_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun downloadCacheRoot(): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.cacheDir
        } else {
            appContext.externalCacheDir ?: appContext.cacheDir
        }
    }

    fun cleanupPythonDownloadCache() {
        getPythonDownloadDir().deleteRecursively()
        getPythonDownloadDir().mkdirs()
    }

    fun cleanupPythonDownloadSidecars() {
        val cacheRoot = getPythonDownloadDir()
        cacheRoot.walkBottomUp().forEach { file ->
            if (file == cacheRoot) return@forEach
            if (file.isDirectory) {
                if (file.list()?.isEmpty() == true) file.delete()
                return@forEach
            }
            val extension = file.extension.lowercase()
            val isMedia = extension in MEDIA_EXTENSIONS
            if (!isMedia || extension == TEMP_EXTENSION) {
                file.delete()
            }
        }
    }

    fun deleteTemporaryDownloadFile(file: File): Boolean {
        val cacheRoot = getPythonDownloadDir().canonicalFile
        val target = file.canonicalFile
        if (!target.path.startsWith(cacheRoot.path + File.separator)) return false
        val deleted = target.delete()
        pruneEmptyParents(target.parentFile, cacheRoot)
        return deleted
    }

    private fun pruneEmptyParents(start: File?, stopAt: File) {
        var current = start
        while (current != null && current != stopAt) {
            val children = current.list()
            if (children == null || children.isNotEmpty()) return
            if (!current.delete()) return
            current = current.parentFile
        }
    }

    fun registerMediaFile(file: File): Uri? {
        val extension = file.extension.lowercase()
        val mimeType = mimeTypeForExtension(extension) ?: return null
        return when {
            isVideoMimeType(mimeType) -> registerVideoToMediaStore(file, mimeType)
            isImageMimeType(mimeType) -> registerImageToMediaStore(file, mimeType)
            isAudioMimeType(mimeType) -> registerAudioToMediaStore(file, mimeType)
            else -> null
        }
    }

    private fun mimeTypeForExtension(extension: String): String? {
        return when (extension) {
            EXTENSION_MP4 -> MIME_VIDEO_MP4
            EXTENSION_MOV -> MIME_VIDEO_QUICKTIME
            EXTENSION_M4A -> MIME_AUDIO_MP4
            EXTENSION_MP3 -> MIME_AUDIO_MPEG
            EXTENSION_JPG, EXTENSION_JPEG -> MIME_IMAGE_JPEG
            EXTENSION_PNG -> MIME_IMAGE_PNG
            EXTENSION_WEBP -> MIME_IMAGE_WEBP
            EXTENSION_GIF -> MIME_IMAGE_GIF
            else -> null
        }
    }

    private fun isVideoMimeType(mimeType: String): Boolean {
        return mimeType.startsWith(MIME_VIDEO_PREFIX)
    }

    private fun isImageMimeType(mimeType: String): Boolean {
        return mimeType.startsWith(MIME_IMAGE_PREFIX)
    }

    private fun isAudioMimeType(mimeType: String): Boolean {
        return mimeType.startsWith(MIME_AUDIO_PREFIX)
    }

    private fun registerVideoToMediaStore(file: File, mimeType: String = MIME_VIDEO_MP4): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, getVideoMediaStoreRelativePath())
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = appContext.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } else {
            copyToPublicMediaDir(file, getLegacyVideoDownloadDir())?.let(::scanLegacyMediaFile)
        }
    }

    private fun registerImageToMediaStore(file: File, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, getImageMediaStoreRelativePath())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = appContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } else {
            copyToPublicMediaDir(file, getLegacyPictureDownloadDir())?.let(::scanLegacyMediaFile)
        }
    }

    private fun registerAudioToMediaStore(file: File, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, getAudioMediaStoreRelativePath())
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } else {
            copyToPublicMediaDir(file, getLegacyAudioDownloadDir())?.let(::scanLegacyMediaFile)
        }
    }

    private fun copyToPublicMediaDir(source: File, targetDir: File): File? {
        if (!source.exists()) return null
        targetDir.mkdirs()
        val target = uniqueTargetFile(targetDir, source.name)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun uniqueTargetFile(targetDir: File, fileName: String): File {
        val original = File(targetDir, fileName)
        if (!original.exists()) return original

        val baseName = fileName.substringBeforeLast(FILE_EXTENSION_SEPARATOR, fileName)
        val extension = fileName.substringAfterLast(FILE_EXTENSION_SEPARATOR, EMPTY_EXTENSION)
        var index = 1
        while (true) {
            val candidateName = if (extension.isBlank()) {
                "$baseName$DUPLICATE_FILE_NAME_SEPARATOR$index"
            } else {
                "$baseName$DUPLICATE_FILE_NAME_SEPARATOR$index$FILE_EXTENSION_SEPARATOR$extension"
            }
            val candidate = File(targetDir, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun scanLegacyMediaFile(file: File): Uri {
        val uri = Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
            data = uri
        }
        appContext.sendBroadcast(intent)
        return uri
    }

    fun getPublicMediaRelativePathForCachePath(cachePath: String): String? {
        val mimeType = mimeTypeForCachePath(cachePath) ?: return null
        return when {
            isImageMimeType(mimeType) -> getImageMediaStoreRelativePath()
            isVideoMimeType(mimeType) -> getVideoMediaStoreRelativePath()
            isAudioMimeType(mimeType) -> getAudioMediaStoreRelativePath()
            else -> null
        }
    }

    private fun mimeTypeForCachePath(cachePath: String): String? {
        return mimeTypeForExtension(File(cachePath).extension.lowercase())
    }

    fun getVideoMediaStoreRelativePath(): String {
        return buildMediaStoreRelativePath(MEDIA_VIDEO_DOWNLOAD_DIR)
    }

    fun getImageMediaStoreRelativePath(): String {
        return buildMediaStoreRelativePath(MEDIA_PICTURE_DOWNLOAD_DIR)
    }

    fun getAudioMediaStoreRelativePath(): String {
        return buildMediaStoreRelativePath(MEDIA_AUDIO_DOWNLOAD_DIR)
    }

    private fun buildMediaStoreRelativePath(rootDir: String): String {
        return rootDir + PATH_SEPARATOR + currentType
    }

    private fun getLegacyVideoDownloadDir(): File {
        return getLegacyPublicMediaDir(Environment.DIRECTORY_MOVIES)
    }

    private fun getLegacyPictureDownloadDir(): File {
        return getLegacyPublicMediaDir(Environment.DIRECTORY_PICTURES)
    }

    private fun getLegacyAudioDownloadDir(): File {
        return getLegacyPublicMediaDir(Environment.DIRECTORY_MUSIC)
    }

    private fun getLegacyPublicMediaDir(environmentDir: String): File {
        return File(
            Environment.getExternalStoragePublicDirectory(environmentDir), currentType
        )
    }
}
