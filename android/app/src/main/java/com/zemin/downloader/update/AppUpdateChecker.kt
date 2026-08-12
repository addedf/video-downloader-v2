package com.zemin.downloader.update

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppUpdateChecker(private val context: Context) {
    suspend fun check(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        check(AppUpdateConfig.isAllowedManifestUrl(AppUpdateConfig.MANIFEST_URL))
        val connection = SecureUpdateHttpClient.openManifest()
        try {
            val info = UpdateManifestParser.parse(SecureUpdateHttpClient.readManifest(connection))
            info.takeIf { it.isNewerThan(currentVersionCode()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun currentVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}
