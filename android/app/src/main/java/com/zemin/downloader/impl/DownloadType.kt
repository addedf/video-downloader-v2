package com.zemin.downloader.impl

import com.zemin.downloader.R
import com.zemin.downloader.appContext

/**
 * @author maozemin@coocaa.com
 * @desc:
 */

const val TYPE_DOU_YIN = "Douyin"
const val TYPE_XHS = "Xhs"

enum class DownloadType(val type: String) {
    DOU_YIN(TYPE_DOU_YIN), XIAO_HONG_SHU(TYPE_XHS);

    val title: String
        get() = when (this) {
            DOU_YIN -> appContext.getString(R.string.name_dou_yin)
            XIAO_HONG_SHU -> appContext.getString(R.string.name_xhs)
        }

    companion object {
        fun fromType(type: String?): DownloadType {
            return entries.find { it.type == type } ?: BridgeAbilityConfig.getDefaultDownloadType()
        }
    }
}

