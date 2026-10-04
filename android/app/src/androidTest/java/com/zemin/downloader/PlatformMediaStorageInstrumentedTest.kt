package com.zemin.downloader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.common.IStoreModule
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.common.util.MediaStorageManager
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.impl.dy.DyStoreModule
import com.zemin.downloader.impl.x.XStoreModule
import com.zemin.downloader.impl.xhs.XhsStoreModule
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real scoped-storage round trips with synthetic local media; no downloads or existing media are used. */
@RunWith(AndroidJUnit4::class)
class PlatformMediaStorageInstrumentedTest {
    @Test
    fun allPlatformsPutImagesCoversVideosAndLiveClipsInTheirOwnAlbum() = runBlocking {
        withFixture { fixture ->
            val png = pngBytes()
            val mp4 = Base64.decode(VIDEO_BASE64, Base64.DEFAULT)
            DownloadType.entries.forEach { platform ->
                listOf("image", "cover").forEach { kind ->
                    fixture.verifyRegistration(platform, "${platform.type}-$kind.png", "image/png", png) {
                        MediaStorageManager.registerMediaFile(it, platform)
                    }
                }
                listOf("video", "image_1_live").forEach { kind ->
                    fixture.verifyRegistration(platform, "${platform.type}-$kind.mp4", "video/mp4", mp4) {
                        MediaStorageManager.registerMediaFile(it, platform)
                    }
                }
            }
        }
    }

    @Test
    fun audioUsesThePlatformsMusicDirectoryAndRetainsItsBytes() = runBlocking {
        withFixture { fixture ->
            val audio = Base64.decode(AUDIO_BASE64, Base64.DEFAULT)
            DownloadType.entries.forEach { platform ->
                fixture.verifyRegistration(platform, "${platform.type}-audio.m4a", "audio/mp4", audio) {
                    MediaStorageManager.registerMediaFile(it, platform)
                }
            }
        }
    }

    @Test
    fun sourceStoreAndExplicitPlatformKeepTheirAlbumAfterTheUiSwitchesPlatform() = runBlocking {
        BridgeAbilityManager.init()
        val previousPlatform = currentDownloadType
        try {
            withFixture { fixture ->
                val png = pngBytes()
                val mp4 = Base64.decode(VIDEO_BASE64, Base64.DEFAULT)
                DownloadType.entries.forEach { source ->
                    BridgeAbilityManager.update(source)
                    val sourceStore = storeFor(source)
                    val uiPlatform = DownloadType.entries[(source.ordinal + 1) % DownloadType.entries.size]
                    BridgeAbilityManager.update(uiPlatform)
                    assertEquals(uiPlatform, currentDownloadType)
                    assertNotEquals(source, currentDownloadType)
                    assertEquals("The existing store must keep its owning platform", source, sourceStore.downloadType)
                    fixture.verifyRegistration(source, "${source.type}-store-after-switch.png", "image/png", png) {
                        sourceStore.registerMediaFile(it)
                    }
                    fixture.verifyRegistration(source, "${source.type}-explicit-after-switch.mp4", "video/mp4", mp4) {
                        MediaStorageManager.registerMediaFile(it, source)
                    }
                }
            }
        } finally {
            BridgeAbilityManager.update(previousPlatform)
        }
    }

    private fun storeFor(platform: DownloadType): IStoreModule = when (platform) {
        DownloadType.DOU_YIN -> DyStoreModule()
        DownloadType.XIAO_HONG_SHU -> XhsStoreModule()
        DownloadType.TWITTER -> XStoreModule()
    }

