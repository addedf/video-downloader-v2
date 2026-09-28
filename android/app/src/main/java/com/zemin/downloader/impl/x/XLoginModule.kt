package com.zemin.downloader.impl.x

import com.zemin.downloader.common.NotLoginModule

/**
 * X 走 guest 免登录，无登录流程（阶段 3 Cookie 会话再接 LoginModule）。
 */
class XLoginModule : NotLoginModule()
