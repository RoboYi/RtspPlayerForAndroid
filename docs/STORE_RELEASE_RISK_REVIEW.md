# RtspPlayer 上架商业风险评估（2026-09-21）

目标渠道：国内安卓应用商店与 Google Play。评估对象是此仓库当前的 `com.roboyi.rtspplayer`、三份 `V1.0.4-Beta` AAR，以及提供的 SDK 源码目录。此文档记录工程证据与待取得的商业材料，不代替具体销售地区的法律审查。

## 发布结论

**当前版本仍不能作为商业版提交。** Core/Transport 的来源、成对 SDK 审计和三份 arm64 原生库的 16 KB ELF 对齐检查已通过，Camera Push AAR 与 app debug 构建通过。Camera Push AAR 尚未纳入正式商业发布审计/SBOM；release 构建仍由正式授权、真实运营主体/公开隐私政策 URL 和签名配置门禁拦截，商业 codec 与 LGPL 分发审批及最终包检查也尚未完成。

| 等级 | 风险与证据 | 发布前动作 |
| --- | --- | --- |
| 高 | 旧版 transport AAR 的三份 arm64 库均为 4 KB ELF 对齐。新版使用 NDK 27 和专用链接参数重建后，三份库已通过 16 KB ELF 审计。Google Play 面向 API 35+ 的应用须支持 16 KB 页，自 **2027-02-01** 起不支持的更新无法发布。[官方说明](https://developer.android.com/guide/practices/page-sizes) | 在 16 KB 真机/模拟器上测试播放、搜索和录像；检查最终 AAB 的 `PAGE_ALIGNMENT_16K` 及商店生成 APK 的 ZIP 对齐。ELF 静态检查不能替代运行验证。 |
| 阻断 | 当前应用内置的 RYL4 `licenseId` 是 demo 授权；提供的密钥目录中现有 `.ryl` 也属于 demo 授权，虽可用提供的公开密钥验签，但仍不能作为商业授权。目录中有一个未带扩展名的签名容器候选文件，但没有可用的发布签名配置和口令核验结果。SDK 私钥也位于提供的本机密钥目录。 | 按 SDK `COMPLIANCE_RELEASE_CHECKLIST.md` 对确切包名、应用标签、功能、codec、地区与渠道签发正式许可，保留合同、签发记录与双人核验。正式 token 放在不入库的 `release-license.properties`，release 构建会验证签名并拒绝 demo token；核验签名容器、别名和口令后配置 APK/AAB 签名。SDK 私钥应按 SDK 文档放在受控离线或加密存储中，不得进入 app、Git 或构建日志。 |
| 阻断 | 旧版 transport AAR 缺少 GStreamer 来源记录。新版已嵌入校验哈希的官方 SDK 来源记录，成对 AAR 审计及 SPDX/CycloneDX SBOM 已生成。H.264/H.265 专利范围、GStreamer 对应源码交付与 LGPL 替换/重链接义务仍需具体商业审批。 | 按实际商店分发模式完成 codec 商业权益、LGPL 与消费者条款审查，准备对应源码/重链接材料及与新版 AAR 哈希绑定的批准记录。[GStreamer 许可说明](https://gstreamer.freedesktop.org/documentation/frequently-asked-questions/licensing.html) |
| 阻断 | App 现有的首次启动告知只是功能性数据说明，没有运营主体全称、完整第三方 SDK 数据流、政策生效日期及公开链接。Google Play 要求 Play Console 与 app 内均可访问完整隐私政策，并填写 Data safety。[Play 用户数据政策](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en-GB) | 由实际运营主体发布可公开访问的中/英文隐私政策，核实 SDK 实际联网/数据处理行为；在 `release-policy.properties` 填写真实主体和 HTTPS URL，release 构建会校验并在 app 内显示政策入口；按真实数据流填写 Play Data safety 与国内各渠道权限/SDK 声明。华为也要求逐一明示第三方 SDK。[华为上架说明](https://developer.huawei.com/consumer/cn/doc/hmscore-guides/app-release-0000001050123495) |
| 高 | 中国大陆渠道的 APP 备案适用性取决于实际运营主体与服务模式；本仓库没有备案信息。 | 由上架主体和目标渠道核实是否属于境内互联网信息服务及所需 APP 备案/资质，留存审核结论。[工信部通知](https://www.miit.gov.cn/jgsj/xgj/wjfb/art/2023/art_dd783a581c9644a4aee10afa582811db.html) |
| 高 | 当前 URL 历史原为明文 SharedPreferences，URL 可含用户名、密码或令牌；系统备份默认可能包含配置与录像。 | 已迁移到 Android Keystore AES-GCM，关闭云端及设备转移备份，清除历史同时清除上次 URL，关闭敏感输入自动填充与状态保存。历史列表只展示协议、主机和端口。升级前已有的系统备份不能由此版本追溯删除。 |
| 高 | 旧版截图/录像写入外部应用专属目录；Android 7–9 上，拥有存储读取权限的其他应用可能读到这些媒体。 | 新文件已改写应用内部目录，旧文件仍可在“本地文件”查看和删除；升级前遗留外部文件不会自动迁移或抹除，发布时应向升级用户说明。 |
| 高 | `android:usesCleartextTraffic="true"` 和用户输入的普通 RTSP/ONVIF HTTP 会向摄像机传输明文或弱保护流量。动态摄像机地址无法在 manifest 中列出固定白名单。 | 已在首次启动告知明文风险，并为默认 `admin/admin` 探测加入显式确认。正式产品应优先支持摄像机侧 RTSPS/HTTPS，在测试矩阵中确认认证方式与网络边界；商店数据申报应与实际行为一致。 |
| 中 | 旧版 SDK 日志直接记录过 RTSP 地址、SOAP 正文及原生 GStreamer 错误，可能泄露摄像机凭据。 | app 用户可见错误改为异常类型，原始设备响应只在 debug 包可见；新版 Core/Transport AAR 已从 SDK 源码重建，移除了已识别的敏感日志。上线前仍应在真机检查最终包日志。 |
| 中 | Google Play 自 2026-08-31 起要求手机新应用/更新面向 API 36；当前工程原为 API 35。[Play 目标 API 政策](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) | 工程已改为 target/compile 36，升级 AGP 到支持该 API 的版本；使用 API 36 和代表性旧设备做回归。Google Play 新应用使用 AAB。[Android App Bundle 说明](https://developer.android.com/guide/app-bundle) |
| 中 | 水印导入先前直接复制外部 `content://` 输入，可写入超大或无效文件，耗尽应用存储空间。 | 已限定 10 MB、4096×4096/1600 万像素，先暂存验证，再替换水印文件。 |
| 阻断 | App 新增 `CAMERA` 权限和设备摄像头 RTSP 推流，可把前置、后置或 Camera2 外接摄像头画面在局域网端口 8554 发布；现有隐私材料尚未按最终产品说明采集触发、接收方、局域网可访问范围及停止行为。 | 更新首次告知、公开隐私政策、国内权限用途与 Google Play Data safety；在目标网络验证端口暴露范围，并决定是否增加认证、绑定地址或显式局域网风险确认。 |

## 已增加的工程门禁与验证命令

```powershell
python tools/check_native_page_size.py app/libs/roboyiplayer-transport-gstreamer-avc-hevc-release-V1.0.4-Beta.aar
python -m unittest discover -s tools -p 'test_check_native_page_size.py'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:bundleRelease
```

第一条已通过，历史成对 SDK 审计与 SBOM 保存在 [`release-evidence/V1.0.2-Beta`](release-evidence/V1.0.2-Beta/README.md)，但必须为最终三份 V1.0.4 制品重新生成；debug APK 的 `zipalign -c -P 16 -v 4` 也通过。`bundleRelease` 已验证因缺少正式许可而失败，隐私政策和签名配置门禁分别也会拒绝空配置。政策草案在 `docs/PRIVACY_POLICY_DRAFT.md`，`release-policy.properties.example` 说明配置字段。2026-09-22 已在连接设备上验证 Camera2 后置预览、H.264 RTSP 启停与 `DESCRIBE 200 OK`；这不替代完整设备矩阵、长稳、UVC 和商店包验证。

## 发布材料清单

1. 签名 release APK（国内渠道）和签名 AAB（Google Play）；正式包名、应用名、版本号与 SDK 许可完全一致。
2. 运营主体、客服/隐私联系方式、公开隐私政策 URL、第三方 SDK 清单、Data safety 和国内商店隐私/权限声明；核实首次启动前 SDK 实际行为。
3. 三份 AAR 的 SHA-256、SBOM/组件清单、GStreamer 来源及对应源码、LGPL 重链接材料、codec 商业权益与正式 SDK 合同/审批记录。
4. 代表性机型兼容性记录，重点是 16 KB 内存页、Android 16、局域网发现、无网络、权限被拒绝、播放/录像长时间稳定性。
5. 按实际大陆分发业务核实 APP 备案和各具体应用商店的主体资质、软著或其他证明要求。

当前 AAR SHA-256：

- Core: `53DF4F2EAEFB65C4E9A9C511838E0E97259AD1181BA4D45C71139882567447E8`
- Transport: `7624B7A819051BFC27A3E08C3EB923EFC62FF016135B3030BEBA608CFA889660`
- Camera Push: `FFFCCBA1363140E279E17B70EFB5C07371540D91DF03D1C14A2AE8F6851AEB35`
