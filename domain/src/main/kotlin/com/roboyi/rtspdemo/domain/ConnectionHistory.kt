package com.roboyi.rtspdemo.domain

data class ConnectionEntry(val url: String, val connectedAtMs: Long)

/** 历史记录契约：业务层不关心偏好设置或其他持久化格式。 */
interface ConnectionHistoryRepository {
    fun entries(): List<ConnectionEntry>
    fun record(url: String)
    fun clear()
}

object RtspAddress {
    fun isSupported(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        (uri.scheme.equals("rtsp", true) || uri.scheme.equals("rtsps", true)) &&
            !uri.host.isNullOrBlank() && uri.port in -1..65535 && uri.port != 0 &&
            url.none { it.isISOControl() }
    }.getOrDefault(false)

    /** History rows show only the endpoint; paths and query strings can also hold secrets. */
    fun maskCredentials(url: String): String = runCatching {
        val uri = java.net.URI(url)
        require(isSupported(url))
        "${uri.scheme.lowercase()}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
    }.getOrDefault("RTSP")
}
