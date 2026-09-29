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
        val mimeType = mimeTypeForExtension(file.extension.lowercase()) ?: return null
        return when {
            isVideoMimeType(mimeType) -> registerToMediaStore(
                file, mimeType,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                relativePath = ::getVideoMediaStoreRelativePath,
                legacyDir = getLegacyVideoDownloadDir(),
            )
            isImageMimeType(mimeType) -> registerToMediaStore(
                file, mimeType,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                relativePath = ::getImageMediaStoreRelativePath,
                legacyDir = getLegacyPictureDownloadDir(),
            )
            isAudioMimeType(mimeType) -> registerToMediaStore(
                file, mimeType,
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                relativePath = ::getAudioMediaStoreRelativePath,
                legacyDir = getLegacyAudioDownloadDir(),
            )
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

    /** 视频/图片/音频入库只在 MediaStore 集合与目录上不同，统一走这一个实现。 */
    private fun registerToMediaStore(
        file: File,
        mimeType: String,
        collection: Uri,
        relativePath: () -> String,
        legacyDir: File,
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = appContext.contentResolver.insert(collection, values) ?: return null

            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } else {
            copyToPublicMediaDir(file, legacyDir)?.let(::scanLegacyMediaFile)
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
