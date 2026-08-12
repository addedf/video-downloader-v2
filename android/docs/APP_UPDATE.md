# 应用版本更新发布说明

这里的“更新”是下载并安装完整 APK，不是运行时替换代码的热修复。

## 发布新版本

1. 增加 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。
2. 使用与已发布版本相同的证书构建 release APK。
3. 用 `apksigner verify --print-certs` 检查签名，用 `shasum -a 256` 计算摘要。
4. 将 APK 复制到 `docs/android/` 作为更新域名的主下载镜像，并创建 GitHub Release 作为备用发布入口。
5. 更新 `docs/android/update.json` 的版本、主镜像 URL、SHA-256、更新说明和发布时间。
6. 提交清单后，确认 `https://updates.menkange.com/android/update.json` 返回新内容。

App 只接受以下来源：

- 更新清单：`https://updates.menkange.com/android/update.json`
- APK：优先使用 `updates.menkange.com/android/` 同域镜像；`addedf/video-downloader` 的 GitHub Releases 作为备用发布入口

下载完成后，App 还会校验 SHA-256、包名、`versionCode` 和签名证书，全部通过才会打开 Android 系统安装页。首次安装应用内更新时，还需要在系统页面开启“允许来自此来源”；v2.3.3 起会在跳转前明确提示，并在返回后自动继续安装。

## 签名兼容性

v2.0.0 已使用仓库中的 `app/debug.keystore` 发布。Android 覆盖安装要求后续版本继续使用同一证书，否则用户必须先卸载旧版。这个证书已经公开，不适合作为长期安全身份；如果改用新的私有 release 证书，应同时更换包名，或接受一次无法覆盖升级的迁移。

v2.1.0 是从上游 Gitee 更新地址迁移到自有通道的引导版本。v2.0.0 中没有自有更新客户端，因此用户需要手动安装一次 v2.1.0；从 v2.1.0 起，后续版本即可在应用内完成安全下载和系统确认安装。
