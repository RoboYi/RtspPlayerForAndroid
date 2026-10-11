package com.roboyi.rtspdemo

import android.content.Context
import com.roboyi.rtsp.CameraDetails
import com.roboyi.rtsp.CameraDevice
import com.roboyi.rtsp.CameraDiscovery
import com.roboyi.rtsp.OnvifCredentials
import com.roboyi.rtsp.CameraSearchHandle
import com.roboyi.rtsp.CameraSearchOptions
import com.roboyi.rtsp.CameraSearchUpdate

/** 网络发现与设备信息获取均由 SDK 完成；demo 仅适配回调。 */
internal class OnvifDiscovery(private val context: Context) {
    fun search(credentials: OnvifCredentials, options: CameraSearchOptions, onUpdate: (CameraSearchUpdate) -> Unit): CameraSearchHandle {
        val searchCredentials = if (credentials.username.isEmpty() && credentials.password.isEmpty()) {
            OnvifCredentials(username = "admin", password = "admin")
        } else credentials
        return CameraDiscovery.search(context, searchCredentials, options, onUpdate)
    }

    fun clearHistory() = CameraDiscovery.clearDiscoveryHistory()

    fun inspect(device: CameraDevice, credentials: OnvifCredentials, onResult: (CameraDetails) -> Unit) =
        CameraDiscovery.inspect(device, credentials, onResult)
}
