package com.zemin.downloader.impl.dy

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.zemin.downloader.appScope
import com.zemin.downloader.common.ILoginModule
import com.zemin.downloader.common.core.DownloadModule
import com.zemin.downloader.common.core.currentType
import com.zemin.downloader.common.util.LocalStorage
import kotlinx.coroutines.launch

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
class DyLoginModule : ILoginModule {
    private var loadedIesDouyin = false

    override val needLogin: Boolean = true
    override val loginUrl: String = LOGIN_URL
    override val userAgent: String = DEFAULT_USER_AGENT

    override fun isLoggedIn(cookieString: String): Boolean {
        return cookieString.contains("sessionid") || cookieString.contains("sso_uid_tt")
    }

    override fun shouldOverrideUrlLoading(
        url: String, view: WebView?, request: WebResourceRequest?
    ): Boolean {
        return url.startsWith("snssdk")
    }

    override fun checkLoginStatus(view: WebView?): Boolean {
        val cookieString = collectCookies()
        val hasLogin = cookieString.isNotBlank() && isLoggedIn(cookieString)
        if (hasLogin) {
            saveCookies(cookieString)
            if (!loadedIesDouyin) {
                loadedIesDouyin = true
                view?.loadUrl(IES_DOUYIN_URL)
            }
        }
        return hasLogin
    }

    override fun saveCookiesFromWebView() {
        val cookieString = collectCookies()
        if (cookieString.isNotBlank()) {
            saveCookies(cookieString)
        }
    }

    private fun collectCookies(): String {
        return COOKIE_URLS.mapNotNull { CookieManager.getInstance().getCookie(it) }
            .filter { it.isNotBlank() }.distinct().joinToString("; ")
    }

    private fun saveCookies(cookieString: String) {
        LocalStorage.saveCookies(downloadType.type, cookieString)
        appScope.launch {
            DownloadModule.refreshCookies(cookieString)
        }
    }

    companion object {
        private const val LOGIN_URL =
            "https://sso.douyin.com/login/?service=https://www.douyin.com&type=phone"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " + "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"
        private const val IES_DOUYIN_URL = "https://www.iesdouyin.com/"
        private val COOKIE_URLS = listOf(
            "https://douyin.com",
            "https://www.douyin.com",
            "https://sso.douyin.com",
            "https://login.douyin.com",
            IES_DOUYIN_URL
        )
    }
}