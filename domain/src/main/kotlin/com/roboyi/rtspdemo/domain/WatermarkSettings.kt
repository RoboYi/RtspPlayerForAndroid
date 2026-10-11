package com.roboyi.rtspdemo.domain

enum class WatermarkPosition { LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM }
enum class WatermarkTarget { PREVIEW, SCREENSHOT, RECORDING }

/** 水印业务配置不引用播放器 SDK，方便在不同输出渠道复用。 */
data class WatermarkSettings(
    val text: String = "",
    val textSizeSp: Float = 24f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val position: WatermarkPosition = WatermarkPosition.LEFT_TOP,
    val imagePath: String? = null,
    val imageScale: Float = 0.20f,
    val applyPreview: Boolean = true,
    val applyScreenshot: Boolean = true,
    val applyRecording: Boolean = true
) {
    /** 未填写文字时不生成默认文字水印。 */
    fun effectiveText(): String = text.trim()

    fun appliesTo(target: WatermarkTarget): Boolean = when (target) {
        WatermarkTarget.PREVIEW -> applyPreview
        WatermarkTarget.SCREENSHOT -> applyScreenshot
        WatermarkTarget.RECORDING -> applyRecording
    }
}
