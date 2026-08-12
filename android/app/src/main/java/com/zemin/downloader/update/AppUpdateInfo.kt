package com.zemin.downloader.update

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class AppUpdateInfo(
    val schemaVersion: Int,
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val changelog: String,
    val publishedAt: String,
) {
    fun isNewerThan(currentVersionCode: Long): Boolean = versionCode > currentVersionCode

    fun isRequiredFor(currentVersionCode: Long): Boolean =
        currentVersionCode < minSupportedVersionCode
}
