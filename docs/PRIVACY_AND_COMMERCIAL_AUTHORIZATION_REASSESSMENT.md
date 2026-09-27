# 隐私披露与商业授权工程复核（2026-09-21）

范围：国内安卓应用商店首发，App `com.roboyi.rtspdemo` / 默认应用名 `RtspPlayer`，两份 `V1.0.2-Beta` AVC+HEVC AAR，SDK 源码 `E:\RoboYi_Code\RtspPlayer_SDK`。依据当前源码、调试包合并 Manifest、AAR 内容及现有发布证据。没有正式 release APK 或真机抓包，联网行为结论仍需动态验证。工程修正后的操作与剩余人工事项见 `CN_STORE_RELEASE_HANDOFF.md`。

## 一、代码实际处理的数据

| 功能 | 代码证据与当前行为 | 隐私披露应写清 |
| --- | --- | --- |
| RTSP 播放 | `MainActivity.kt:407-425` 接收用户输入的 `rtsp://` 或 `rtsps://` 地址并发起连接。URL 可能含账号、密码、令牌、路径、查询参数；目标地址可由用户指定，不保证只在局域网。`MainViewModel.kt:81-84` 在点击连接时就加密保存“上次地址”，即使连接失败；`PlaybackWorkflow.kt:19-23` 仅在视频就绪后写成功连接历史。 | 处理目的、URL/凭据类别、传输至用户指定摄像机或服务端、普通 RTSP 的明文风险、上次地址和最多 10 条成功记录的保留及删除方式。不要声称“只保存成功地址”或“只连接局域网”。 |
| ONVIF/海康设备发现 | `OnvifDiscovery.kt:11-20` 在账号密码均留空时改用 `admin/admin`；`MainActivity.kt:645-656` 先要求用户确认。SDK `CameraDiscovery.kt:54-73,194-235` 进行 ONVIF 搜索，必要时探测局域网海康 HTTP 接口；`OnvifSearcher.kt` 使用 UDP 组播/单播和 HTTP/SOAP。界面展示设备 IP、厂家、型号、固件、序列号、服务地址、流地址等（`MainActivity.kt:694-743`）；原始响应仅在调试包展示（`MainActivity.kt:750`）。 | 搜索范围和请求方式、用户填写或默认探测凭据可能发送给哪些设备、设备标识及服务地址的处理方式；说明正式包不展示原始调试响应。运行中数据保存在内存，源码未见持久化或向开发者服务端上传。 |
| 截图与录像 | `AppCaptureRepository.kt:8-23` 将新文件写入内部 `filesDir/captures`，仍列出旧版本在外部应用专属目录的文件；用户可选中删除。`MainActivity.kt:560-573` 打开文件时通过 `FileProvider` 临时授予所选查看应用读取权。 | 视频画面可能包含可识别人物或场所；本地保存到删除/卸载，旧版文件的位置及删除途径，用户主动交给外部查看应用后的处理边界。不要把“未上传开发者服务器”写成“未向任何第三方传输”。 |
| 水印文字与图片 | 文字保存在普通私有偏好设置，导入图片保存在内部文件；系统选择器导入限制 10 MB、尺寸和像素数。现有水印设置含独立删除图片按钮，“删除本地数据并撤回同意”同时清除文字和图片。 | 本地存储类别、保留期限、删除路径，以及文字可能由用户输入个人信息。 |
| 本地保护与权限 | `SecureConnectionStore.kt:15-55` 使用 Android Keystore AES-GCM，加密并迁移旧版明文 URL；`AndroidManifest.xml:7-15` 关闭备份，允许明文网络。当前调试包合并 Manifest 含 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`WAKE_LOCK`；未见定位、相机、麦克风或广泛相册读取权限。另有 AndroidX 生成的签名级动态广播权限。 | 逐项说明网络、网络状态、Wi-Fi/组播发现、保持唤醒的用途；说明本地加密与备份限制，但不能暗示 RTSP/ONVIF 传输已加密。最终申报以正式 APK 的 Manifest 为准。 |

静态源码与依赖中未发现广告、统计、崩溃上报 SDK 或开发者云端上传接口。实际网络目的地仍需用**最终 release APK**在首次启动、拒绝/接受告知、播放、搜索、截图和录像场景抓包核对；同时检查 logcat 是否泄露完整 URL、账号、密码、设备响应。`docs/PRIVACY_POLICY_DRAFT.md` 现有“未见上传”表述应保持为待核实事实。

## 二、隐私披露与交互待办

