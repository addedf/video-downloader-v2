# X (Twitter) 视频/图片识别下载功能策划案

> 2026-09-28 立项策划。目标：让 APP 像识别抖音一样自动识别 X 链接，解析视频/图片并下载，体验对标 [savetwitter.net](https://savetwitter.net/zh-cn3)，解析引擎引入 [XApis](https://github.com/cv-cat/XApis)（纯 Python，契合现有 Chaquopy 架构）。

## 1. 目标与定位

- 分享/复制/粘贴 X 链接 → 自动识别 → 解析出视频（固定最高画质）、图片、GIF → 下载 → 落盘系统相册。
- **不提供画质选择**，视频一律取最高码率 mp4，交互更简单。
- 对齐 savetwitter 的核心能力：图片原图下载、GIF 下载、MP3 提取；叠加原生 APP 优势：系统分享直达、剪贴板自动识别、MediaStore 相册可见、下载历史。
- 不承诺 savetwitter 宣传的"4K/8K"——X 源视频上限通常 1080p（部分可达 2K），能下到的最高画质取决于源。

## 2. 架构契合度（为什么 XApis 能直接进 APP）

现有数据流：**链接 → PlatformResolver（host 白名单）→ BridgeAbilityManager（按 DownloadType 选桥）→ BasePyDownloadModule.callAttr() → Python 模块（aiohttp 解析+下载）→ JSON 回 Kotlin → MediaStorageManager 注册 MediaStore**。

- 所有平台逻辑全在 Python 侧（`python/dy/`、`python/xhs/`），Kotlin 只做桥接+UI。
- XApis 是纯 Python 库（登录、XCTID/Castle 纯算、读接口、媒体接口），无浏览器依赖 → 可 vendor 进 `app/src/main/python/` 由 Chaquopy 打包。
- 小红书模块（`xhs/cli/xhs_android_entry.py`）是比抖音更轻的参照模板；X 若以免登录解析起步，可先不做 LoginModule。

## 3. 技术方案

### 3.1 解析链路：三级降级策略

| 优先级 | 方式 | 覆盖场景 | 代价/风险 |
|---|---|---|---|
| ① 免登录 | XApis 读接口（GraphQL TweetResultByRestId/TweetDetail，公开 Bearer + guest token，XCTID 头用其纯算） | 公开推文 | guest 限流；X 风控随时间加码 |
| ② Cookie 会话 | 用户粘贴 `auth_token`/`ct0`，XApis 以已有 Cookie 构建会话 | ① 限流失败、登录可见、敏感内容 | 依赖用户提供 Cookie |
| ③ 兜底 | fxtwitter 公共 API（`api.fxtwitter.com/status/:id`，返回含全部媒体 variants 的 JSON） | ①② 都失败时的应急 | 依赖第三方服务，不作承诺 |

- **链接归一化**（Python 侧 `link_normalizer.py`）：支持 `x.com`、`twitter.com`、`mobile.twitter.com`、`t.co` 短链（跟随重定向取真实 status URL）、`vxtwitter.com`/`fixupx.com` 分享链接、`/status/` 及 `/statuses/` 路径、末尾参数清理，抽取出 tweet id。
- 账密登录（Castle/XCTID 纯算）放阶段 3 作高级选项，需核实 `CASTLE_PURE_RL_FILE` profile 的获取成本。

### 3.2 媒体提取规则（`media_extractor.py`）

- **视频**：`extended_entities.media[].video_info.variants` → 过滤 `video/mp4`（弃 m3u8）→ 按 bitrate 降序取最高，作为唯一下载项，无画质选择。
- **GIF**：同 video_info 的 mp4 variants，预览标注「GIF（MP4）」。
- **图片**：`media_url_https` + `?format=jpg&name=orig`（或 `name=4096x4096`）取原图；一条推文最多 4 图，全部入 IMAGE tab。
- **MP3**（阶段 2）：不引入 ffmpeg，用 Kotlin `MediaExtractor + MediaMuxer` 从 mp4 抽音轨免转码封装。
- 视频与图片可同推文共存（X 支持视频+图？实际同一推文 media 数组同类，但解析按实体逐个处理即可）。

### 3.3 代码改动清单

**Kotlin 侧（仿 `impl/xhs/` 五件套）**

| 文件 | 改动 |
|---|---|
| `impl/DownloadType.kt` | 加 `TWITTER` 枚举 |
| `impl/x/`（新建） | `XBridgeAbility` / `XDownloadModule`（pyModuleName=`x.cli.x_android_entry`）/ `XLoginModule`（阶段2）/ `XStoreModule`（cookie 存取） |
| `common/core/BridgeAbilityManager.kt` L62-64 | `when` 加分支 |
| `ui/util/PlatformResolver.kt` | host 白名单加 `x.com` `www.x.com` `twitter.com` `mobile.twitter.com` `t.co` `www.t.co` `vxtwitter.com` `fixupx.com`；正则抽 URL 适配 |
| `AndroidManifest.xml` L25-50 | VIEW intent-filter 加上述 hosts |
| `strings.xml` | `main_toast_only_douyin_supported` 等文案去抖音硬编码、通用化 |
| `common/util/DownloadHistoryStore.kt` | key/结构加 platform 字段（兼容旧数据） |
| 预览卡 `ui/preview/` | 资源 tab 复用 VIDEO/IMAGE，无画质选择器（几乎零改动） |

**Python 侧**

| 内容 | 说明 |
|---|---|
| `python/x/` 新包 | `x_android_entry.py`（`resolve`/`download`/`warm_up` 签名对齐 `dy_android_entry`）、`link_normalizer.py`、`media_extractor.py`、`x_client.py`（封装 XApis 调用与降级链） |
| XApis 引入 | **vendor 固定 commit 拷贝** `x_apis/` `builder/` `utils/` `static/` 至 `python/x_apis_vendor/`（不用 submodule/git pip，保打包确定性），记录 commit hash |
| 依赖适配 | 核对其 requirements 与 Chaquopy 兼容性；**重点排查 curl_cffi**（Android 不可用则写 aiohttp/httpx 适配层替换其传输层）；`common/android_progress_reporter.py` 复用现有进度回调 |
| 会话持久化 | cookie 存 Kotlin `XStoreModule`（对齐 `PyBridgeConfig` 注入模式），Python 侧接到 XApis 的 Cookie 会话构建入口；XApis 自身 cookie 持久化文件放 `filesDir/python-runtime` |

### 3.4 交互流程（对标 savetwitter）

1. X APP/浏览器点分享 → 选本 APP → 直接解析（SEND intent）。
2. 复制链接 → 切回 APP → 剪贴板弹窗确认（现抖音同款体验）。
3. 解析中状态文案「正在检索数据，请稍候…」。
4. 预览卡：作者/文案摘要 + 封面 + 资源 tab（视频/图片），视频固定最高画质直接下载。
5. 下载 → 悬浮进度泡（现有 `DownloadProgressBubbleView`）→ 相册可见。

## 4. 分期计划

| 阶段 | 内容 | 工作量估计 | 退出标准 |
|---|---|---|---|
| **0 技术验证** | a) 桌面 Python3.12 用 XApis 解析单条公开推文拿到 mp4 直链；b) requirements 在 Chaquopy/Android 可用性核对（curl_cffi?）；c) 免登录 vs 必须 cookie、`CASTLE_PURE_RL_FILE` 是否必须；d) t.co 重定向实测；e) 选定 vendor commit | 0.5–1 天 | 结论写入本文档附录 |
| **1 MVP** | 三入口识别（分享/剪贴板/VIEW）+ 免登录解析公开视频推文 + 最高画质下载 + 相册落盘 + 历史记录 | 2–3 天 | 端到端可用，抖音/小红书回归无影响 |
| **2 体验对齐** | 多图下载、GIF、MP3 提取、Cookie 粘贴登录（解锁限流与敏感内容） | 2–3 天 | 对齐 savetwitter 核心能力 |
| **3 差异化** | XApis 账密登录（Castle 纯算）、**用户主页媒体批量下载**（用户主页读取接口，savetwitter 没有的）、搜索解析 | 3–5 天 | 批量下载可用 |
| **4 打磨** | 限流/风控错误文案、GraphQL hash 变更的跟进机制、存量数据迁移 | 1–2 天 | 长期可维护 |

