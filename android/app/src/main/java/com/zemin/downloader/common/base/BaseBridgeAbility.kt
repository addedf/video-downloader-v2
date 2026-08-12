package com.zemin.downloader.common.base

import android.util.Log
import com.zemin.downloader.common.IBridgeAbility

/**
 * @author maozemin@coocaa.com
 * @desc
 */
abstract class BaseBridgeAbility : IBridgeAbility {
    protected open val TAG = "BaseBridgeAbility"

    override var initialized: Boolean = false

    override suspend fun init(): Boolean {
        if (initialized) {
            return true
        }

        val initSuccess  = try {
            downloadModule.warmUp()
            true
        } catch (e: Exception) {
            Log.e(TAG, "init: error = ${e.message}")
            false
        }
        initialized = initSuccess
        return initSuccess
    }
}