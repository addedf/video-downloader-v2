package com.zemin.downloader.common.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Python 桥接协议共用 Moshi 实例。反射 Adapter 构造成本不低，
 * 协议解析器与各平台模块一律从这里取，不要自建。
 */
object BridgeJson {
    val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
}
