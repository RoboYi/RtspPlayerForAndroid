package com.roboyi.rtspdemo.domain

data class ConnectionEntry(val url: String, val connectedAtMs: Long)

/** 历史记录契约：业务层不关心偏好设置或其他持久化格式。 */
interface ConnectionHistoryRepository {
    fun entries(): List<ConnectionEntry>
    fun record(url: String)
    fun clear()
}

object RtspAddress {
    fun isSupported(url: String): Boolean =
        url.startsWith("rtsp://", ignoreCase = true) ||
            url.startsWith("rtsps://", ignoreCase = true)

    /** 仅用于界面展示；持久化时仍须保留原 URL 才能重连。 */
    fun maskCredentials(url: String): String =
        url.replace(Regex("//[^/@]+@"), "//••••@")
}
