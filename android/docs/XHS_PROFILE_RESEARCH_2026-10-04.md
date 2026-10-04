# 小红书主页免 Token 方案调研

状态：用户已决定暂停并移除小红书主页功能；以下保留为前期调研记录，文中的产品代码位置和候选路线不再代表当前实现。

2026-10-04，Asia/Shanghai。本轮按用户要求先查 GitHub，仅源码研究、匿名 HTTP 对照和本地解析器审查，没有修改产品代码、重打 APK 或使用用户浏览器 Cookie。

## 结论

`user_posted` 存在不传链接 `xsec_token` 的实现，但仍需要 Cookie 与请求签名上下文；没有验证成功“只有 user_id、无 Cookie、无签名”的完整分页下载。无需用户手工输入 token，与请求内部完全没有 token，是两种不同能力。

上一轮“匿名主页需要登录”的判断范围过宽：生产代码会删除主页分享参数，并直接切到桌面网页采集。保留用户分享参数并使用移动 UA，可以匿名读取该作者的公开分享页，但当前只取得有限的作品卡片，尚不是下载闭环。

## GitHub 源码证据

| 项目 | 核对结果 |
| --- | --- |
| [MediaCrawler `bf281780`](https://github.com/NanmiCoder/MediaCrawler/blob/bf28178082bc69989954f65a13a17bc129b9aa6e/media_platform/xhs/client.py#L583-L618) | 作者列表的 `xsec_token` 默认空，通过 `num/cursor/user_id` 等字段分页。当前签名使用 `xhshow` 算法；[主流程](https://github.com/NanmiCoder/MediaCrawler/blob/bf28178082bc69989954f65a13a17bc129b9aa6e/media_platform/xhs/core.py#L107-L124)先检查登录状态，不能据此证明免登录可用。 |
| [redbook v0.8.2 `801a098`](https://github.com/lucasygu/redbook/blob/801a09847699a2bae5560eeefd901e3afdde469b/src/lib/client.ts#L257-L285) | token/source 默认为空；大陆端优先 `v2/user_posted`，特定错误降级 v1。2026-09-12 的 [Issue #10 修复记录](https://github.com/lucasygu/redbook/issues/10)将部分失败定位到 GET 签名变化，并有作者成功复测；请求仍检查 Cookie 并生成签名，该记录不是匿名成功证据。 |
| [ReaJason/xhs `f4b62d9`](https://github.com/ReaJason/xhs/blob/f4b62d9f8e4078e631fc6e4ec8e430bc711ee9f0/xhs/core.py#L442-L476) | `get_user_notes` 不传 `xsec_token`；但请求经过[签名和 Cookie 路径](https://github.com/ReaJason/xhs/blob/f4b62d9f8e4078e631fc6e4ec8e430bc711ee9f0/xhs/core.py#L135-L187)，详情再用列表返回的作品 token。 |
| [XHS-Downloader `3261312`](https://github.com/JoeanAmier/XHS-Downloader/blob/3261312721f0b37c705ba6515885bc7f34349f2f/source/application/user_posted.py#L31-L61) | 新增 `UserPosted` 使用 `xhshow.sign_headers_get`，但 `run()` 仍为空实现，不能当成已完成的主页分页。其[用户脚本](https://github.com/JoeanAmier/XHS-Downloader/blob/3261312721f0b37c705ba6515885bc7f34349f2f/static/XHS-Downloader.js#L1129-L1191)从页面初始状态提取作品 ID 和 `xsecToken`。 |
| [rednote_downloader `106966f`](https://github.com/SeanLi-Coder/rednote_downloader/blob/106966f552e45eb3af75055810ab51f940f767be/app/xiaohongshu.py#L469-L565) | 从网页初始状态读取作品及其 token；DOM 链接只能补充已确认属于主页的作品。其完整发现仍使用 Playwright 滚动，不是匿名无签名列表 API。 |
| [xhs-importer-pro 更新记录](https://github.com/GitBinS/xhs-importer-pro/blob/9c79ddb8e1b1e1056b2ccd548df7a7754d730cf9/CHANGELOG.md) | 旧版 1.0.7 曾声明主页无需登录/token；但 1.0.12 已因频繁跳转登录、限流而移除作者主页抓取。搜索摘要中的旧结论不能直接作为当前可用性证明。 |

`xhshow` 的“算法签名”不等于零原生依赖。调研时 0.2.0 包要求 Python >= 3.10、`pycryptodome>=3.23.0`；移植到现有 Chaquopy Python 3.12 前，还需验证 Android wheel、ABI 和版本兼容性。没有把这些第三方库安装或接入生产构建。

## 用户主页匿名对照

输入为用户提供的 `https://xhslink.cn/o/1vyH6GFJvyW`，跳转到作者 `5bc340f0208fe80001ce4060`。短链目标本身带有 `xsec_token/xsec_source`。未记录这些参数的值。

每个对照请求使用新的无登录 Cookie 客户端，间隔 5 秒：

| 请求方式 | 实际返回 |
| --- | --- |
| 无 query，移动 Safari UA | 302 → `/website-login/captcha` |
| 仅保留分享 `xsec_token/xsec_source`，同一移动 UA | 200，43,922 字节，含初始状态 |
| 相同分享参数，桌面 Chrome UA | 302 → `/login` |

保留完整分享地址的移动 UA 复验同样为 200、44,703 字节。数据位于 `__INITIAL_STATE__.profile`，作者昵称为“慧喵”，`noteData` 含 5 张卡片（4 视频、1 图文）。`notePage=1`、`canShowExpand=true`。

这些卡片只有封面、标题和类型等字段，ID 为 32 位 hex；没有可直接复用的 24 位作品 ID、作品 token 或原始图片／视频下载地址，页面也没有常用详情路由链接。它证明匿名公开页可读取，不能证明取全、详情解析或媒体下载成功。以上是当前网络环境的一组实测，不是平台永久规则。

## 当前代码的具体问题和后续路线

1. `xhs_profile.target_for_url` 的主页分支重建无 query 地址，丢掉分享上下文；Kotlin `ProfileBrowserPolicy.target` 本身没有删参。应优先保留经过域名、路径和参数白名单校验的合法分享上下文。现有作者身份、续页和下载校验使用 path 中作者 ID，不依赖 query。
2. `_resolve_async` 当前主页分支直接返回 `browser_response`，并未调用已有 `profile_links`。应建立匿名页面探测，明确区分“作者资料／卡片可见”“可展开详情”“可分页取全”。
3. 不能直接启用现有 `links_from_html`：合成用例已复现 `new Set([])` 解析失败、Vue `_rawValue` 包装错误，以及包装的空 tab 被误报 `complete=true`。须先兼容结构并严格验证结束状态；移动端 `profile.noteData` 与桌面端 `user.notes` 也不是同一种结构。
4. 完整列表优先研究 MediaCrawler 的签名分页做法，先验证匿名会话是否可用，再决定何时提示登录；只有公开卡片的情况不能塞进可下载资源列表。网页采集作为补充及失败后的继续入口。

本次调研当时没有接入签名分页，也没有验证无登录全量下载；后续已按用户要求移除主页功能并重建测试包。
