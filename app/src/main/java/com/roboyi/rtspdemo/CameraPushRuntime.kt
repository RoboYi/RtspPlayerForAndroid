package com.roboyi.rtspdemo

import com.roboyi.camerapush.CameraRtspPushConfig

internal enum class CameraPushPhase { IDLE, STARTING, RUNNING, STOPPING, FAILED }

internal data class CameraPushSnapshot(
    val phase: CameraPushPhase = CameraPushPhase.IDLE,
    val url: String? = null,
    val config: CameraRtspPushConfig? = null,
    val error: String? = null
)

/**
 * 进程内只保存可展示的推流快照，真正的摄像头和编码器由前台服务独占。
 * Activity 因旋转或页面切换销毁时，不会误停正在运行的推流。
 */
internal object CameraPushRuntime {
    @Volatile
    var snapshot: CameraPushSnapshot = CameraPushSnapshot()
        private set

    fun update(value: CameraPushSnapshot) {
        snapshot = value
    }
}
