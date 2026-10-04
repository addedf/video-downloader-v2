# X 帖子与博主主页媒体提取验收

日期：2026-10-04（Asia/Shanghai）。开发基线：9f804a9。下文为发版前的实现及验收记录；本批功能纳入 v2.5.0（versionCode 16），发布入口见 [GitHub Release](https://github.com/addedf/video-downloader-v2/releases/tag/v2.5.0)。

## 本次实现

- 识别 X / Twitter 帖子、`/i/status/`、博主主页及 `/用户名/media`，展开 t.co 短链；拒绝非 X 跳转目标、系统导航页及伪造域名。
- 单帖保留现有 guest GraphQL 直连，失败降级至 FxTwitter v2；主页使用 FxTwitter v2 媒体时间线。
- 图片保存原格式原图，视频/GIF 选最高码率 MP4；仅提取当前作者的媒体，不递归引入引用帖，也不保存他人转推媒体。
- 主页自动分页、按媒体地址去重；每轮最多 10 页或约 30 秒（单个正在进行的请求可能额外耗时），保留游标继续提取。连续 3 页无新增媒体则暂停；重复游标停止。仅在上游明确无下一游标时声明“接口当前可访问的全部媒体”。
- 预览支持“全部／视频／图片”以及现有工作区的勾选功能。续页保留已有选择，新资源默认选中。缩略图每组 40 项，支持上一组／下一组；X 下载快照取消旧的 100 项截断。
- 下载直接使用预览快照，不重新拉取整个主页；严格核对来源、资源 ID、所选资源及 HTTPS 媒体 CDN。空选择不会变成全部下载。
- 下载到 `.part` 文件，校验类型和长度后原子改名；失败清理残片。批次进度按资源数累计。部分失败不报全部成功，已成功保存的 URI 保留在历史中；相册注册失败单独报告。

## 真实链接证据

| 输入 | 桌面真实接口及下载结果 |
| --- | --- |
| 用户提供的 `https://x.com/i/status/2106090316747207047` | guest 直连成功，解析 4 张图片；抽样原图完整下载 375,545 字节 |
| 用户提供的 `https://x.com/donna44qz3` | 返回 5 条作者原创媒体帖，共 10 张图片；抽样原图完整下载 155,111 字节 |
| `https://x.com/NASA` | 首轮 10 页，115 条去重媒体帖、66 个视频及 59 张图片，共 125 项；视频样本 8,896,730 字节，图片样本 1,432,957 字节；所选样例帖 guest 直连成功 |

`donna44qz3` 的后续请求返回空页，但仍带新的游标。这是实测到的上游行为，不能据此证明该作者全部历史媒体已取全。应用保留当前 10 项并显示未确认完整及继续入口，不将其伪装为完整归档。

## 验证边界

截至本次检查：Python 全量 79 项通过；Kotlin JVM 53 项通过；detekt 0 问题；Debug APK 与 AndroidTest APK 构建通过。用户指定链接的真实网络仪器测试 1 项通过；NASA 大主页与视频 MediaStore 验证、预览勾选回归合计 7 项通过。

- 新增 X Python 测试覆盖链接、短链、媒体提取、质量选择、分页、重复游标、空页、限流部分结果、快照选择、CDN 校验和原子下载。
- Kotlin 测试覆盖混合媒体标签、主页合并去重、结束分页和错误隔离。
- 真实网络仪器测试为显式启用：默认不会访问外网或写相册。通过 `runXLive=true` 启用，`xProfile` / `xPost` 可以指定链接。测试读取写入后的 MediaStore URI，并对视频读取正数时长，之后清理测试下载文件及媒体条目。
- 用户指定帖子及主页已在 Pixel_7_API_36 模拟器上通过：Chaquopy 解析 → 原生预览 → 选择快照 → 下载 → MediaStore 写入和读取。该账号本轮只有图片，视频闭环另用 NASA 验证并通过，视频时长可读取；大主页续页后的全部资源数量与下载快照数量一致（超过 100 项）。
- 未执行物理 Android 手机验收；未验收私密账号、登录可见内容、被删除内容或平台未暴露的历史媒体。

模拟器另通过实际 VIEW 链接填入、点击“解析资源”的界面检查：NASA 显示混合媒体标签、未取全提示、继续提取按钮、勾选数量与保存按钮，视频预览可播放。不同请求批次受 30 秒预算及上游返回影响，首轮数量可能不同；不是静态承诺的资源总量。

## 运行方式

```sh
.venv/bin/python -m unittest discover -s android/tests/python
cd android
./gradlew :app:testDebugUnitTest :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w \
  -e class com.zemin.downloader.XDownloadInstrumentedTest \
  -e runXLive true \
  -e xProfile https://x.com/donna44qz3 \
  -e xPost https://x.com/i/status/2106090316747207047 \
  com.ricardo.videodownloader.v2.test/androidx.test.runner.AndroidJUnitRunner
```

本地 Gradle 使用现有 Java 21 toolchain；Chaquopy 构建 Python 为本机 uv 管理的 Python 3.12，可通过 `-Pchaquopy.buildPython=...` 指定。

## 实际方案与原策划的区别

当前没有 vendor XApis，没有实现 X Cookie 登录，也不会把用户 Cookie 发送给 FxTwitter。公开单帖是 guest → FxTwitter v2；公开主页直接使用 FxTwitter v2。主页功能依赖第三方服务及 X 当前公开返回范围，不能保证取得“账号历史上的所有内容”。MP3 提取不属于本次交付。

接口依据：[FxEmbed 帖子 API 文档](https://github.com/FxEmbed/FxEmbed/wiki/Status-Fetch-API)、[主页媒体 API](https://docs.fxembed.com/api/twitter/operations/2profilehandlemedia/)、[当前 OpenAPI](https://api.fxtwitter.com/2/openapi.json)。已实时核对 v2 `status`、`results`、`cursor.bottom` 和 `media.all[].formats` 字段。
