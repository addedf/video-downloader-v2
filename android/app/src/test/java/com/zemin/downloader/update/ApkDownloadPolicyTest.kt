package com.zemin.downloader.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ApkDownloadPolicyTest {
    @Test
    fun computesTotalFromPartialResponse() {
        assertEquals(
            1000L + 500L,
            expectedTotalBytes(
                responseCode = 206,
                resumeOffset = 1000L,
                contentLength = 500L,
            ),
        )
        assertEquals(-1L, expectedTotalBytes(206, 1000L, -1L))
    }

    @Test
    fun computesTotalFromFullResponse() {
        assertEquals(4096L, expectedTotalBytes(200, 0L, 4096L))
        assertEquals(-1L, expectedTotalBytes(200, 0L, -1L))
        assertEquals(-1L, expectedTotalBytes(416, 4096L, 4096L))
    }

    @Test
    fun retryDelaysGrowAndCap() {
        assertEquals(1_000L, AppUpdateConfig.retryDelayMillis(1))
        assertEquals(2_000L, AppUpdateConfig.retryDelayMillis(2))
        assertEquals(4_000L, AppUpdateConfig.retryDelayMillis(3))
        assertEquals(8_000L, AppUpdateConfig.retryDelayMillis(4))
        assertEquals(8_000L, AppUpdateConfig.retryDelayMillis(9))
    }

    @Test
    fun digestsFileContent() {
        val file = File.createTempFile("sha", ".bin").apply { deleteOnExit() }
        file.writeBytes("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Of(file),
        )
    }

    @Test
    fun digestContinuesAcrossPartialFile() {
        val first = "hello ".toByteArray()
        val second = "world".toByteArray()
        val partial = File.createTempFile("part", ".bin").apply { deleteOnExit() }
        partial.writeBytes(first)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateFromFile(partial)
        digest.update(second)

        val expected = MessageDigest.getInstance("SHA-256")
        expected.update(first + second)
        assertEquals(
            expected.digest().joinToString("") { "%02x".format(it) },
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }
}
