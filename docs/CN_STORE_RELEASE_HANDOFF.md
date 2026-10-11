# 国内安卓商店正式包交付手册

> 2026-10-11 更新：本文为历史上架检查资料。当前 Demo 已移除全部 Gradle 发布门禁，发布配置不影响试用构建；应用为 com.roboyi.rtspplayer / RtspPlayer / 1.0.5。历史版本、哈希和阻断项不代表当前状态，旧门禁命令不再适用。请使用 [当前发布说明](RELEASING.md)。

个人开发者上架时，先阅读 `docs/INDIVIDUAL_CN_STORE_RELEASE_GUIDE.md`；其中包含个人主体可用渠道、软著、个人 APP 备案及本工程特有缺口。

本手册对应 App `com.roboyi.rtspdemo`、默认标签 `RtspPlayer`、版本号 `3 / 1.0.2-Beta` 和两份 `V1.0.2-Beta` AVC+HEVC AAR。执行正式签发或提交前，先固定是否保留 `Beta` 版本显示，以及最终上架主体和具体商店。任何包名、默认应用标签、AAR 或 codec 范围变更，都要重新核对授权与商业审批。

## 1. 人工填写上架身份和隐私政策

负责：实际运营主体和隐私负责人。

1. 在 `docs/PRIVACY_POLICY_DRAFT.md` 填写企业/个人法定全称、有效联系地址、隐私邮箱、生效日期、未成年人条款、权利请求处理方式；按最终真机数据流完成第三方组件、设备信息、摄像机凭据、视频文件和跨境端点说明。法务核对后发布无需登录的 HTTPS 简体中文全文，保存发布页面快照和生效版本。
2. 复制 `release-policy.properties.example` 为仓库根目录的 `release-policy.properties`，填写 `privacyOperator`、`privacyPolicyUrl`、`privacyContactEmail`、`privacyPolicyVersion`。此文件已被 Git 忽略。政策重大变更时递增 `privacyPolicyVersion`（格式 `YYYY-MM-DD` 或 `YYYY-MM-DD-序号`），应用会重新显示首次告知。
3. 在非开发网络中打开政策 URL，确认内容与 App 内运营主体和邮箱、商店隐私政策地址一致。构建门禁只能检查 URL 格式，不能代替网页可用性和内容审核。

```powershell
# 在 E:\RoboYi_Code\RtspPlayer_Demo 运行；填完本地配置后检查门禁。
Copy-Item release-policy.properties.example release-policy.properties
# 当前版本无 verifyReleasePolicy 任务，隐私信息为可选构建配置。
```