    private suspend fun withFixture(block: suspend (StorageFixture) -> Unit) {
        assumeTrue("RELATIVE_PATH requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val fixture = StorageFixture()
        try {
            block(fixture)
        } finally {
            fixture.cleanup()
        }
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.MAGENTA)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class StorageFixture {
        private val resolver: ContentResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        private val uniquePrefix = "codex-media-storage-${UUID.randomUUID()}"
        private val directory = File(MediaStorageManager.getPythonDownloadDir(), uniquePrefix).apply {
            check(mkdirs()) { "Could not create the isolated media fixture directory" }
        }
        private val createdUris = mutableListOf<Uri>()
        private val attemptedRows = mutableListOf<Pair<Uri, String>>()

        fun verifyRegistration(
            platform: DownloadType,
            suffix: String,
            mime: String,
            bytes: ByteArray,
            register: (File) -> Uri?,
        ) {
            val file = File(directory, "$uniquePrefix-$suffix").apply { writeBytes(bytes) }
            val expectedPath = expectedPath(platform, mime)
            assertEquals(
                "The planned path must match the source platform",
                expectedPath.trimEnd('/'),
                MediaStorageManager.getPublicMediaRelativePathForCachePath(file.path, platform)?.trimEnd('/'),
            )
            attemptedRows.add(collectionFor(mime) to file.name)
            val uri = requireNotNull(register(file)) { "Registration must return a MediaStore URI" }
            createdUris.add(uri)
            assertEquals("content", uri.scheme)
            assertTrue("The file must be registered in the matching media table", uri.pathSegments.contains(tableFor(mime)))

            val columns = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.DISPLAY_NAME,
            )
            requireNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
                assertTrue("Registered media row must exist", cursor.moveToFirst())
                assertEquals(expectedPath, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
                assertEquals(mime, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)))
                assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)))
                assertEquals(file.name, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)))
            }
            assertTrue("Only this cache file should be removed", MediaStorageManager.deleteTemporaryDownloadFile(file))
            assertFalse(file.exists())
            val savedBytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals("Public media must retain every source byte after cache cleanup", bytes, savedBytes)
            // Production prunes empty cache parents after each file; recreate only our own directory.
            if (!directory.exists()) assertTrue(directory.mkdirs())
        }

        fun cleanup() {
            try {
                createdUris.forEach { resolver.delete(it, null, null) }
                // If registration failed after insert but before returning, clean only this run's exact UUID names.
                attemptedRows.forEach { (collection, displayName) ->
                    resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(displayName))
                }
            } finally {
                directory.deleteRecursively()
            }
        }

        private fun expectedPath(platform: DownloadType, mime: String): String {
            val folder = when (platform) {
                DownloadType.DOU_YIN -> "抖音"
                DownloadType.XIAO_HONG_SHU -> "小红书"
                DownloadType.TWITTER -> "X"
            }
            val root = if (mime.startsWith("audio/")) "Music" else "DCIM"
            return "$root/视频下载器/$folder/"
        }

        private fun tableFor(mime: String) = when {
            mime.startsWith("image/") -> "images"
            mime.startsWith("video/") -> "video"
            else -> "audio"
        }

        private fun collectionFor(mime: String): Uri = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private companion object {
        // Generated offline: 16x16 black MPEG-4 Part 2, one frame / one second; validated with ffprobe.
        const val VIDEO_BASE64 =
            "AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAAAhmcmVlAAAAGW1kYXQAAAGzABAHAAABthYVGE1t+wAAAwttb292AAAAbG12aGQA" +
            "AAAAAAAAAAAAAAAAAAPoAAAD6AABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAACAAACWnRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAD6AAAAAAAAAAAAAAAAAAAAAAA" +
            "AQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAEAAAABAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAA+gAAAAAAAEA" +
            "AAAAAdJtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAEAAAABAAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVv" +
            "SGFuZGxlcgAAAAF9bWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABPXN0" +
            "YmwAAADZc3RzZAAAAAAAAAABAAAAyW1wNHYAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAEAAQAEgAAABIAAAAAAAAAAEKTGF2YyBtcGVn" +
            "NAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY//8AAABPZXNkcwAAAAADgICAPgABAASAgIAwIBEAAAAAAw1AAAAAiAWAgIAeAAABsAEAAAG1" +
            "iRMAAAEAAAABIADEjYgADQCEAhRjBoCAgAECAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAADDUAAAACIAAAAGHN0dHMAAAAAAAAA" +
            "AQAAAAEAAEAAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAABEAAAABAAAAFHN0Y28AAAAAAAAAAQAAACwA" +
            "AAA9dWR0YQAAADVtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAAAhpbHN0"

        // Generated offline: 8 kHz mono AAC silence, 80 ms in an M4A container; validated with ffprobe.
        const val AUDIO_BASE64 =
            "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAAhmcmVlAAAAEG1kYXQBGCAHARggBwAAAtZtb292AAAAbG12aGQAAAAAAAAAAAAA" +
            "AAAAAAPoAAAAUAABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAACAAACJXRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAAUAAAAAAAAAAAAAAAAQEAAAAAAQAAAAAAAAAA" +
            "AAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAAFAAAAQAAAEAAAAAAZ1tZGlh" +
            "AAAAIG1kaGQAAAAAAAAAAAAAAAAAAB9AAAAGgFXEAAAAAAAtaGRscgAAAAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAA" +
            "AAFIbWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAEMc3RibAAAAGpzdHNkAAAA" +
            "AAAAAAEAAABabXA0YQAAAAAAAAABAAAAAAAAAAAAAQAQAAAAAB9AAAAAAAA2ZXNkcwAAAAADgICAJQABAASAgIAXQBUAAAAAALuAAAAB" +
            "MwWAgIAFFYhW5QAGgICAAQIAAAAgc3R0cwAAAAAAAAACAAAAAQAABAAAAAABAAACgAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAgAAAAEA" +
            "AAAUc3RzegAAAAAAAAAEAAAAAgAAABRzdGNvAAAAAAAAAAEAAAAsAAAAGnNncGQBAAAAcm9sbAAAAAIAAAAB//8AAAAcc2JncAAAAABy" +
            "b2xsAAAAAQAAAAIAAAABAAAAPXVkdGEAAAA1bWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAIaWxzdA=="
    }
}
