package com.roboyi.rtspdemo.domain

/**
 * 与 Android/播放器 SDK 无关的播放规则。
 * 发起连接不等于播放成功：只有收到有效视频帧后才写入历史。
 */
class PlaybackWorkflow(private val history: ConnectionHistoryRepository) {
    private var pendingUrl: String? = null

    fun onConnectRequested(url: String) {
        pendingUrl = url
    }

    fun onStopped() {
        pendingUrl = null
    }

    /** 返回是否新增历史，供界面决定是否刷新列表。 */
    fun onVideoReady(): Boolean {
        val url = pendingUrl ?: return false
        history.record(url)
        pendingUrl = null
        return true
    }
}