政策和权限自查参照[网信办 App 个人信息认定方法](https://www.cac.gov.cn/2019-12/27/c_1578986455686625.htm)与[个人信息保护法](https://www.npc.gov.cn/npc/c2/c30834/202108/t20210820_313088.html)。

## 2. 法务确认商业发行权利

负责：SDK 权利人、运营主体及法务；所有结论应保留合同或审批编号。

1. 核对 RoboYiPlayer 自有代码的著作权和 App 上架主体的再分发权；若主体不同，取得明确授权。
2. 对实际地区 `CN`、国内应用商店分发、播放、录像、截图及 AVC+HEVC 范围，判断 H.264/H.265 专利许可是否已覆盖本产品。若只能批准 AVC，工程需改用**成对 AVC AAR**和 AVC RYL4，并对新增 AAR 更新文件名版本号、哈希、SBOM 与审批。
3. 审阅 GStreamer/GLib 等 LGPL 的公告、对应源码、构建脚本与替换/重新链接方式，特别是用户拿到**签名 APK**后如何行使替换权。当前 APK 已能从“设置—开源组件与许可证”查看许可文本；12 份固定版本源码归档已由 SDK 脚本下载到本地缓存并通过 SHA-256 校验，交付包仍需连同实际审批生成。
4. 核对 App 整包 AndroidX/Kotlin 等依赖公告。依赖树在 `docs/release-evidence/V1.0.2-Beta/app-release-runtime-dependencies.txt`，App 内许可入口已包含主要公告与文本。

SDK 源码的 `docs/compliance/COMPLIANCE_RELEASE_CHECKLIST.md` 是正式审批逐项表。专利与 LGPL 是独立权利问题，签发 RYL4 不会自动解决它们。

## 3. 冻结 SDK 与签署商业审批

负责：工程发布人和两项法律审批人。

1. SDK 工程当前冻结于提交 `ebd18b53bc721f648ba2f1c524548e2faea6e4f6`，工作树干净，App 内 AAR 与 SDK 当前构建产物哈希一致。审批前由发布人复核此提交；任何后续改动都需重新提交并更新审批中的 `gitCommit`。`commercialRelease` 会拒绝脏工作树。
2. 复制 SDK `compliance/commercial-release-approval.example.json` 到受控审批目录，并按 `compliance/commercial-release-approval.schema.json` 填写实际 `version`、40 位 `gitCommit`、`regions: ["CN"]`、**实际商店渠道标识**、有效截止日期、审批人和审批编号。`reviews.gstreamerLgplDistribution` 与 `reviews.codecPatentCommercialization` 只能在各自真实批准后改为 `APPROVED`。`artifacts` 字段使用 SDK 输出的无版本文件名及以下真实哈希：

   - Core `2e6dbdff2882c83e28114cee65c8cff7c496d94ae665cf2105bde858de0a3c88`
   - Transport `e1a62777449c136ddeb6de2f7322a563df93409a9eb22c27428f88b926309b4e`

3. 运行商业门禁及 SDK 交付打包。若提交或重建改变 AAR 哈希，审批记录和 App 内的 AAR 必须同步更新；**任何更新的 AAR 文件名版本号也必须递增**。

```powershell
# 在 E:\RoboYi_Code\RtspPlayer_SDK 运行；不要在命令或日志中放私钥。
git status --short
$publicKeys = Get-Content -Raw E:\roboyi-keys\public-key-ring.txt
.\gradlew.bat commercialRelease `
  "-ProboyiLicensePublicKeys=$($publicKeys.Trim())" `
  "-PcommercialApproval=E:\审批目录\commercial-release-approval.json" `
  "-PcommercialRegion=CN" `
  "-PcommercialChannel=实际商店渠道标识" `
  "-PcommercialCodecScope=AVC_HEVC" --console=plain
.\scripts\cache-gstreamer-sources.ps1 -VerifyOnly
.\scripts\package-roboyiplayer-compliance.ps1 `
  -Approval E:\审批目录\commercial-release-approval.json `
  -Region CN -Channel 实际商店渠道标识 -CodecScope AVC_HEVC
```

`E:\roboyi-keys\public-key-ring.txt` 已由现有 `.spki` 生成并校验，仅含公钥。审批 JSON 中的渠道字段必须与命令一致；示例里的 `DIRECT_B2B` 不能代替应用商店。

## 4. 由授权人员签发正式 RYL4

负责：SDK 私钥管理员。`E:\roboyi-keys\prod-2026-01-private.pk8` 只供受控签发环境使用，不复制到 App 工程、CI 或聊天。请复核开发机上现存私钥的保管方式并迁移至批准的离线/加密环境。

1. 按商业合同决定 `licenseId`、有效期（UTC Unix 秒）及 `AVC_HEVC` 范围。App 当前实际用到 `PLAYBACK,RECORDING,SCREENSHOT`；当前界面不使用 `RTSP_PUSH`，无商业决定时不加入。
2. 在 SDK 目录按以下命令签发；`--expires-at` 由合同确定，不直接照抄永久授权示例。SDK 工具不会输出私钥字节。
3. 用配对的 `.spki` 验签，并由第二人核对 token 中的默认标签、包名、功能、codec、有期/永久选择和交付 AAR 哈希。保留外部签发记录。

```powershell
java .\tools\license\LicenseTool.java issue `
  --private E:\roboyi-keys\prod-2026-01-private.pk8 `
  --key-id prod-2026-01 `
  --license-id 由审批确定的唯一编号 `
  --application-label RtspPlayer `
  --packages com.roboyi.rtspdemo `
  --expires-at 由合同确定的UTC秒数 `
  --codec-scope AVC_HEVC `
  --features PLAYBACK,RECORDING,SCREENSHOT `
  --output E:\受控交付目录\rtspplayer-production.ryl
java .\tools\license\LicenseTool.java verify `
  --public E:\roboyi-keys\prod-2026-01-public.spki `
  --license E:\受控交付目录\rtspplayer-production.ryl
```

将签发文件中的**单行 token**填入 App 根目录 `release-license.properties` 的 `licenseKey`，`publicKeyFile=E:/roboyi-keys/prod-2026-01-public.spki`。此文件已被 Git 忽略；不要在工单或日志中复制 token。App 发布门禁还会确认此公钥确实编入当前 Core AAR。

## 5. 核对 Android 应用签名

负责：应用签名密钥管理员。它与 RYL4 签发私钥是两套不同密钥。

`E:\roboyi-keys\roboyi_rtsp_apkstore` 的文件头符合 PKCS#12 容器形式，但**别名、口令、证书权属和续更用途尚未验证**。管理员在本机运行以下命令，交互输入口令，记下别名和证书 SHA-256，并核对与既有上架包是否一致：

```powershell
keytool -list -v -storetype PKCS12 -keystore E:\roboyi-keys\roboyi_rtsp_apkstore
```

复制 `release-signing.properties.example` 为 `release-signing.properties`，填入真实 `storeFile`、`storeType=pkcs12`、`keyAlias`；建议不在该文件留口令。在本次 PowerShell 会话中设置 `ROBOYI_RELEASE_STORE_PASSWORD` 和 `ROBOYI_RELEASE_KEY_PASSWORD` 环境变量后运行构建；本地私密文件和变量不要截图或发到聊天中。对签名容器做受控备份，后续同包名更新必须沿用正确签名。

## 6. 生成并验收最终 APK

```powershell
# 在 E:\RoboYi_Code\RtspPlayer_Demo 运行。
.\gradlew.bat :app:assembleRelease --offline --console=plain
# 成功后检查 app\build\outputs\apk\release\RoboYiPlayer-1.0.2-Beta.apk。
```

发布前记录：APK SHA-256、`apksigner verify --print-certs` 的证书指纹、`aapt dump badging` 的包名/版本/应用标签、最终合并 Manifest 的权限、三份 AAR 的 SHA-256 和启动授权测试结果。用干净安装设备测试首次拒绝/同意、隐私政策入口、撤回与删除、ONVIF 搜索、RTSP/RTSPS 播放、摄像头推流、录像/截图、水印删除、升级迁移、离线场景和 logcat；同时抓包确认仅向用户选定摄像机及局域网设备发送所声明的数据。当前连接的 Android 12 设备为 4 KB 页，可做基本回归，不能代表 Android 16 或 16 KB 页设备。

```powershell
# 本机 Android SDK 37.0.0 build-tools；在 App 目录执行。
$apk = 'app\build\outputs\apk\release\RoboYiPlayer-1.0.2-Beta.apk'
Get-FileHash -LiteralPath $apk -Algorithm SHA256
& 'F:\Program Files\Android\SDK\build-tools\37.0.0\apksigner.bat' verify --print-certs $apk
& 'F:\Program Files\Android\SDK\build-tools\37.0.0\aapt.exe' dump badging $apk
& 'F:\Program Files\Android\SDK\build-tools\37.0.0\aapt.exe' dump permissions $apk
```

当前连接设备已安装 `com.roboyi.rtspdemo` 的 `1.0.1-Beta` 并存有用户数据。测试新版时请先决定数据备份与升级方案；不要为测试而卸载或覆盖它。另需找一台干净设备验证首次安装，及支持 16 KB 页的设备或模拟器验证实际加载。

## 7. 国内商店人工事项

运营主体按实际服务方式判断是否属于需办理的境内互联网信息服务 APP 备案；适用时先完成备案并在应用中展示要求的信息。[工信部 APP 备案通知](https://www.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2023/art_920db564162e4312916a01bed6540ad8.html) 各商店分别提交主体证明、隐私政策、权限/SDK 声明、软件权属材料、应用介绍、截图及实际要求的其他材料。以**同一签名的最终 APK**完成渠道扫描和反馈整改，记录每店提交版本与审核结果。