## 5. 风险与对策

| # | 风险 | 对策 |
|---|---|---|
| R1 | XApis 依赖含 curl_cffi 等原生库，Chaquopy/Android 编译不过 | 阶段 0 先行验证；不兼容则只 vendor 纯算/接口层，传输层换 aiohttp 适配 |
| R2 | X GraphQL 接口、`x-client-transaction-id` 等风控参数随时间失效 | XApis 上游活跃跟进；vendor 锁 commit + 定期升级脚本/记录 |
| R3 | guest 免登录被限流/封禁 | 自动降级到 Cookie 会话；UI 明确提示原因 |
| R4 | 账密登录触发账号风控/验证 | 主推 Cookie 方式并提示用小号；账密登录做成高级选项并加风险文案 |
| R5 | 合规/ToS | 功能定位个人离线使用；设置页加使用条款提示 |
| R6 | fxtwitter 三方兜底不稳定 | 仅最后降级，失败给出明确错误而非静默 |

## 6. 验收清单

- [ ] `x.com` / `twitter.com` / `t.co` 链接：分享、剪贴板、直接点开（VIEW）三入口均能识别进入解析
- [ ] 公开视频推文：分享 → 预览（作者/封面/画质）→ 下载 → 系统相册可见、可播放
- [ ] 多图推文：IMAGE tab 全图原图下载
- [ ] GIF 推文：下载为 MP4，预览有标注
- [ ] 视频始终取 variants 中最高码率 mp4，且无画质选择入口
- [ ] Cookie 登录后：限流恢复、登录可见推文可解析
- [ ] 下载历史带平台标识；旧抖音记录不丢
- [ ] 抖音/小红书全流程回归通过

## 附录：阶段 0 验证结论

（待填：XApis 版本/commit、依赖兼容结论、免登录可行性、CASTLE profile 必要性、样例推文解析结果）
