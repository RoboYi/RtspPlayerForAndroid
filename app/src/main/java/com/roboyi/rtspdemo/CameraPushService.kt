package com.roboyi.rtspdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.roboyi.camerapush.CameraFacing
import com.roboyi.camerapush.CameraRtspPushConfig
import com.roboyi.camerapush.CameraRtspPushController
import com.roboyi.camerapush.VideoCodec
import com.roboyi.rtsp.RoboYiPlayer
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 前台服务独占摄像头推流资源，使切换页面或退到后台时仍可持续推流。 */
class CameraPushService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val controller by lazy { CameraRtspPushController(applicationContext, RTSP_PORT) }
    @Volatile private var focusServer: RemoteFocusServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPush()
            ACTION_START -> startPush(intent)
            ACTION_TORCH -> updateTorch(
                intent.getBooleanExtra(EXTRA_TORCH_ENABLED, false),
                intent.getBooleanExtra(EXTRA_PREVIOUS_TORCH_ENABLED, false)
            )
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        generation.incrementAndGet()
        focusServer?.close()
        focusServer = null
        runCatching { controller.stop() }
        worker.shutdownNow()
        CameraPushRuntime.update(CameraPushSnapshot())
        super.onDestroy()
    }

    private fun startPush(intent: Intent) {
        val request = generation.incrementAndGet()
        val config = configFrom(intent)
        CameraPushRuntime.update(CameraPushSnapshot(CameraPushPhase.STARTING, config = config))
        startForeground(NOTIFICATION_ID, notification(getString(R.string.push_starting)))
        worker.execute {
            runCatching {
                RoboYiPlayer.initialize(applicationContext, BuildConfig.ROBOYI_LICENSE)
                controller.start(config, previewSurface = null)
                val host = localIpv4Address() ?: "<device-ip>"
                focusServer?.close()
                val server = RemoteFocusServer(DEFAULT_FOCUS_PORT, config.path, controller::focusAt)
                server.start()
                focusServer = server
                "rtsp://$host:$RTSP_PORT/${config.path}"
            }.onSuccess { url ->
                if (generation.get() != request) {
                    focusServer?.close()
                    focusServer = null
                    runCatching { controller.stop() }
                    return@onSuccess
                }
                CameraPushRuntime.update(CameraPushSnapshot(CameraPushPhase.RUNNING, url, config))
                notifyRunning(url)
            }.onFailure { error ->
                if (generation.get() != request) return@onFailure
                focusServer?.close()
                focusServer = null
                runCatching { controller.stop() }
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: error::class.java.simpleName
                CameraPushRuntime.update(CameraPushSnapshot(
                    phase = CameraPushPhase.FAILED,
                    config = config,
                    error = message
                ))
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun stopPush() {
        generation.incrementAndGet()
        focusServer?.close()
        focusServer = null
        CameraPushRuntime.update(CameraPushRuntime.snapshot.copy(phase = CameraPushPhase.STOPPING))
        worker.execute {
            runCatching { controller.stop() }
            CameraPushRuntime.update(CameraPushSnapshot())
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun updateTorch(enabled: Boolean, previous: Boolean) {
        worker.execute {
            runCatching {
                check(controller.setTorchEnabled(enabled)) { "Torch is unavailable" }
                CameraPushRuntime.update(
                    CameraPushRuntime.snapshot.copy(
                        config = CameraPushRuntime.snapshot.config?.copy(torchEnabled = enabled)
                    )
                )
            }.onFailure {
                CameraPushRuntime.update(
                    CameraPushRuntime.snapshot.copy(
                        config = CameraPushRuntime.snapshot.config?.copy(torchEnabled = previous)
                    )
                )
            }
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CameraPushActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.push_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun notifyRunning(url: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(url))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.push_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun configFrom(intent: Intent): CameraRtspPushConfig {
        val osdItems = PushOsdConfig.decode(intent.getStringExtra(EXTRA_OSD_ITEMS))
        return CameraRtspPushConfig(
            facing = CameraFacing.valueOf(intent.getStringExtra(EXTRA_FACING) ?: CameraFacing.BACK.name),
            width = intent.getIntExtra(EXTRA_WIDTH, 1280),
            height = intent.getIntExtra(EXTRA_HEIGHT, 720),
            cameraWidth = intent.getIntExtra(EXTRA_WIDTH, 1280),
            cameraHeight = intent.getIntExtra(EXTRA_HEIGHT, 720),
            frameRate = intent.getIntExtra(EXTRA_FPS, 25),
            bitRate = intent.getIntExtra(EXTRA_BITRATE, 2_500_000),
            path = intent.getStringExtra(EXTRA_PATH).orEmpty().ifBlank { "live/camera" },
            codec = VideoCodec.valueOf(intent.getStringExtra(EXTRA_CODEC) ?: VideoCodec.H264.name),
            watermarkEnabled = osdItems.isNotEmpty(),
            watermarkSpec = PushOsdConfig.toWatermarkSpec(osdItems),
            torchEnabled = intent.getBooleanExtra(EXTRA_TORCH_ENABLED, false)
        )
    }

    private fun localIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()

    companion object {
        private const val ACTION_START = "com.roboyi.rtspdemo.camera.START"
        private const val ACTION_STOP = "com.roboyi.rtspdemo.camera.STOP"
        private const val ACTION_TORCH = "com.roboyi.rtspdemo.camera.TORCH"
        private const val EXTRA_FACING = "facing"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CODEC = "codec"
        private const val EXTRA_OSD_ITEMS = "osd_items"
        private const val EXTRA_TORCH_ENABLED = "torch_enabled"
        private const val EXTRA_PREVIOUS_TORCH_ENABLED = "previous_torch_enabled"
        private const val CHANNEL_ID = "camera_rtsp_push"
        private const val NOTIFICATION_ID = 8554
        private const val RTSP_PORT = 8554

        internal fun start(context: Context, config: CameraRtspPushConfig, osdItems: List<PushOsdItem>) {
            val intent = Intent(context, CameraPushService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FACING, config.facing.name)
                putExtra(EXTRA_WIDTH, config.width)
                putExtra(EXTRA_HEIGHT, config.height)
                putExtra(EXTRA_FPS, config.frameRate)
                putExtra(EXTRA_BITRATE, config.bitRate)
                putExtra(EXTRA_PATH, config.path)
                putExtra(EXTRA_CODEC, config.codec.name)
                putExtra(EXTRA_OSD_ITEMS, PushOsdConfig.encode(osdItems))
                putExtra(EXTRA_TORCH_ENABLED, config.torchEnabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraPushService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun setTorchEnabled(context: Context, enabled: Boolean) {
            val previous = CameraPushRuntime.snapshot.config?.torchEnabled ?: !enabled
            CameraPushRuntime.update(
                CameraPushRuntime.snapshot.copy(
                    config = CameraPushRuntime.snapshot.config?.copy(torchEnabled = enabled)
                )
            )
            context.startService(Intent(context, CameraPushService::class.java).apply {
                action = ACTION_TORCH
                putExtra(EXTRA_TORCH_ENABLED, enabled)
                putExtra(EXTRA_PREVIOUS_TORCH_ENABLED, previous)
            })
        }
    }
}
