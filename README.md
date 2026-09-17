# RoboYi RTSP SDK · Android 集成示例

本项目是 **RoboYi RTSP SDK 的独立 Android demo**。开发者可以先运行 demo 体验网络摄像机搜索、RTSP 播放、截图和录像，再按照下方示例把 SDK 接入自己的应用。播放器与设备发现的实现位于 SDK AAR 中；本项目负责界面、交互和调用示例。

**申请 SDK、试用授权或商业授权：请联系 [RoboYi@126.com](mailto:RoboYi@126.com)。**

## 先看能做什么

| 场景 | 本 demo 的用法 |
| --- | --- |
| 查找摄像机 | 自动搜索局域网 ONVIF 设备
| 查看设备信息 | 展示型号、固件、序列号、服务地址、Scopes、设备原始响应和可用码流 |
| 获取 RTSP 地址 | 区分设备通过 ONVIF 返回的地址与根据常见路径生成的**待验证候选地址** |
| 播放视频 | 播放 RTSP，切换解码模式和画面比例，点击视频上的按钮进入横屏全屏 |
| 保存画面 | 截图、MP4 录像、录像暂停与继续，以及文字、图片和全画幅 Bitmap 绘图水印 |
| 管理文件 | 查看、打开和删除应用保存的截图与录像 |

SDK 支持 H.264/H.265 RTSP 拉流，使用 Android MediaCodec 解码。本 demo 展示的是单路播放器接入；SDK 的其他模块和功能以交付的 SDK 包及授权范围为准。

## 运行 demo

1. 使用 Android Studio 打开本目录，配置 Android SDK 35，等待 Gradle 同步完成。
2. 连接 Android 7.0（API 24）及以上的设备；当前工程打包 `arm64-v8a` 和 `armeabi-v7a`。
3. 运行 `app`。启动后确认页面显示“永久授权”。demo 始终使用 `app/build.gradle` 中的 `BuildConfig.ROBOYI_LICENSE`；该许可证**仅匹配此 demo**，页面不提供导入授权入口。
4. 输入可访问的 RTSP 地址并点击“连接”，例如 `rtsp://192.168.1.100:554/Streaming/Channels/101`。实际路径、端口及账号密码以摄像机配置为准。

也可以在项目根目录使用 Windows PowerShell 构建示例：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。电脑构建成功只说明工程能够打包，实际搜索、拉流和录像仍需在连接摄像机的 Android 设备上体验。

### 在 demo 中搜索设备

1. 让手机与摄像机所在网络互通，进入“ONVIF”页面，按需填写设备账号和密码，然后点击“搜索”。**搜索不需要填写 IP。**
2. 点击搜索结果查看设备详情。优先选择标有“设备返回”的 RTSP 地址；标有“候选地址”的路径尚未由设备确认，需要连接验证。
3. 选择码流后返回播放区，点击“连接”。若设备需要 RTSP 认证，请使用设备提供的账号密码。

海康 HTTP 探测只在 ONVIF 未发现设备时作为兜底。搜索时若用户名和密码都留空，会尝试 `admin/admin`；只要填写了其中任意一项，就原样使用输入的凭据。它依赖设备网络可达以及相应接口开放，不能保证发现所有型号，也不集成海康 SDK。

## 接入自己的 Android 应用

### 1. 添加 SDK AAR

本 demo 使用两个本地 AAR，当前 SDK 版本均为 `V1.0.1-Beta`：

```text
app/libs/roboyiplayer-core-avc-hevc-release-discovery-V1.0.1-Beta.aar
app/libs/roboyiplayer-transport-gstreamer-avc-hevc-release-V1.0.1-Beta.aar
```

Core AAR 提供播放器、录制、截图和设备发现 API；Transport AAR 提供 RTSP 传输运行时及原生库。**两个 AAR 的 codec scope 必须一致**，不能混用 `AVC` 与 `AVC_HEVC`。拿到正式交付包后，以交付的文件名和授权范围替换示例文件。

在应用模块的 `build.gradle` 中添加依赖；以下名称对应本 demo 当前文件：

```gradle
dependencies {
    implementation files('libs/roboyiplayer-core-avc-hevc-release-discovery-V1.0.1-Beta.aar')
    implementation files('libs/roboyiplayer-transport-gstreamer-avc-hevc-release-V1.0.1-Beta.aar')
}
```

