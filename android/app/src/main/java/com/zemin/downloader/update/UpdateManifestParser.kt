package com.zemin.downloader.update

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant

object UpdateManifestParser {
    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(AppUpdateInfo::class.java)
        .failOnUnknown()

    fun parse(json: String): AppUpdateInfo {
        val info = adapter.fromJson(json)
            ?: throw JsonDataException("Update manifest is empty")
        require(info.schemaVersion == SUPPORTED_SCHEMA_VERSION) { "Unsupported schema version" }
        require(info.versionCode > 0L) { "Invalid versionCode" }
        require(info.versionName.isNotBlank() && info.versionName.length <= MAX_VERSION_NAME_LENGTH) {
            "Invalid versionName"
        }
        require(info.minSupportedVersionCode in 1L..info.versionCode) {
            "Invalid minSupportedVersionCode"
        }
        require(AppUpdateConfig.isAllowedApkUrl(info.apkUrl)) { "APK URL is not allowed" }
        require(SHA_256_REGEX.matches(info.sha256)) { "Invalid SHA-256" }
        require(info.changelog.length <= MAX_CHANGELOG_LENGTH) { "Changelog is too long" }
        runCatching { Instant.parse(info.publishedAt) }
            .getOrElse { throw IllegalArgumentException("Invalid publishedAt", it) }
        return info
    }

    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MAX_VERSION_NAME_LENGTH = 64
    private const val MAX_CHANGELOG_LENGTH = 8_000
    private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
}
