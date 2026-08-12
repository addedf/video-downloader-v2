package com.zemin.downloader.common

import android.webkit.WebResourceRequest
import android.webkit.WebView

/**
 * @author maozemin@coocaa.com
 * @desc:
 */

interface ILoginModule : IBaseBusinessModule {
    val needLogin: Boolean

    val loginUrl: String

    val userAgent: String

    fun isLoggedIn(cookieString: String): Boolean

    fun shouldOverrideUrlLoading(url: String, view: WebView?, request: WebResourceRequest?): Boolean

    fun checkLoginStatus(view: WebView? = null): Boolean

    fun saveCookiesFromWebView()
}

open class NotLoginModule : ILoginModule {
    override val needLogin: Boolean = false
    override val loginUrl: String = ""
    override val userAgent: String = ""

    override fun isLoggedIn(cookieString: String): Boolean {
        return false
    }

    override fun shouldOverrideUrlLoading(
        url: String, view: WebView?, request: WebResourceRequest?
    ): Boolean {
        return false
    }

    override fun checkLoginStatus(view: WebView?): Boolean {
        return false
    }

    override fun saveCookiesFromWebView() {
    }
}