示例工程的 `minSdk` 为 24、`compileSdk` 为 35。应用至少需要网络权限；若摄像机使用明文 RTSP/HTTP，请允许明文流量：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<application android:usesCleartextTraffic="true">
    <!-- 保留应用原有的 Activity、Provider 等声明 -->
</application>
```

### 2. 先初始化授权，再创建播放器

取得与自己应用匹配的 RYL4 许可证后，在使用媒体功能前初始化 SDK：

```kotlin
import com.roboyi.rtsp.RoboYiPlayer

val info = RoboYiPlayer.initialize(applicationContext, licenseKey)
// 可以展示 info.codecScope 和 info.features，便于确认已启用的能力。
```

`licenseKey` 由授权方提供。demo 的 `BuildConfig.ROBOYI_LICENSE` 仅适用于示例应用名称、包名和功能范围，复制到另一个应用不会自动获得授权。

### 3. 嵌入播放器并播放

在 Activity 布局中准备一个容器：

```xml
<FrameLayout
    android:id="@+id/player_host"
    android:layout_width="match_parent"
    android:layout_height="240dp" />
```

授权初始化成功后创建 `RoboYiPlayerFragment`：

```kotlin
import com.roboyi.rtsp.DecoderMode
import com.roboyi.rtsp.RoboYiPlayerFragment

val player = RoboYiPlayerFragment.newInstance()
supportFragmentManager.beginTransaction()
    .replace(R.id.player_host, player)
    .commitNow()

player.setDecoderMode(DecoderMode.AUTO)
player.setFillMode(false) // false：按比例显示；true：填满窗口
player.play(rtspUrl)

// 停止播放时调用
player.stop()
```

播放、停止和 Fragment 生命周期的完整页面示例见 [MainActivity.kt](app/src/main/java/com/roboyi/rtspdemo/MainActivity.kt)。

### 4. 截图与录像

连接并开始播放后，可调用：

```kotlin
import java.io.File

val dir = checkNotNull(getExternalFilesDir(null))
player.captureSurfaceScreenshot(File(dir, "snapshot.jpg")) { ok, error ->
    // 回调中根据 ok / error 更新界面；必要时切回主线程。
}

val recording = File(dir, "recording.mp4")
val started = player.startRecording(recording)
// 录像开始后，分别在“暂停”“继续”“停止”按钮的点击事件中调用：
// player.pauseRecording()
// player.resumeRecording()
// player.stopAllRecordings()
```

demo 的“暂停录像”按钮仅在录像过程中显示。`captureSurfaceScreenshot(...)` 依赖 Android 8.0（API 26）及以上的 PixelCopy；低版本设备需要采用其他截图方案。

### 5. 录像水印：在任意位置叠加文字与图形

录像水印是 SDK 的主要能力之一。文字、徽标、透明 PNG、图章或业务代码绘制的图形可以同时叠加在视频画面上，并**写入录制后的 MP4 画面**。它们不是只显示在播放器页面上的临时控件。适合设备标识、巡检说明、时间信息、品牌徽标和证据标记等场景。

**先在 demo 中体验：**连接并播放视频，点击“OSD / 水印”，填写文字，选择字号、颜色和四角位置；如需图片，点击“选择图片”。勾选“应用到录制”并点击“应用”，然后点击“录制”。默认还可分别选择是否将水印用于预览和截图；未设置文字与图片时，demo 会以应用名称作为默认水印。

若要体验任意位置的手绘图层，等待视频分辨率显示后点击播放区的 **“Bitmap 绘图”**。demo 会创建一张与当前视频宽高完全一致的透明 Bitmap：在画布上拖动即可手绘，输入文字后点“放置文字”，再点画布选择文字位置；“示例图形”展示如何通过 `Canvas` 绘制形状。点击“应用图层”后，它会作为覆盖整帧的 `OverlayItem.BitmapLayer(x = 0f, y = 0f, width = 1f, height = 1f)` 用于预览和录像，点击“录制”即可保存到 MP4。普通 OSD 文字和图片会继续显示在 Bitmap 图层上方；“移除图层”仅移除手绘内容。编辑器的深色背景只是为了看清透明图层，不会写入视频。示例画布保存在当前运行会话中；连接另一条码流或重启应用后，需按视频尺寸重新绘制。

**在自己的应用中精确定位：**一段文字和一张图片可使用 `WatermarkSpec` 的 `textX/textY`、`overlayX/overlayY`；多个独立元素可放入 `overlayItems`。这些位置参数都是相对于视频画面的比例坐标，`(0f, 0f)` 为左上角，`(1f, 1f)` 为右下角；图片或 Bitmap 图层的 `width`、`height` 也是画面宽高比例。例如 `x = 0.75f` 表示从画面左侧起 75% 的位置。坐标随视频分辨率缩放，无需按手机屏幕像素重新计算；超出画面的图层会限制在画面内。图层的 `alpha` 控制透明度，`zIndex` 越大越靠前。

只有一段文字和一张图片时，可直接设置两者各自的位置，作为最短接入方式：

```kotlin
import com.roboyi.rtsp.WatermarkSpec
import java.io.File