| 优先级 | 待办与验收标准 | 依据 |
| --- | --- | --- |
| 发布阻断 | 确认上架主体法定全称、联系地址、实际有效的隐私联系邮箱、生效日期；逐项填完 `docs/PRIVACY_POLICY_DRAFT.md`，发布无需登录的稳定 HTTPS 简体中文全文。最终 APK 首次告知、设置入口、商店填写的主体和 URL 必须一致。 | 草稿仍有占位符；`app/build.gradle:118-129` 只校验主体非空及 URL 语法，不校验网页存在和内容。 |
| 发布阻断 | 核实最终 AAR/原生库的出站流量、组件提供方和权限，形成“组件—目的—数据类别—收件方/目的地—触发时机”清单；填写国内商店的 SDK/权限声明。GStreamer 是内置运行库，是否列为独立“第三方 SDK”按具体商店表单要求填写，但其开源信息和网络处理不能遗漏。 | `app/build.gradle:99-110`、两份 AAR 的 `assets/roboyiplayer/legal/`、调试包合并 Manifest。 |
| 发布阻断 | 把摄像机账号密码、RTSP URL/令牌、设备 IP/型号/序列号、视频截图录像、水印文字图片分别写入政策的数据表，列目的、获取方式、保存位置、保留期限、删除办法、对外传输场景。对明文 RTSP/ONVIF HTTP 和用户可填远端地址作明确说明。 | 上表数据流；`AndroidManifest.xml:15` 允许明文流量。 |
| 工程已完成，发布前复核 | 首次告知从发布配置读取运营主体、隐私邮箱；`verifyReleasePolicy` 要求正式包填写两者、HTTPS URL 和政策版本。人工仍需保证公开政策页面与商店填报一致。 | `app/build.gradle`、`MainActivity.kt`。 |
| 工程已完成，真机待验 | 按 `privacyPolicyVersion` 重新征询同意；应用内提供停止播放、删除历史/媒体/水印、撤回同意和退出入口。删除范围包括符合应用文件命名规则的零字节或未完成媒体文件。 | `MainActivity.kt`、`MainViewModel.kt`、`AppCaptureRepository.kt`。 |
| 发布前核对 | 真机测试拒绝首次告知后无网络/SDK初始化；同意后可从设置打开完整政策；离线、无浏览器、网页不可达时有可理解的处理。升级用户须复核旧外部目录中的录像/截图及旧备份风险。 | `MainActivity.kt:784-825`、`AppCaptureRepository.kt:10-19`。 |

