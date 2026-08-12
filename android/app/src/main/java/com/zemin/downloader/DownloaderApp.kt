package com.zemin.downloader

import android.app.Application
import android.content.Context
import com.zemin.downloader.common.core.BridgeAbilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val appScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
val appContext: Context get() = DownloaderApp.instance.applicationContext

class DownloaderApp : Application() {

    companion object {
        lateinit var instance: DownloaderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initAbility()
    }

    private fun initAbility() {
        appScope.launch { BridgeAbilityManager.init() }
    }
}
