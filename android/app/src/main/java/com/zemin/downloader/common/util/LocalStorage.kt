package com.zemin.downloader.common.util

import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.core.content.edit
import com.zemin.downloader.appContext
import com.zemin.downloader.impl.DownloadType

object LocalStorage {
    const val KEY_ABILITY = "current_ability"

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(
            appContext
        )
    }

    fun saveAbility(downloadType: DownloadType) {
        prefs.edit { putString(KEY_ABILITY, downloadType.type) }
    }

    fun getAbility(): DownloadType {
        val storedType = DownloadType.fromType(prefs.getString(KEY_ABILITY, null))
        return if (storedType in com.zemin.downloader.impl.BridgeAbilityConfig.getAllAbility()) {
            storedType
        } else {
            com.zemin.downloader.impl.BridgeAbilityConfig.getDefaultDownloadType()
        }
    }

    fun saveCookies(key: String, cookieString: String) {
        CookieVault.saveEncryptedCookie(prefs, key, cookieString)
    }

    fun getCookieString(key: String): String? {
        return CookieVault.getCookie(prefs, key)
    }

    fun clearCookies(key: String) {
        CookieVault.clearCookie(prefs, key)
    }

    fun getCookiesMap(key: String): Map<String, String> {
        val cookieString = getCookieString(key) ?: return emptyMap()
        return parseCookieString(cookieString)
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun parseCookieString(cookieString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        cookieString.split(";").forEach { part ->
            val trimmed = part.trim()
            val idx = trimmed.indexOf("=")
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }
}
