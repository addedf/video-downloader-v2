# 视频下载器 V2 代码评审报告与改进计划

- 日期：2026-09-29
- 范围：`android/app/src/main` 全部 Kotlin（68 文件 / 7632 行）与 Python（约 100 文件 / 16049 行）；对照参考区 ytdlnis、DYDownloader、astrbot_plugin_media_parser、Downloader、XApis。
- 结论先行：**桥接协议与 update 模块达到开源水准；主要病灶是 MainActivity 上帝类、约 3000 行 Python 桌面遗产死代码、双协议兼容残留，以及一处由「重复兜底」直接引入的真 bug（X 平台选择不持久化）。**
- 评分：Kotlin 6.5/10，Python 5.5/10（若剔除死代码与桌面遗产，Python 实际业务代码约 7/10）。

---

## 一、真 Bug（P0）

### 1. X 平台选择在重启后丢失
`LocalStorage.kt:22-29` 用 `BridgeAbilityConfig.getAllAbility()` 白名单校验持久化的平台，但 `BridgeAbilityConfig.kt:11-13` 的白名单只有 `[DOU_YIN, XIAO_HONG_SHU]`，没有 `TWITTER`。分享 X 链接 → `BridgeAbilityManager.update(TWITTER)` 会保存 `"X"`，下次启动 `init()` 读回时被白名单打回抖音默认值。
根因：`DownloadType.fromType()`（`DownloadType.kt:26-28`）本身已经带「未知 → 默认」兜底，`getAbility()` 再叠一层白名单校验属于重复防御，反而引入 bug。
（若「X 暂不可选」是有意的产品门控，正确做法是在 `PlatformResolver` 或 `BridgeAbilityManager.update()` 处门控，而不是在持久化读回处静默丢弃。）

### 2. 「轻量 Cookie 兜底」注释与事实不符
`dy/cli/dy_android_entry.py:382-384` 注释说 legacy signed client「只在 fallback 时才加载」，但 `dy/core/__init__.py:1-5` 在包导入时就 `import downloader_factory / mix_downloader / music_downloader`。`from core import DouyinAPIClient` 触发整个桌面下载链加载。注释描述的懒加载根本没成立。

## 二、死代码（P0）

### Kotlin
| 位置 | 说明 |
|---|---|
| `common/bean/PyDownloadResultExt.kt`（整文件 123 行） | `formatDownloadSummary/formatProcessLog/formatCounts/formatOutputDir` 全项目 0 调用 |
| `common/util/CommonUtils.kt` 的 `compactFileName/ellipsizeMiddle/formatSpeed/formatDuration` | 仅被上述死文件引用 |
| `ResolveResultParser.kt:35-57`（V1 协议分支） | 三个 Python 入口 resolve 都固定输出 `schema_version: 2`（`dy_android_entry.py:429`、`xhs_android_entry.py:260`、`x_android_entry.py:125`），V1 分支不可达 |
| `bean/PyResolveResponse.kt:8-22` 的 v1 扁平字段 | 与上同源，属双协议残留 |
| `build.gradle.kts` 的 `lifecycle.viewmodel.ktx` | 全项目无 ViewModel 使用 |

### Python（安卓入口不可达的桌面遗产，约 3000 行）
- `dy/core/downloader_factory.py` 及其下游 `user_downloader / mix_downloader / live_downloader / music_downloader / user_mode_registry / user_modes/*`（用户主页、合集、直播、音乐下载是桌面 App 功能）。
- `dy/core/retry_executor.py`、`dy/core/discovery.py`、`dy/utils/notifier.py`、`dy/storage/database.py`：无任何入口链引用。
- `dy/cli/dy_api_diagnostics.py`（694 行桌面诊断 CLI）被打进 APK。
- `xhs/source/` 整包搬入的开源项目含桌面件：`expansion/pyi_rth_beartype.py`（PyInstaller 运行时钩子）、`locale/po_to_mo.py`、`translation/` 等。
- 注意：`xhs/source` 与 `dy/utils/abogus.py / xbogus.py` 属 vendor 性质，不按业务代码标准苛评，但桌面件应剥离。

## 三、过度防御 / 重复兜底

1. `BridgeAbilityManager.kt:18-19`：`ConcurrentHashMap` 缓存 + `Mutex` 串行化双保险。互斥锁已保证单线程写缓存，ConcurrentHashMap 是多余的。
2. `ResolveResultParser.kt:117-132`：capabilities 布尔与分组非空二次交叉、counts 用分组大小覆盖声明值。方向正确（不信任生产端），但代码没说这是信任边界，读的人会以为是普通赋值。
3. `xhs/cli/xhs_android_entry.py:259-277`：resolve 响应同时输出 v1 扁平键（`source_url/title/author/cover_url/media_type`）和 v2 嵌套（`source/work`），双份载荷。
4. `MainActivity.kt:1528-1532`：`setUiEnabled(enabled)` 里 `btnClear.isEnabled = true` 是写死的特例，参数名掩盖了语义。
5. `CookieVault.kt:30-39`：读到旧明文 Cookie 时自动迁移加密。这是一次性迁移路径，合理，但没有任何注释说明，半年后会被当成多余兜底删掉。

