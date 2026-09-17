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
    /** 仅在文字和图片都没有时使用应用名，避免静默生成无水印内容。 */
    fun effectiveText(defaultText: String): String = text.trim().ifEmpty {
        if (imagePath == null) defaultText else ""
    }

    fun appliesTo(target: WatermarkTarget): Boolean = when (target) {
        WatermarkTarget.PREVIEW -> applyPreview
        WatermarkTarget.SCREENSHOT -> applyScreenshot
        WatermarkTarget.RECORDING -> applyRecording
    }
}
