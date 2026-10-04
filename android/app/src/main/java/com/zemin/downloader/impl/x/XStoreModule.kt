package com.zemin.downloader.impl.x

import com.zemin.downloader.common.base.BaseStoreModule
import com.zemin.downloader.impl.DownloadType

/**
 * X 平台存储：与通用基类一致（缓存目录 + MediaStore 注册）。
 */
class XStoreModule : BaseStoreModule(DownloadType.TWITTER)