**总评：项目整体防御并不算过度（全项目仅 3 处 `catch (e: Exception)`、0 个 `!!`），病根集中在「双协议兼容」和「兜底叠兜底」两处。**

## 四、猜字段

- `dy/core/api_client.py:467` `data.get("mix_info") or data.get("mix_detail") or data`；`:481` music 同款；`:619` cursor/offset；`:682` cid/comment_id。
- `dy/core/downloader_base.py:601,643,710,777`：`images or image_list`、`uri or vid or download_addr.uri`、`url_list or urlList` 等多 key 探测散落在业务代码各处。
- 对照：`dy/cli/dy_resource_normalizer.py` 已经是「把不稳定响应归一成 v2 协议」的正确归宿（还带水印 URL 策略注释），但上面的探测没有收编进去，仍在调用点散布。
- `dy/cli/dy_android_entry.py:366-373` `_runtime_cookies` 用 `getattr(config, "get_cookies", None)` 鸭子类型探测自己的 ConfigLoader 接口——配置类是自家代码，接口应确定。
- 外部 API 有版本差异，容忍多 key 本身合理；**问题是分散**：应集中到 normalizer 一层，业务代码只见单一协议。

## 五、设计不合理 / 冗余

1. **MainActivity 上帝类（1608 行，约 10 个职责）**：输入/剪贴板轮询、分享接入、预览渲染、缩略图视图手工构建、下载编排、历史、异常日志、气泡停靠几何、Insets。
   - `showExceptionLogsDialog`（885-1023，约 140 行）在 Activity 里用代码手搭 BottomSheetDialog：嵌套 LinearLayout/ScrollView、`(180 * resources.displayMetrics.density).toInt()` 手写密度换算（项目已有 `dp()`）、`content.getChildAt(childCount - 1) as? LinearLayout` 反查按钮这种脆弱寻址；**复制按钮逻辑原样粘贴两遍**（919-934 与 997-1010）。
   - `recordResolveException`（1025-1072）9 个参数，渠道显示名映射、脱敏、阶段拼接全部内联在 Activity。
   - `buildDownloadRequest`（595-666）快照策略含魔法数 `.take(8)/.take(100)`、`startsWith("https://")` 过滤——这类策略该进 Policy 类（项目已有先例）。
2. **MediaStorageManager 三胞胎**（154-227）：`registerVideo/Image/AudioToMediaStore` 三个 25 行函数只差 MediaStore 集合与目录；另有 `isVideoMimeType/isImageMimeType/isAudioMimeType` 三个一行包装。同文件里既有过度拆分又有该合不合。
3. **双 JSON 栈**：`ExceptionLogStore` 与 `DownloadHistoryStore` 用 `org.json` 手写对称的 toJson/toRecord（结构完全平行的双胞胎），而桥接层用 Moshi。一个项目两套 JSON。
4. **三个 Moshi 实例**：`ResolveResultParser`、`DownloadResultParser`、`BasePyDownloadModule` 各建一个（带反射 Adapter 的 Moshi 构造成本不低）。
5. **`PyDownloadResult/PyResolveResult` 用 `open class` 但无子类**：应为 data class（自动获得 toString/equals/copy，日志与测试受益）。
6. **`BridgeAbilityExt.kt:12-22`**：`Ability/StoreModule/DownloadModule` 全局 PascalCase 属性是服务定位器，命名违反 Kotlin 惯例（大写留给类型）。单人项目可接受，但至少命名该收敛。
7. **`formatSpeed(kbps: Int)`（CommonUtils.kt:35-42）**：参数叫 kbps（千比特），实现按 KB/s（千字节）处理——命名错误。（随死代码一并清除。）
8. `dp()` 三处重复实现：`MainActivity.kt:1132`、`AppUpdateManager.kt:279`、`ui/util/UiUtils.kt:38`。

## 六、风格一致性问题

