package com.roboyi.rtspdemo

import android.content.Context
import com.roboyi.rtsp.CameraDetails
import com.roboyi.rtsp.CameraDevice
import com.roboyi.rtsp.CameraDiscovery
import com.roboyi.rtsp.OnvifCredentials

/** 网络发现与设备信息获取均由 SDK 完成；demo 仅适配回调。 */
internal class OnvifDiscovery(private val context: Context) {
    fun search(credentials: OnvifCredentials, onResult: (List<CameraDevice>) -> Unit) {
        // 兼容当前内置 AAR：SDK 的海康兜底搜索在两个输入都为空时使用 admin/admin。
        val searchCredentials = if (credentials.username.isEmpty() && credentials.password.isEmpty()) {
            OnvifCredentials(username = "admin", password = "admin")
        } else credentials
        CameraDiscovery.search(context, searchCredentials, onResult)
    }

    fun inspect(device: CameraDevice, credentials: OnvifCredentials, onResult: (CameraDetails) -> Unit) =
        CameraDiscovery.inspect(device, credentials, onResult)
}
