package com.zemin.downloader.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

class ApkVerifier(private val context: Context) {
    fun verify(apkFile: File, info: AppUpdateInfo) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw SecurityException("Cannot read APK package information")
        require(candidate.packageName == context.packageName) { "APK package name does not match" }
        require(versionCode(candidate) == info.versionCode) { "APK versionCode does not match" }
        require(info.versionCode > currentVersionCode()) { "APK is not newer than this app" }
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        require(signerDigests(candidate) == signerDigests(current)) { "APK signer does not match" }
    }

    private fun currentVersionCode(): Long = versionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = info.signingInfo?.apkContentsSigners.orEmpty()
        require(signatures.isNotEmpty()) { "APK has no signing certificate" }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
