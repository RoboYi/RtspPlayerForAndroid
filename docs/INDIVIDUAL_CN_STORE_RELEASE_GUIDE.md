# 个人开发者国内安卓商店上架指引

> 2026-10-11 更新：本文为历史上架检查资料。当前 Demo 已移除全部 Gradle 发布门禁，发布配置不影响试用构建；应用为 com.roboyi.rtspplayer / RtspPlayer / 1.0.5。历史版本、哈希和阻断项不代表当前状态，旧门禁命令不再适用。请使用 [当前发布说明](RELEASING.md)。

更新时间：2026-09-21
适用工程：com.roboyi.rtspdemo / RtspPlayer / versionCode 3 / versionName 1.0.2-Beta

## 1. 审核结论

个人可以作为 APP 主办者和隐私运营者，但实名账号、域名实名、APP 备案、软件著作权、隐私政策和权利授权必须落到同一个人。个人发布不会免除隐私、开源许可证、SDK 授权或音视频专利义务，相关责任由个人承担。

当前工程不能提交正式审核，阻断项如下：

1. 包名仍含 demo，版本名仍含 Beta，长期应用身份未冻结。
2. 隐私政策还是占位草稿，没有个人姓名、有效邮箱和公开 HTTPS 地址。
3. 没有 APP 备案，应用内也没有备案编号及查询链接。
4. 没有登记在个人名下的软件著作权或商店认可的 APP 电子版权证书。
5. 若 App、RoboYiPlayer SDK、品牌或图标不属于个人，需要权利人书面授权。
6. H.264/H.265 商店分发权利尚无书面结论。
7. 正式 RYL4 尚未签发，APK 发布证书别名、口令和归属尚未核实。
8. GStreamer 公告和对应源码已准备，但最终签名 APK 的替换协助方案及正式交付 ZIP 尚未完成发行确认。
9. 没有商店素材、审核用 RTSP 测试流、最终包抓包记录及商店扫描报告。

建议首版采用免费、无广告、无付费、无账号体系的工具类模式。

## 2. 个人主体可选渠道

| 渠道 | 现行情况 | 操作建议 |
| --- | --- | --- |
| 华为应用市场 | 官方支持个人开发者实名认证和应用上架。 | 作为首发渠道，在 AppGallery Connect 创建应用。 |
| OPPO 软件商店 | OPPO 已宣布向个人开发者开放应用上传，现行协议也包含自然人；部分旧操作文档仍用企业措辞。 | 完成个人认证后，先在控制台确认移动应用入口及个人版权、ICP 字段，保留客服答复。 |
| 小米应用商店 | 截至 2026-09-08，官方暂不收录清单明确包含“个人开发者身份发布的 APP”。 | 个人主体当前不提交小米。以后若改经营主体，需重做账号、备案、软著和授权对齐。 |
| vivo、应用宝等 | 公开官方页面不足以确认当前个人账号的普通 Android 应用发布口径。 | 在个人实名控制台发工单，确认是否接受个人名下软著和个人 APP 备案后再排期。 |

