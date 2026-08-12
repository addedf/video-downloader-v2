package com.zemin.downloader.common.core

import com.zemin.downloader.common.IBridgeAbility
import com.zemin.downloader.common.IDownloadModule
import com.zemin.downloader.common.ILoginModule
import com.zemin.downloader.common.IStoreModule
import com.zemin.downloader.impl.DownloadType

/**
 * Module级别
 */
val Ability: IBridgeAbility get() = BridgeAbilityManager.currentAbility
val LoginModule: ILoginModule get() = Ability.loginModule
val StoreModule: IStoreModule get() = Ability.storeModule
val DownloadModule: IDownloadModule get() = Ability.downloadModule

/**
 * Config级别
 */
val currentDownloadType: DownloadType get() = Ability.downloadType
val currentTitle: String get() = currentDownloadType.title
val currentType: String get() = currentDownloadType.type