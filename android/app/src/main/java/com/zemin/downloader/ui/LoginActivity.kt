package com.zemin.downloader.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zemin.downloader.R
import com.zemin.downloader.common.base.BaseActivity
import com.zemin.downloader.common.core.LoginModule
import com.zemin.downloader.common.core.currentTitle
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.databinding.ActivityLoginBinding

class LoginActivity : BaseActivity<ActivityLoginBinding>(ActivityLoginBinding::inflate) {
    private var loginDone = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        CookieManager.getInstance().setAcceptCookie(true)

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = LoginModule.userAgent
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    checkLoginStatus()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkLoginStatus(view)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return LoginModule.shouldOverrideUrlLoading(url, view, request)
                }
            }
        }

        binding.btnLoginAction.apply {
            setOnClickListener {
                finishLogin()
            }
        }
        binding.tvLoginTitle.text = getString(R.string.login_title_douyin, currentTitle)
        binding.webView.loadUrl(LoginModule.loginUrl)
    }

    private fun checkLoginStatus(view: WebView? = null) {
        val hasLogin = LoginModule.checkLoginStatus(view)
        if (hasLogin) {
            loginDone = true
            binding.tvLoginStatus.text = getString(R.string.login_status_saved)
            binding.btnLoginAction.text = getString(R.string.login_button_done)
            CookieManager.getInstance().flush()
        }
    }

    private fun saveCookiesFromWebView() {
        LoginModule.saveCookiesFromWebView()
    }

    private fun finishLogin() {
        if (loginDone) {
            CookieManager.getInstance().flush()
            saveCookiesFromWebView()
            setResult(RESULT_OK, Intent())
            toast(getString(R.string.login_toast_success))
        }
        finish()
    }
}