国内隐私自查可对照[网信办《App违法违规收集使用个人信息行为认定方法》](https://www.cac.gov.cn/2019-12/27/c_1578986455686625.htm)和[《个人信息保护法》](https://www.npc.gov.cn/npc/c2/c30834/202108/t20210820_313088.html)。政策文本应由实际运营主体复核，尤其是用户视频中可能出现他人、跨境摄像机端点、未成年人和权利请求处理方式。

## 三、商业授权待办

| 优先级 | 待办与验收标准 | 代码/现有证据 |
| --- | --- | --- |
| 发布阻断 | 固定正式应用身份：`applicationId=com.roboyi.rtspdemo`、默认 `android:label=RtspPlayer`、运营主体、国内首发商店和销售地区。若商店展示名要变更，先确认是否改**默认应用标签**；改标签或包名就必须重新签发 RYL4。若 SDK 权属主体与上架主体不同，留存明确的商业分发授权合同。 | `app/build.gradle:35-46`；SDK `ApplicationLabelIdentity.kt:8-36`、`OfflineLicenseVerifier.kt:140-150`。 |
| 发布阻断 | 由受控签发环境签发非 `demo-` 的 RYL4：准确包名/标签、`AVC_HEVC`、有效期以及 `PLAYBACK,RECORDING,RTSP_PUSH,SCREENSHOT`。当前 App 已调用设备摄像头 RTSP 推流，release 门禁会要求 `RTSP_PUSH`。用 SDK 工具验证签名，并在真实 release App 初始化测试。签发记录保存客户/合同、签发人、密钥 ID、AAR 版本及三个哈希。 | `app/build.gradle` 当前内置 demo token；SDK `tools/license/README.md:38-90`。 |
| 发布阻断 | 法务/业务对 **H.264 与 H.265** 的目标地区、Android 应用分发、解码/录制/编码场景作书面权利判断；确认是否已有可覆盖本产品的许可或需要另行取得。SDK 使用系统 MediaCodec 不自动证明应用发行方已取得全部专利许可。若仅批 AVC，必须换成成对 AVC AAR、对应新授权和新的发布审批。 | 当前 AAR 名和 `compliance/gstreamer-plugins.yml` 表明 AVC+HEVC；SDK `docs/compliance/THIRD_PARTY_COMPONENTS.md` 把专利事项列为单独审批。可参考[Via LA AVC 许可计划](https://www.via-la.com/licensing-programs/avc-h-264/)与[Access Advance HEVC 计划](https://accessadvance.com/hevc-advance-patent-pool-general-pool-terms/)。 |
| 发布阻断 | 审查 GStreamer 1.28.6 及 GLib 等开源组件的公告、完整许可证、相应源码、构建脚本和替换/重新链接可行性；把面向**签名商店 APK**的交付或协助方式写入发行方案及用户条款。12 份固定版本源码归档已下载至 SDK 校验缓存，并全部通过 SHA-256；正式交付包仍待真实审批后生成。 | SDK `scripts/cache-gstreamer-sources.ps1`、`docs/compliance/LGPL_RELINKING.md`；[GStreamer 许可说明](https://gstreamer.freedesktop.org/documentation/frequently-asked-questions/licensing.html)、[LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.dbk)。 |
| 工程已完成，法务待验 | App 整包依赖树已导出，设置页可查看 AndroidX/Kotlin/GStreamer 等公告与主要许可文本。法务需复核归属、NOTICE 完整性和最终 APK 的实际组件清单。历史 SPDX/CycloneDX 仍只覆盖两份 SDK AAR，新增 Camera Push AAR 必须补充组件清单/SBOM。 | `docs/release-evidence/V1.0.2-Beta/app-release-runtime-dependencies.txt`、`app/src/main/assets/legal/`。 |
| 发布阻断 | 对**实际应用商店渠道**形成 SDK `commercial-release-approval.json`：地区 `CN`、准确渠道标识、`AVC_HEVC`、SDK 版本/提交、未过期日期、LGPL 和 codec 专利两项 `APPROVED`、现有三份 AAR 的 SHA-256。当前 SDK 工作树含未提交变更且商业门禁尚未覆盖 Camera Push AAR；仍需完成审计、真实审批后运行发布门禁和打包核验。 | `docs/release-evidence/V1.0.2-Beta/README.md` 只有历史技术审计和 SBOM，不是商业审批；SDK `roboyiplayer/build.gradle`。文档示例的 `DIRECT_B2B` 不能直接代表应用商店。 |
| 工程已完成，正式包待验 | `verifyReleaseLicense` 已核对发行 token 的公钥 ID 与 SPKI 确实编入 Core AAR。正式 token 签发后还需以最终 APK 验证 SDK 初始化。 | `app/build.gradle`；SDK `RoboYiLicenseRuntime.kt`。 |
| 商业决策 | 明确离线许可的复制风险：RYL4 对包名、默认标签、功能和 codec 做检查，未见签名证书绑定、设备数限制或在线撤销。商店 APK 中 token 可被提取；仅靠包名/标签不能阻止他人重打包到其他设备。若合同和渠道控制不足，需设计签名证书绑定或其他授权机制，再重建 SDK。 | SDK `OfflineLicenseVerifier.kt:87-155`、`tools/license/README.md:73-90`。 |
| 发布阻断 | 配置独立的 Android 应用发布签名，核对别名/口令和证书备份，后续更新使用同一签名；签名材料与 RYL4 签发私钥分开保管。`release-signing.properties`、`release-license.properties`、`release-policy.properties` 留在 Git 外。生成正式 APK 后检查签名、默认标签、包名、合并权限及 SDK 实际加载。 | `app/build.gradle:6-26,62-79,162-174`；现无可用正式配置。 |

当前 AAR SHA-256：Core `53df4f2eaefb65c4e9a9c511838e0e97259ad1181ba4d45c71139882567447e8`；Transport `7624b7a819051bfc27a3e08c3eb923efc62ff016135b3030beba608cfa889660`；Camera Push `fffccba1363140e279e17b70efb5c07371540d91df03d1c14a2ae8f6851aeb35`。审批必须绑定最终交付物。**如重建任一 AAR，按用户要求同步递增其文件名版本号，并更新 App 依赖、哈希、SBOM、审批和授权签发记录。**

## 四、最小交付顺序

1. 运营主体确定商店、法定主体、政策域名、隐私邮箱、目标地区和是否允许 H.265。
2. 工程已修正固定邮箱、政策变更提示、水印图片删除及本地数据清除；用最终二进制完成真机抓包、日志、权限和功能清单。
3. 运营主体发布政策，法务完成 codec 与 LGPL 审批，并准备可交付的相应源码/重链接材料。
4. SDK 冻结干净提交，审核与 AAR 哈希绑定；受控签发 RYL4，复核 App 签名与公钥一致性。
5. 生成签名 release APK，复核应用启动、授权、数据流、隐私入口和所有商店申报内容一致后提交。

此复核没有使用或输出 `E:\roboyi-keys` 中的私钥和口令。正式 RYL4 签发密钥与 APK 签名密钥须由授权人员在受控环境分别操作。
