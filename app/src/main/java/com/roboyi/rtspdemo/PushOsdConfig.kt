package com.roboyi.rtspdemo

import android.graphics.Color
import com.roboyi.camerapush.OverlayItem
import com.roboyi.camerapush.WatermarkSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推流页自己的轻量 OSD 模型。
 *
 * UI 不直接保存 SDK 对象，避免页面、Intent 序列化和推流服务相互耦合；真正启动预览或
 * 推流时再统一映射为 SDK 的 [OverlayItem.Text]。x/y 使用 0..1 的视频归一化坐标。
 */
internal data class PushOsdItem(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val textSizeSp: Float,
    val textColor: Int,
    val includeTimestamp: Boolean
)

internal object PushOsdConfig {
    fun toWatermarkSpec(items: List<PushOsdItem>): WatermarkSpec = WatermarkSpec(
        text = "",
        showText = false,
        overlayItems = items.mapIndexed { index, item ->
            OverlayItem.Text(
                id = item.id,
                x = item.x.coerceIn(0f, 1f),
                y = item.y.coerceIn(0f, 1f),
                text = item.text,
                textSizeSp = item.textSizeSp.coerceIn(12f, 54f),
                textColor = item.textColor,
                shadowColor = Color.BLACK,
                backgroundColor = Color.argb(96, 0, 0, 0),
                includeTimestamp = item.includeTimestamp,
                zIndex = index
            )
        }
    )

    /** 前台服务通过字符串接收配置，避免依赖 Activity 内存状态。 */
    fun encode(items: List<PushOsdItem>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("x", item.x.toDouble())
                put("y", item.y.toDouble())
                put("size", item.textSizeSp.toDouble())
                put("color", item.textColor)
                put("timestamp", item.includeTimestamp)
            })
        }
    }.toString()

    fun decode(raw: String?): List<PushOsdItem> = runCatching {
        val array = JSONArray(raw.orEmpty())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(PushOsdItem(
                    id = item.optString("id").ifBlank { "osd_$index" },
                    text = item.optString("text"),
                    x = item.optDouble("x", 0.04).toFloat().coerceIn(0f, 1f),
                    y = item.optDouble("y", 0.05).toFloat().coerceIn(0f, 1f),
                    textSizeSp = item.optDouble("size", 20.0).toFloat().coerceIn(12f, 54f),
                    textColor = item.optInt("color", Color.WHITE),
                    includeTimestamp = item.optBoolean("timestamp", false)
                ))
            }
        }
    }.getOrDefault(emptyList())
}
