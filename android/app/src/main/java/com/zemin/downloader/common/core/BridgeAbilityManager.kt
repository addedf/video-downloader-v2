package com.zemin.downloader.common.core

import android.util.Log
import com.zemin.downloader.common.IBridgeAbility
import com.zemin.downloader.common.util.LocalStorage
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.impl.dy.DyBridgeAbility
import com.zemin.downloader.impl.xhs.XhsBridgeAbility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object BridgeAbilityManager {
    private const val TAG = "BridgeAbilityManager"
    private val abilityCache = ConcurrentHashMap<DownloadType, IBridgeAbility>()
    private val abilityMutex = Mutex()
    internal lateinit var currentAbility: IBridgeAbility
        private set
    private val _downloadTypeFlow = MutableStateFlow<DownloadType?>(null)
    val downloadTypeFlow: StateFlow<DownloadType?> = _downloadTypeFlow.asStateFlow()

    suspend fun init() {
        setCurrentAbility(LocalStorage.getAbility(), isSaveStorage = false)
    }

    suspend fun update(downloadType: DownloadType) {
        setCurrentAbility(downloadType)
    }

    private suspend fun setCurrentAbility(
        downloadType: DownloadType, isSaveStorage: Boolean = true
    ) = abilityMutex.withLock {
        if (::currentAbility.isInitialized && currentAbility.downloadType == downloadType) {
            Log.d(TAG, "setCurrentAbility: is same downloadType = $downloadType")
            return@withLock
        }

        Log.d(TAG, "setCurrentAbility: downloadType = $downloadType")
        val ability = getOrCreateBridgeAbility(downloadType).also { currentAbility = it }
        if (isSaveStorage) {
            LocalStorage.saveAbility(downloadType)
        }
        _downloadTypeFlow.value = downloadType

        // 初始化
        if (!ability.initialized) {
            val initSuccess = ability.init()
            Log.d(TAG, "setCurrentAbility: initSuccess = $initSuccess")
        }
    }

    private fun getOrCreateBridgeAbility(downloadType: DownloadType): IBridgeAbility {
        val cacheAbility = abilityCache[downloadType]
        if (cacheAbility != null) {
            Log.d(TAG, "getBridgeAbility: from cache, type = $downloadType")
            return cacheAbility
        }

        val ability = when (downloadType) {
            DownloadType.DOU_YIN -> DyBridgeAbility()
            DownloadType.XIAO_HONG_SHU -> XhsBridgeAbility()
        }
        abilityCache[downloadType] = ability
        return ability
    }
}
