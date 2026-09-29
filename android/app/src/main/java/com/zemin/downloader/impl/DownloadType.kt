package com.zemin.downloader.impl

import com.zemin.downloader.R
import com.zemin.downloader.appContext

const val TYPE_DOU_YIN = "Douyin"
const val TYPE_XHS = "Xhs"
const val TYPE_TWITTER = "X"

enum class DownloadType(val type: String) {
    DOU_YIN(TYPE_DOU_YIN), XIAO_HONG_SHU(TYPE_XHS), TWITTER(TYPE_TWITTER);

    val title: String
        get() = when (this) {
            DOU_YIN -> appContext.getString(R.string.name_dou_yin)
            XIAO_HONG_SHU -> appContext.getString(R.string.name_xhs)
            TWITTER -> appContext.getString(R.string.name_x)
        }

    companion object {
        fun fromType(type: String?): DownloadType {
            return entries.find { it.type == type } ?: BridgeAbilityConfig.getDefaultDownloadType()
        }
    }
}