提交当天还应复核平台政策。参考：[华为上架流程](https://developer.huawei.com/consumer/cn/appgallery)、[OPPO 开放平台协议](https://open.oppomobile.com/wiki/index)、[OPPO 个人开发者公告](https://www.oppo.com/cn/newsroom/press/539/)、[小米暂不收录类应用](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1354)。

## 3. 先冻结应用身份

| 项目 | 当前值 | 建议 |
| --- | --- | --- |
| 安装名称 | RtspPlayer | 可保留或改中文名，但备案、软著、APK 和商店页面须一致。 |
| applicationId | com.roboyi.rtspdemo | 首次公开发布前评估改成不含 demo 的长期包名，最好由个人实名域名派生。 |
| 版本 | 3 / 1.0.2-Beta | 正式首发建议去掉 Beta。 |
| 签名容器 | E:\roboyi-keys\roboyi_rtsp_apkstore | 核实属于发布个人，确认别名和口令，制作两份加密备份。 |
| 著作权人 | 未确定 | 应为账号实名认证姓名，或提供商店接受的授权链。 |
| 隐私运营者 | 未确定 | 填身份证真实姓名，不使用不存在的公司或工作室名。 |

包名、默认标签或签名改变后，要同步更新 APP 备案、商店记录和 RYL4。包名改变还会导致旧版数据不能直接升级迁移。

## 4. 个人发布权证明

### 4.1 软件著作权

优先申请登记在发布者本人名下的《计算机软件著作权登记证书》。通常准备：

1. 软件著作权登记申请表。
2. 本人身份证明。
3. 源程序前、后各连续 30 页；总计不足 60 页交全部；通常每页不少于 50 行。
4. 用户手册、设计说明或操作说明前、后各连续 30 页；不足 60 页交全部；通常每页不少于 30 行。
5. 委托、合作、职务开发或受让形成的代码，提交合同、任务书或权属证明。

使用最终软件全称、简称和版本。只提交个人有权登记的 App 代码和自有文档，不把 AndroidX、GStreamer 等第三方源码申报为本人原创。依据：[国家版权局《计算机软件著作权登记办法》](https://www.ncac.gov.cn/xxfb/flfg/bmgz/202410/t20241015_869486.html)。

如改用 APP 电子版权认证证书，先让目标商店确认该证书和个人主体均被接受。

### 4.2 App、SDK 和品牌授权

保留以下书面材料：

- App 为个人独立开发的声明，或其他作者给个人的转让/许可文件。
- RoboYi 名称、图标或商标不属于个人时，由权利人授权个人使用。
- RoboYiPlayer 权利人给个人的分发授权，写明姓名、应用名、包名、中国大陆、目标商店、播放/录像/截图、AVC/HEVC、AAR 版本及哈希。
- 正式 RYL4 只是运行时许可，不能代替软著、品牌授权和 SDK 再分发合同。
- 如果代码属于职务成果，先由任职公司确认权属并授权或转让，不能仅凭代码在个人电脑上就按个人作品申报。

### 4.3 Codec 与 LGPL

当前 AAR 为 AVC+HEVC。应由熟悉音视频专利的律师或许可方对“中国大陆、Android 商店、个人免费分发、解码、录像/截图”作书面判断。若只允许 AVC，需要更换成对 AVC AAR、重新签发 RYL4，并更新 AAR 文件名版本、哈希、SBOM 和审批。

GStreamer 1.28.6 的许可证、12 份对应源码和重链接说明位于：

- E:\RoboYi_Code\RtspPlayer_SDK\roboyigstreamer\src\main\legal
- E:\RoboYi_Code\RtspPlayer_SDK\.codex-tmp\roboyiplayer-compliance-cache
- E:\RoboYi_Code\RtspPlayer_SDK\docs\compliance\LGPL_RELINKING.md

个人作为 APK 分发者，需要确定用户如何取得对应源码，以及用户替换 LGPL 库后如何获得重新签名或安装协助。

## 5. 个人域名和 APP 备案

该应用包含 RTSP/RTSPS、ONVIF、HTTP/HTTPS 网络功能，不按纯离线单机应用处理。工信部登记表允许个人主体，并要求 APP 名称、图标、域名、包名、公钥、证书 MD5、运行平台和 SDK 信息。依据：[工信部 APP 备案通知](https://www.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2023/art_920db564162e4312916a01bed6540ad8.html)、[备案登记表](https://xzca.miit.gov.cn/bsfw/bszn/art/2024/art_5d3cc7f452c04fdd97b6881a20b656f9.html)。

### 5.1 备案准备

1. 用本人身份实名认证域名。
2. 准备中国大陆境内、符合备案条件的云资源，通过实际接入商提交。
3. 在该域名发布无需登录的 HTTPS 隐私政策。
4. 用最终包名和最终发布签名生成 release 候选 APK；不能使用 debug 证书备案。
5. 名称、图标、包名、个人姓名和域名所有人信息保持一致。

### 5.2 填报内容

- 主办者：真实姓名、身份证、手机号、邮箱、通信地址和真实性核验。
- APP：最终名称、图标、Android 平台、applicationId。
- 特征：发布证书公钥和证书 MD5。
- 域名和资源：本人实名域名及实际云资源。
- SDK：RoboYiPlayer、GStreamer 及其用途。厂商列表中没有时按接入商要求处理，不选择无关厂商。
- 服务描述：RTSP/ONVIF 工具，不提供公开视频、直播平台、UGC 社区或开发者云存储。
- 及时完成工信部短信核验；材料齐全后管局审核最长可到 20 个工作日。

字段清单可参考[腾讯云 APP 备案 FAQ](https://cloud.tencent.com/document/product/243/97691)，证书特征提取可参考[阿里云指引](https://help.aliyun.com/zh/icp-filing/basic-icp-service/user-guide/fill-in-app-feature-information)，实际以接入商为准。

### 5.3 备案通过后

在 App 的“设置/关于”显著位置展示真实编号，例如“粤ICP备XXXXXXXX号-XA”，并链接 https://beian.miit.gov.cn/ 。当前工程没有此入口，取得编号后需要修改代码并用同一签名重建。不能提前显示虚构编号。

## 6. 个人版隐私政策

以 docs/PRIVACY_POLICY_DRAFT.md 为基础填写。公开页面至少包括：

1. 个人运营者身份证真实姓名和有效邮箱。公开页面不要写身份证号码；完整住址按备案和商店要求提交。
2. 生效日期、更新日期和政策版本。
3. RTSP/RTSPS 地址可能含账号、密码或令牌，普通 RTSP 可能明文传输。
4. ONVIF 搜索会处理局域网 IP、厂商、型号、固件、序列号、服务地址和认证信息。
5. 当前空凭据搜索经确认后可能尝试 admin/admin。正式版建议删除默认尝试；若保留，需醒目披露。
6. 截图、录像、水印图片和文字的本地保存、第三方打开、保留和删除。
7. 上次尝试 URL 和最多 10 条成功历史使用 Android Keystore 加密并关闭备份。
8. RoboYiPlayer、GStreamer 及组件提供方、用途、数据和网络目的地。
9. 最终 APK 抓包验证无广告、统计和开发者云上传。
10. 查询、更正、删除和撤回方式；本应用没有用户账号时写明无账号注销事项。
11. 不面向未满 14 周岁儿童；如果实际允许儿童使用，需要监护人同意流程。
12. 用户只能连接有权访问的摄像机，并自行处理画面中的他人信息。

当前代码已有首次告知、拒绝退出、政策版本更新重提示、本地历史加密、关闭备份、本地数据删除和撤回、开源许可入口。发布姓名、邮箱、政策 URL 仍为空。

### 权限说明

| 权限 | 用途 |
| --- | --- |
| INTERNET | 连接用户指定的 RTSP/RTSPS 设备、用户触发的 ONVIF/HTTP 接口，并打开隐私政策。 |
| ACCESS_NETWORK_STATE | 判断网络是否可用于连接和播放。 |
| ACCESS_WIFI_STATE | 辅助局域网发现和网络状态判断，不用于定位或画像。 |
| CHANGE_WIFI_MULTICAST_STATE | 用户点击搜索后接收 ONVIF 组播响应。 |
| WAKE_LOCK | 播放期间避免相关组件立即休眠。 |

当前 APK 未声明相机、麦克风、定位、通讯录、短信或广泛存储读取权限。更换 AAR 后需重新检查最终 Manifest。

## 7. 工程待办

1. 决定是否修改 com.roboyi.rtspdemo、RtspPlayer 和 1.0.2-Beta。
2. 包名或标签变化后更新构建门禁并重新签发 RYL4。
3. 评估删除 admin/admin 自动尝试，降低未授权访问和安全扫描风险。
4. 取得备案号后增加备案号展示和查询链接。
5. 发布个人版政策并配置 release-policy.properties。
6. 完成 SDK 个人授权、codec 决定和 LGPL 发行决定，生成合规归档。
7. 签发非 demo RYL4，填写 release-license.properties。
8. 核实 APK 签名证书，填写 release-signing.properties。
9. 准备审核专用 RTSP 循环测试流、临时账号和最小权限，不暴露家庭或办公摄像机。
10. 用最终 APK 测试拒绝/同意、政策、撤回删除、ONVIF、RTSP/RTSPS、录像、截图、水印、无网和错误凭据。
11. 抓包确认无未披露服务器，检查 logcat 不含完整 URL、账号、密码或设备原始响应。
12. 完成目标平台隐私、安全和兼容性扫描。

## 8. 构建与核验

~~~powershell
Copy-Item release-policy.properties.example release-policy.properties

# 交互输入口令，不把口令写进命令或文档
keytool -list -v -storetype PKCS12 -keystore E:\roboyi-keys\roboyi_rtsp_apkstore

$env:ROBOYI_RELEASE_STORE_PASSWORD = Read-Host 'APK store password'
$env:ROBOYI_RELEASE_KEY_PASSWORD = Read-Host 'APK key password'
.\gradlew.bat :app:assembleRelease --offline --console=plain

$apk = 'app\build\outputs\apk\release\RoboYiPlayer-1.0.2-Beta.apk'
Get-FileHash -LiteralPath $apk -Algorithm SHA256
& 'F:\Program Files\Android\SDK\build-tools\37.0.0\apksigner.bat' verify --print-certs $apk
& 'F:\Program Files\Android\SDK\build-tools\37.0.0\aapt.exe' dump badging $apk
& 'F:\Program Files\Android\SDK\build-tools\37.0.0\aapt.exe' dump permissions $apk
~~~

备案时优先上传 APK 给接入商工具自动识别包名、公钥和证书 MD5。口令、私钥、身份证照片和 RYL4 token 不提交 Git、聊天或普通工单。

## 9. 商店提交材料

- 个人实名认证、手机号和联系邮箱。
- APP 备案证明和应用内备案号截图。
- 个人名下软著/APP 电子版权证书或完整授权链。
- SDK、品牌、图标授权。
- 隐私政策 URL、政策快照和版本记录。
- 权限说明、SDK/开源组件表、数据处理清单。
- 最终 APK、SHA-256、证书 SHA-256/MD5、包名和版本。
- 512×512 PNG 商店图标。当前工程图标为 1254×1254 普通 PNG，需要另做平台素材并确保与安装图标一致。
- 3–5 张 1080×1920 竖屏截图，不含其他品牌标识和真实个人监控画面。
- 一句话简介、20 字以上介绍、5 字以上版本说明、年龄分级和客服说明。
- 审核专用 RTSP 地址、操作步骤和功能视频。
- GStreamer 公告、许可证、对应源码、SBOM 和重链接说明。
- 测试记录、隐私扫描、网络抓包和漏洞修复记录。

推荐商店介绍：

> RtspPlayer 是个人开发者提供的 RTSP/RTSPS 与 ONVIF 摄像机连接工具。用户自行输入或搜索其有权访问的设备。本应用不提供公开视频、直播平台、云端账号、广告或开发者云存储；录像、截图和连接历史保存在用户设备中并可删除。

## 10. 执行顺序

1. 确定个人实名、首发商店、最终名称、包名、版本和免费模式。
2. 理清 App、SDK、品牌和图标权属，申请个人名下软著。
3. 用实名域名和大陆云资源发布隐私政策。
4. 固定 APK 签名和正式包特征，办理 APP 备案。
5. 完成 codec 与 LGPL 发行决定，签发 RYL4。
6. 增加真实备案号，生成最终签名 APK。
7. 做抓包、隐私、安全、兼容性和审核场景测试。
8. 先提交华为；OPPO 个人控制台确认资质入口后提交。
9. 小米个人主体暂不提交；其他商店逐一确认。
