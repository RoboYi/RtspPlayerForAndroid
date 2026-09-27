package com.roboyi.rtspdemo

import android.net.Uri
import android.util.Log
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Remote focus is discovered from a normal RTSP URL. The control endpoint is negotiated
 * automatically on the companion port, so users never need to edit control data into the URL.
 */
internal data class RemoteFocusTarget(
    val host: String,
    val port: Int,
    val streamPath: String
) {
    companion object {
        fun fromRtspUrl(url: String): RemoteFocusTarget? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals("rtsp", ignoreCase = true)) return null
            if (uri.fragment != null) return null
            val host = uri.host?.takeIf(String::isNotBlank) ?: return null
            val rtspPort = uri.port.takeIf { it in 1 until 65535 } ?: DEFAULT_RTSP_PORT
            val path = uri.encodedPath.orEmpty().trim('/').takeIf(String::isNotBlank) ?: return null
            return RemoteFocusTarget(host, rtspPort + 1, path)
        }

    }
}

internal object RemoteFocusClient {
    fun send(target: RemoteFocusTarget, x: Float, y: Float): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val output = socket.getOutputStream()
            output.write("HELLO $PROTOCOL ${target.streamPath}\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            if (readLineLimited(socket, MAX_RESPONSE_BYTES) != FOCUS_CAPABILITY) return false
            val command = String.format(
                Locale.US,
                "FOCUS %.6f %.6f\n",
                x.coerceIn(0f, 1f),
                y.coerceIn(0f, 1f)
            )
            output.write(command.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val focused = readLineLimited(socket, MAX_RESPONSE_BYTES) == "OK"
            Log.d(TAG, "Remote focus response host=${target.host} path=${target.streamPath} ok=$focused")
            focused
        }
    }.onFailure { error ->
        Log.w(TAG, "Remote focus connection failed host=${target.host}:${target.port}", error)
    }.getOrDefault(false)

    private const val CONNECT_TIMEOUT_MS = 1_000
    private const val READ_TIMEOUT_MS = 2_500
    private const val MAX_RESPONSE_BYTES = 96
    private const val TAG = "RoboYiRemoteFocus"
}

internal class RemoteFocusServer(
    private val port: Int,
    streamPath: String,
    private val onFocus: (Float, Float) -> Boolean
) : Closeable {
    private val expectedStreamPath = streamPath.trim('/')
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null
    private var lastFocusAtMs = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = server
        Log.i(TAG, "Focus control ready port=$port path=$expectedStreamPath")
        worker = Thread({
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    if (!running.get()) break
                    continue
                }
                runCatching { handle(socket) }.onFailure { error ->
                    Log.w(TAG, "Focus control request failed", error)
                }
                runCatching { socket.close() }
            }
        }, "RoboYi-RemoteFocus").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = CLIENT_TIMEOUT_MS
        val hello = readLineLimited(socket, MAX_COMMAND_BYTES)?.trim()?.split(Regex("\\s+"))
            ?: return reply(socket, ERROR_RESPONSE)
        if (hello.size != 3 || hello[0] != "HELLO" || hello[1] != PROTOCOL ||
            hello[2].trim('/') != expectedStreamPath
        ) {
            Log.w(TAG, "Rejected focus handshake from ${socket.inetAddress?.hostAddress}")
            return reply(socket, ERROR_RESPONSE)
        }
        reply(socket, CAPABILITY_RESPONSE)

        val parts = readLineLimited(socket, MAX_COMMAND_BYTES)?.trim()?.split(Regex("\\s+"))
            ?: return reply(socket, ERROR_RESPONSE)
        if (parts.size != 3 || parts[0] != "FOCUS") return reply(socket, ERROR_RESPONSE)
        val x = parts[1].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
        val y = parts[2].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
        if (x == null || y == null) return reply(socket, ERROR_RESPONSE)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFocusAtMs < MIN_FOCUS_INTERVAL_MS) return reply(socket, ERROR_RESPONSE)
        lastFocusAtMs = now
        val focused = onFocus(x, y)
        Log.d(TAG, "Focus request x=$x y=$y ok=$focused")
        reply(socket, if (focused) OK_RESPONSE else ERROR_RESPONSE)
    }

    private fun reply(socket: Socket, response: ByteArray) {
        socket.getOutputStream().write(response)
        socket.getOutputStream().flush()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker?.interrupt()
        worker = null
        Log.i(TAG, "Focus control stopped")
    }

    private companion object {
        const val CLIENT_TIMEOUT_MS = 3_000
        const val MAX_COMMAND_BYTES = 256
        const val MIN_FOCUS_INTERVAL_MS = 120L
        const val TAG = "RoboYiRemoteFocus"
        val CAPABILITY_RESPONSE = "$FOCUS_CAPABILITY\n".toByteArray(StandardCharsets.US_ASCII)
        val OK_RESPONSE = "OK\n".toByteArray(StandardCharsets.US_ASCII)
        val ERROR_RESPONSE = "ERROR\n".toByteArray(StandardCharsets.US_ASCII)
    }
}

private fun readLineLimited(socket: Socket, maxBytes: Int): String? {
    val input = socket.getInputStream()
    val buffer = ByteArray(maxBytes)
    var count = 0
    while (count < maxBytes) {
        val value = input.read()
        if (value < 0 || value == '\n'.code) break
        if (value != '\r'.code) buffer[count++] = value.toByte()
    }
    if (count == maxBytes) return null
    return String(buffer, 0, count, StandardCharsets.US_ASCII)
}

private const val PROTOCOL = "ROBOYI/1"
private const val FOCUS_CAPABILITY = "CAPS FOCUS/1"
private const val DEFAULT_RTSP_PORT = 554
internal const val DEFAULT_FOCUS_PORT = 8555