1. **`@author （原作者邮箱已脱敏）` 模板注释 ×17 个文件**：工作邮箱出现在公开匿名仓库；`@desc:` 全部为空。这是 IDE 模板残留，不是文档。
2. **全限定类名补丁痕迹 ×4**：`BasePyDownloadModule.kt:65`、`BridgeAbilityManager.kt:65`（新平台接入时没加 import 的典型痕迹）、`LocalStorage.kt:24,27`。
3. **66 处硬编码中文**分布在 `MainActivity / ResolveResultParser / PyDownloadResultExt / CommonUtils / ExceptionLogStore`，与其余走 `strings.xml` 的部分双轨。数据层（ResolveResultParser）里出现 UI 文案尤其突兀。
4. **脱敏正则三处重复**：Kotlin `ExceptionLogRecord.redactText`（ExceptionLogStore.kt:130-138）、Python `_safe_diagnostic_text`（dy_android_entry.py:514-524）、`_safe_diagnostic_traceback`（:527-538）同一套正则抄三份。
5. 无 ktlint/detekt/lint 门禁。

## 七、做得好的（保持住）

- **update/ 模块是全项目标杆**：`ApkContentException` 区分内容级错误并带 `recoverableByFreshDownload`、Range 断点续传、为什么能这么做的注释（ApkDownloader.kt:131）、策略纯函数 + `ApkDownloadPolicyTest`。
- **Policy 抽取模式**：`PreviewUiPolicy / PreviewRequestPolicy / ProgressBubblePolicy / MotionSpec / VideoPlaybackGate` 各自可单测（14 个测试文件）。
- `dy_resource_normalizer.py` 单层归一 + 匿名播放端点构造注释（:102-107）。
- `x/x_android_entry.py` 桥接协议 docstring、`DownloadSnapshot` 的 WAF 快照设计注释。
- 0 个 `!!`；协程结构化并发（withContext/ensureActive）用法正确；平台模块工厂（10 行的平台五件套）干净。

## 八、参考区对照

| 项目 | 可借鉴 | 不学 |
|---|---|---|
| ytdlnis（最接近） | MainActivity 仅 664 行：状态在 13 个 ViewModel、数据在 8 Repository + Room、下载在 `services/`+`work/` 后台 | `util/UiUtil.kt` 3310 行的 util 巨石；手工单例满天飞 |
| astrbot_plugin_media_parser | `core/parser/platform/<平台>.py` 一平台一文件 + 统一分发注册（AGPL 仅参考风格） | 平台文件普遍 1000+ 行不拆 |
| DYDownloader | 剪贴板识别与预览交互的产品形态 | `ResourceFragment` 1540 行、`DouyinDownloader` 1234 行——上帝类反例 |
| XApis | vendor 固定版本、目录隔离 | — |

**对照结论**：本项目不需要引入 Hilt/Compose 之类的大架构改造（单人项目、v2.4.3 刚发版），需要的是：
1. Activity 瘦身到「装配 + 转发」，状态与流程进 Controller/ViewModel（对齐 ytdlnis 形态）；
2. 文件行数红线：Activity ≤ 500 行、一般类 ≤ 300 行、函数 ≤ 80 行；
3. 平台接入口保持现在的 10 行五件套工厂（这比 ytdlnis 的做法还干净，别动）。

## 九、改进计划

### P0（2026-09-29 本轮执行，✅ 已完成并通过验证）
- [x] 修复 X 平台持久化 bug：`getAllAbility()` 改为 `DownloadType.entries.toList()`，`LocalStorage.getAbility()` 去掉重复白名单。
- [x] 删除 Kotlin 死代码：`PyDownloadResultExt.kt` 整文件、`CommonUtils` 三个孤儿函数、`ResolveResultParser` V1 分支、`PyResolveResponse` v1 扁平字段、`lifecycle.viewmodel.ktx` 依赖。
- [x] 删除 Python 桌面遗产（约 4600 行）：downloader_factory 链、downloader_base、control/、storage/、user_modes/、retry_executor、discovery、metadata、transcript_manager、comments_collector、notifier、naming、dy_api_diagnostics；`core/__init__.py` 裁剪为 DouyinAPIClient + URLParser。
- [x] Python 入口去重：`_resolve_async/_download_async` 抽出共用 `_resolve_anonymous_or_cookie_fallback`。
- [x] 脱敏正则收编到 `common/android_utils.redact_sensitive_text`。
- [x] Kotlin 清理：@author ×17、全限定名 ×4、`PyDownloadResult/PyResolveResult` 转 data class、共享 `BridgeJson` Moshi 实例、解析器错误文案保持协议层中文字面量（与 Python 端 JSON 消息一致，转 strings.xml 需 appContext，会在单测崩，留 P1 随消息管道统一处理）。
- [x] `MediaStorageManager` 三个 register 合一。
- [x] `MainActivity.showExceptionLogsDialog` 拆出 `ui/ExceptionLogDialog.kt`（含复制按钮逻辑去重、硬编码文案进 strings.xml）。
- [x] XHS resolve 响应去掉 v1 扁平键（对齐 dy 的 v2-only）。

