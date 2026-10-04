# 应用版本更新发布说明

这里的“更新”是下载并安装完整 APK，不是运行时替换代码的热修复。

## 发布新版本

1. 增加 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。
2. 使用与已发布版本相同的证书构建 release APK。
3. 用 `apksigner verify --print-certs` 检查签名，用 `shasum -a 256` 计算摘要。
4. 将 APK 复制到 `android/docs/android/`（相对仓库根目录），并创建指向本次源码提交的 GitHub Release 作为备用发布入口。
5. 更新 `android/docs/android/update.json` 的版本、主镜像 URL、SHA-256、更新说明和发布时间；保持现有清单字段及 `minSupportedVersionCode`，除非本次明确需要强制更新。
6. 提交并推送源码、版本和清单。GitHub Pages 实际发布源为 `gh-pages` 分支根目录；还须将 APK 和清单同步到该分支的 `android/`，保留根目录 `CNAME` 和 `index.html` 后推送。仅更新 `main` 不会部署镜像。
7. 等待 Pages 构建成功，确认 `https://updates.menkange.com/android/update.json` 返回新内容；重新下载线上 APK 核对 SHA-256，并核对 GitHub Release 资产摘要。保留上一版镜像，供仍缓存旧清单的客户端下载。

App 只接受以下来源：

- 更新清单：`https://updates.menkange.com/android/update.json`
- APK：优先使用 `updates.menkange.com/android/` 同域镜像；`addedf/video-downloader-v2` 的 GitHub Releases 作为备用发布入口

GitHub Release 是手动下载备用入口，客户端不会在主镜像失败后自动切换到它。更新检查发生在主界面本次生命周期首次启动时，发版后需要关闭应用再重新进入；点过“稍后”会忽略该版本，可通过发布页手动下载安装。

下载完成后，App 还会校验 SHA-256、包名、`versionCode` 和签名证书，全部通过才会打开 Android 系统安装页。首次安装应用内更新时，还需要在系统页面开启“允许来自此来源”；v2.3.3 起会在跳转前明确提示，并在返回后自动继续安装。

下载链路自 v2.4.2 起支持弱网加固：半成品保留在应用缓存中，下载中断后基于 HTTP Range 断点续传（已下载部分的 SHA-256 摘要会重新计入校验）；单次下载失败自动重试，最多尝试 5 次（间隔 1/2/4/8 秒递增），读超时放宽到 30 秒；摘要不符且发生在续传路径上时会清空半成品重下一次，全新下载仍不匹配则判定为发布源异常。App 外的浏览器下载同样可用作兜底。

## 签名兼容性

v2.0.0 已使用仓库中的 `app/debug.keystore` 发布。Android 覆盖安装要求后续版本继续使用同一证书，否则用户必须先卸载旧版。这个证书已经公开，不适合作为长期安全身份；如果改用新的私有 release 证书，应同时更换包名，或接受一次无法覆盖升级的迁移。

v2.1.0 是从上游 Gitee 更新地址迁移到自有通道的引导版本。v2.0.0 中没有自有更新客户端，因此用户需要手动安装一次 v2.1.0；从 v2.1.0 起，后续版本即可在应用内完成安全下载和系统确认安装。
