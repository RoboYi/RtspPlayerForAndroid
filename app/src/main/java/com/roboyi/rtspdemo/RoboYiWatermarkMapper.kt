package com.roboyi.rtspdemo

import android.content.Context
import android.graphics.Bitmap
import com.roboyi.rtsp.OverlayItem
import com.roboyi.rtsp.WatermarkBitmapFactory
import com.roboyi.rtsp.WatermarkSpec
import com.roboyi.rtspdemo.domain.WatermarkPosition
import com.roboyi.rtspdemo.domain.WatermarkSettings
import com.roboyi.rtspdemo.domain.WatermarkTarget

/** 只在 SDK 边界把业务配置转为 RoboYi 的参数对象。 */
internal object RoboYiWatermarkMapper {
    fun spec(
        context: Context,
        settings: WatermarkSettings,
        target: WatermarkTarget,
        fullFrameBitmap: Bitmap? = null
    ): WatermarkSpec {
        if (!settings.appliesTo(target)) return WatermarkSpec.NONE
        val text = settings.effectiveText()
        val osdSpec = WatermarkSpec(
            text = text,
            textSizeSp = settings.textSizeSp,
            textColor = settings.textColorArgb,
            position = when (settings.position) {
                WatermarkPosition.LEFT_TOP -> WatermarkSpec.Position.LEFT_TOP
                WatermarkPosition.RIGHT_TOP -> WatermarkSpec.Position.RIGHT_TOP
                WatermarkPosition.LEFT_BOTTOM -> WatermarkSpec.Position.LEFT_BOTTOM
                WatermarkPosition.RIGHT_BOTTOM -> WatermarkSpec.Position.RIGHT_BOTTOM
            },
            showText = text.isNotEmpty(),
            overlayImagePath = settings.imagePath,
            showOverlay = settings.imagePath != null,
            overlayScale = settings.imageScale,
            overlayPosition = WatermarkSpec.Position.RIGHT_BOTTOM
        )
        if (fullFrameBitmap == null) return osdSpec

        // SDK 在 overlayItems 非空时只绘制列表，先用 SDK 的定位规则展开普通 OSD。
        val osdItems = WatermarkBitmapFactory.run {
            osdSpec.toOverlayModel(context, fullFrameBitmap.width, fullFrameBitmap.height).items
        }
        return osdSpec.copy(overlayItems = listOf(OverlayItem.BitmapLayer(
            id = "demo_full_frame_bitmap",
            x = 0f,
            y = 0f,
            bitmap = fullFrameBitmap,
            width = 1f,
            height = 1f,
            zIndex = -1 // Bitmap 在下方，OSD 文字与图片仍清晰可见。
        )) + osdItems)
    }
}