val spec = WatermarkSpec(
    text = "设备 01 · 巡检中",
    textX = 0.05f, textY = 0.82f,
    overlayImagePath = File(filesDir, "logo.png").absolutePath,
    overlayX = 0.80f, overlayY = 0.05f,
    overlayScale = 0.16f
)
val outputFile = File(checkNotNull(getExternalFilesDir(null)), "watermarked.mp4")
player.startRecording(file = outputFile, spec = spec)
```

请先将图片复制到指定路径。需要多个独立文字或任意图形时，再使用 `overlayItems`：

下面同时叠加左下方的文字、右上方的透明图片和左上方由代码绘制的圆形图标：

```kotlin
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.roboyi.rtsp.OverlayItem
import com.roboyi.rtsp.WatermarkSpec
import java.io.File

val circle = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
Canvas(circle).drawCircle(
    32f, 32f, 28f,
    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
)
val logoFile = File(filesDir, "logo.png") // 先把透明 PNG 复制到这个可读取的路径

val watermark = WatermarkSpec(
    text = "",
    showText = false,
    showOverlay = false,
    overlayItems = listOf(
        OverlayItem.Text(
            id = "device_label",
            x = 0.05f, y = 0.82f,
            text = "设备 01 · 巡检中",
            textSizeSp = 24f,
            textColor = Color.WHITE,
            shadowColor = Color.BLACK,
            backgroundColor = Color.argb(140, 0, 0, 0),
            includeTimestamp = true,
            zIndex = 1
        ),
        OverlayItem.Image(
            id = "brand_logo",
            x = 0.80f, y = 0.05f,
            path = logoFile.absolutePath,
            width = 0.16f, height = 0.10f,
            alpha = 0.9f,
            zIndex = 2
        ),
        OverlayItem.BitmapLayer(
            id = "status_icon",
            x = 0.03f, y = 0.05f,
            bitmap = circle,
            width = 0.04f, height = 0.07f,
            zIndex = 3
        )
    )
)

val output = File(checkNotNull(getExternalFilesDir(null)), "watermarked.mp4")
val started = player.startRecording(file = output, spec = watermark)
// 录像结束时调用 player.stopAllRecordings()；之后可打开 output 查看效果。
```

`includeTimestamp = true` 会在文字后追加时间；不需要时间时设为 `false`。图片文件必须在录制期间可读取，`BitmapLayer` 的 Bitmap 在使用期间不能提前回收。使用 `overlayItems` 时，它会作为完整的图层列表；若还想显示普通的 `text` 或 `overlayImagePath`，请一并转成 `OverlayItem` 加入列表。

录制过程中可调用 `player.updateWatermark(新配置)` 更新水印，或使用 `pauseRecording()`、`resumeRecording()` 暂停与继续录像。水印录制包含解码、图层合成和重新编码，设备负载通常高于不加水印的原始录制。水印截图可使用 `captureSurfaceScreenshotWithWatermark(...)`；demo 的基础配置转换见 [RoboYiWatermarkMapper.kt](app/src/main/java/com/roboyi/rtspdemo/RoboYiWatermarkMapper.kt)。

### 6. 自动搜索 ONVIF 设备

搜索与详情解析都由 SDK 完成，调用方无需实现设备扫描：

```kotlin
import com.roboyi.rtsp.CameraDiscovery
import com.roboyi.rtsp.OnvifCredentials