**P0 验证记录**：Python 单测 47/47 通过；Android 单测 47/47 通过（含改写的 `ResolveResultParserTest.rejectsResponseWithoutSchemaVersion`）；assembleDebug 成功；模拟器（Pixel 7 API 36）真实抖音链接 E2E：分享口令提取 → v.douyin.com 短链解析（温柚柚视频作品，2 资源）→ 预览渲染 → 保存视频 → `Movies/Douyin/*.mp4`（ftyp 头校验有效），logcat 无 FATAL。改动合计 70 文件 +185/−6448 行。

### P1（2026-09-29 第二轮，✅ 已完成并通过验证）
- [x] MainActivity 拆分：1608 → **434 行**装配层；`ProgressBubbleController`（283 行，停靠几何/吸附/隐藏调度）、`DownloadFlowController`（444 行，解析/下载编排+历史+异常记录）、`PreviewSectionRenderer`（461 行，预览区渲染与状态）。
- [x] `ExceptionLogStore/DownloadHistoryStore` 抽公共 `JsonListStore<T>` 基类（保持 org.json 序列化格式不变以兼容既有存量数据；Uri 需要 DTO 转换，迁 Moshi 收益不抵风险）。
- [x] `recordResolveException` 渠道显示映射下沉为 `ExceptionLogRecord.displayChannel`。
- [x] 硬编码中文：控件文案全部进 strings.xml（异常日志对话框 3 条、缩略图占位「音频」等）；**日志载荷与协议消息保持字面量**（record 字段/formatForCopy/解析协议错误文案），与 Python 端 JSON 消息同语言，转资源需 appContext 会在单测崩，属消息管道统一问题留 P3。
- [x] detekt 门禁：`detekt 1.23.8` 接入 Gradle（腾讯/阿里镜像可解析），`detekt.yml` 调优（关魔法数/行长 140/复杂度阈值 120），存量问题入 `detekt-baseline.xml`（新代码违反即挂构建）；建议把 `:app:detekt` 加进发版前检查清单。
- [x] `_runtime_cookies` 鸭子类型改为直调 `ConfigLoader.get_cookies()`——测试替身 FakeConfig 补 `get_cookies()` 后 47/47 通过（鸭子类型原本掩盖了替身缺方法的事实）。
- [x] `DownloadSelection.resourceIds` 从 Kotlin 协议端移除（Python 按缺省=全选处理，内部默认请求仍自带该字段），协议注释写明多选为未来扩展。
- [x] CookieVault 明文迁移路径补注释。

### P2（2026-09-29 第二轮，✅ 已完成）
- [x] `api_client.py` 1058 → **333 行**：分页 API 族（用户/合集/音乐/直播/热搜/搜索/评论）与 Playwright 浏览器采集族仅被已删除的桌面模块引用，整段移除；残留的 `mix_info or mix_detail`、`cursor or offset`、`cid or comment_id` 多 key 探测全部随之消失，存活面只剩 `get_video_detail`（含 aid 双候选过滤重试）与 `resolve_short_url`。
- [x] `xhs/source` 桌面件剥离：删除 `expansion/pyi_rth_beartype.py`（PyInstaller 钩子）、`locale/po_to_mo.py`、`locale/generate_path.py`（翻译构建工具）；`translation/` 经查证被 `android_xhs.py` 实际使用（gettext 多语言），**保留**，locale 数据目录保留。
- [x] dy/core 多 key 探测：随 api_client 死方法清理已归零（downloader_base 的探测点在 P0 已随文件删除）。

### P3（记录在案，暂不做）
- 消息管道统一（协议消息/日志载荷的 i18n 策略）。
- 引入 ViewModel 层（当前控制器直接持有宿主 Activity 引用，单人项目可接受；若未来加多 Activity/多表盘再上）。
- 三个空壳 StoreModule 在出现真实平台差异时再保留，否则简化。
- xhs/source 上游再同步时重新评估 vendored 范围。

**P1/P2 验证记录（2026-09-29 第二轮）**：Python 单测 47/47、Android 单测 47/47、detekt 通过（baseline 模式）、assembleDebug 成功；模拟器 E2E 重跑真实抖音链接（温柚柚视频）：分享提取 → 解析预览（作者/标签/缩略图正常）→ 保存视频 → `Movies/Douyin/…(1).mp4` 入库（同名自动去重命名验证了统一后的 registerMediaFile）→ 「我的」面板历史记录正常显示旧格式存量数据（JsonListStore 向后兼容）。logcat 零 FATAL。第二轮合计 82 文件 +321/−8515 行（两轮累计净删约 12600 行）。
