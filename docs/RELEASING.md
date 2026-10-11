# Demo 构建与正式 APK 打包

当前应用为 `RtspPlayer` / `com.roboyi.rtspplayer`，版本 `1.0.5`（versionCode 6）。SDK AAR 的版本独立管理。

## 运行 Demo

使用 JDK 17 和 Android SDK 36，用 Android Studio 运行 `app`，或执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

不需要发布配置、签名口令或审批文件。`build.gradle` 不定义发布校验任务，也不挂接发布门禁。缺少正式签名配置时，release 构建生成未签名 APK；试用请运行 debug。

## 本机打包

以下可选配置均已被 Git 忽略：

- `release-signing.properties`：签名容器、别名和本机口令；也可通过 `ROBOYI_RELEASE_STORE_PASSWORD` / `ROBOYI_RELEASE_KEY_PASSWORD` 提供口令。反斜杠按 Java properties 格式转义。
- `release-license.properties`：匹配包名、应用名、codec 和功能的 RYL4 授权，替换预置授权。签发私钥不进入工程。
- `release-policy.properties`：主体、邮箱、政策版本和可选 HTTPS 地址。默认主体为 Roboyi、邮箱为 roboyi@126.com、政策版本为 2026-10-11。

可直接运行 `:app:assembleRelease`。发布人也可主动执行独立脚本，它不影响普通 Gradle 构建：

```powershell
.\tools\package-release.ps1
```

脚本检查签名私钥和证书有效期，构建 release，检查 APK 签名、包名和版本、debuggable、16 KB ELF/ZIP 对齐及隐私文本一致性。APK 与不含口令的校验记录输出到 `app/release/`，不自动执行单元或设备测试。

正式证书签署的 APK 不能覆盖此前使用 Android Debug 证书签署的同包名演示包。先备份所需数据，再卸载旧演示包；今后升级持续使用同一正式签名密钥。

## 隐私政策与 HTTPS

全文为 `docs/privacy/index.html`，相同文本内置于 `app/src/main/assets/legal/privacy-policy.html`。没有在线地址时可离线阅读。更新文本后同步两份文件并更新政策版本。

提交应用商店时将页面发布到公开网站。例如在 GitHub 仓库 **Settings → Pages** 选择从分支发布、目录 `/docs`，访问 Pages 提供的站点地址下的 `/privacy/`，确认无需登录，并开启 HTTPS。把实际地址填入 `release-policy.properties` 后重新打包。[GitHub Pages HTTPS 官方说明](https://docs.github.com/en/pages/getting-started-with-github-pages/securing-your-github-pages-site-with-https)。当前远程指向 Gitee，需使用实际 GitHub 仓库才能配置 GitHub Pages。

APK 技术核验与应用商店审核是不同事项。具体渠道要求的公开政策、主体资质、备案、授权和第三方许可材料按实际分发方式准备。历史检查资料保留供参考，旧版本门禁命令和哈希不适用于当前构建。