val credentials = OnvifCredentials(username = "admin", password = "设备密码")
CameraDiscovery.search(applicationContext, credentials) { devices ->
    // 这里以第一台设备为例；正式界面可先展示列表，再按用户点击解析。
    val device = devices.firstOrNull() ?: return@search
    CameraDiscovery.inspect(device, credentials) { details ->
        // details.device：设备信息和原始响应
        // details.streams：RTSP 地址；returnedByDevice 表示设备确实返回了该地址
        // details.warnings：解析提示
        runOnUiThread { /* 更新设备详情页面 */ }
    }
}
```

设备搜索和网络请求的回调需要按 Android 线程规则处理，更新 View 时切回主线程。demo 的调用适配见 [OnvifDiscovery.kt](app/src/main/java/com/roboyi/rtspdemo/OnvifDiscovery.kt)。当设备响应超时、凭据错误或未提供 Media 服务时，详情可能只有待验证的常见 RTSP 路径。

## 授权与购买

本项目中的永久演示许可证只匹配 `RtspPlayer` / `com.roboyi.rtspdemo`，适用于学习本 demo 的接入方式。将 SDK 用于自己的应用、修改包名或应用名称、增加授权功能时，需要取得与目标应用匹配的许可证及对应 SDK 交付包。

**SDK 试用、采购、商业授权与技术对接，请发送邮件至 [RoboYi@126.com](mailto:RoboYi@126.com)。** 建议在邮件中提供应用名称、包名、目标设备与 Android 版本，以及需要的编解码范围和功能（播放、录像、截图、转推等），便于确认交付内容。

许可证签名密钥由授权方管理。接入方只需使用交付的 AAR 和许可证。

## 商业使用合规说明

本 demo 展示 SDK 的接入方式，内置的 AAR 与永久演示许可证不代表客户产品已完成商业授权或发布审批。将 SDK 集成到对外销售、交付或部署的应用前，请按实际交付版本、应用、销售地区与渠道核对：

| 项目 | 发布前要确认 |
| --- | --- |
| SDK 授权 | 取得适用于目标应用的正式交付包和 RYL4 许可证，核对默认应用名称、`applicationId`、期限、功能范围与 `AVC` / `AVC_HEVC` 范围；Core 与 Transport AAR 必须采用相同范围。demo 授权码不能移用到客户应用。 |
| 第三方组件 | Transport AAR 包含 GStreamer 及其他第三方组件。随产品提供适用的许可文本、版权声明、组件清单、对应源码获取方式，以及替换或重新链接说明；修改组件或打包方式后重新核对。具体材料见交付包中的 `THIRD_PARTY_NOTICES.md`、`GSTREAMER-LGPL-2.1-or-later.txt` 和 `LGPL_RELINKING.md`。 |
| 编解码权益 | Android `MediaCodec` 的使用不自动解决 H.264/H.265 在目标地区、产品类型及销售渠道的商业使用问题；按实际 codec 范围完成权利审查。 |
| 发布记录 | 保存合同及许可证记录、交付版本、两个 AAR 的 SHA-256、组件清单或 SBOM、开源义务履行材料及发布审批记录。更换制品、依赖或销售场景时重新核对。 |
| 设备和录像数据 | 只接入获准访问的摄像机；保护设备账号、RTSP URL 中的凭据、截图和录像，并按产品部署地的要求确定访问、留存、删除与告知方式。 |

SDK 供应方的具体发布流程见 SDK 工程的 `docs/compliance/COMPLIANCE_RELEASE_CHECKLIST.md`。关于第三方库许可，可参阅 [GStreamer 官方许可说明](https://gstreamer.freedesktop.org/documentation/frequently-asked-questions/licensing.html)与 [GNU LGPL 2.1 许可证](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)。具体商业范围和义务以合同、实际交付制品及对应审查结论为准；商业授权请联系 [RoboYi@126.com](mailto:RoboYi@126.com)。

## 常见问题

| 现象 | 先检查什么 |
| --- | --- |
| 显示“未授权”或授权失败 | 许可证是否匹配应用名称、包名、codec scope 和所需功能；AAR 是否为对应交付版本 |
| 搜不到 ONVIF 设备 | 手机和设备是否网络互通；局域网是否允许组播；设备是否开启 ONVIF；账号密码是否正确 |
| 只有“候选地址” | 设备未成功返回 `GetStreamUri`；检查 ONVIF 服务、Media 服务和网络响应，再验证候选 RTSP 路径 |
| RTSP 无法播放 | 检查 URL、账号密码、端口、网络连通性及摄像机使用的 H.264/H.265 码流 |
| 录像或截图不可用 | 确认视频已经播放、许可证包含对应功能，并查看页面给出的错误信息 |

本 demo 的截图和录像默认存放在应用专属目录 `Android/data/com.roboyi.rtspdemo/files/captures`。若 RTSP URL 内含账号密码，分享日志或截图前请注意遮盖凭据。
