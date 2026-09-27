package com.roboyi.rtspdemo

import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量 TCP 建连探测，只用于界面展示网络可达延迟。
 * 不持有 Activity，也不参与播放器连接，避免状态面板与播放链路耦合。
 */
internal class RtspLatencyMonitor {
    private val executor = Executors.newSingleThreadExecutor()
    private val probing = AtomicBoolean(false)

    fun probe(url: String, callback: (Long?) -> Unit) {
        if (!probing.compareAndSet(false, true)) return
        executor.execute {
            val latency = runCatching {
                val uri = URI(url)
                val host = requireNotNull(uri.host)
                val port = if (uri.port > 0) uri.port else 554
                val started = SystemClock.elapsedRealtime()
                Socket().use { it.connect(InetSocketAddress(host, port), 900) }
                SystemClock.elapsedRealtime() - started
            }.getOrNull()
            probing.set(false)
            callback(latency)
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}
