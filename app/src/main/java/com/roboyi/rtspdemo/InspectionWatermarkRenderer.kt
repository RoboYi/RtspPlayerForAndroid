package com.roboyi.rtspdemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.roboyi.rtsp.OverlayItem
import com.roboyi.rtspdemo.data.InspectionRule
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The SDK Text item couples both alphas. A cached bitmap preserves independent text/background alpha. */
internal object InspectionWatermarkRenderer {
    private data class Key(val text: String, val size: Float, val color: Int, val background: Int,
                           val textOpacity: Float, val backgroundOpacity: Float, val width: Int, val height: Int)
    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
        // Do not recycle evicted bitmaps: in-flight SDK frames may still reference them.
    }

    @Synchronized fun item(id: String, text: String, rule: InspectionRule, videoWidth: Int, videoHeight: Int,
                           zIndex: Int = 20): OverlayItem.BitmapLayer {
        // Render at the target resolution to keep recorded text sharp, including 4K streams.
        val width = videoWidth.coerceAtLeast(1)
        val height = videoHeight.coerceAtLeast(1)
        val key = Key(text.trim(), rule.size, rule.color, rule.background,
            rule.textOpacity, rule.backgroundOpacity, width, height)
        val bitmap = cache.get(key) ?: render(key).also { cache.put(key, it) }
        return OverlayItem.BitmapLayer(id = id, x = rule.x / 100f, y = rule.y / 100f,
            bitmap = bitmap, width = bitmap.width.toFloat() / width, height = bitmap.height.toFloat() / height,
            alpha = 1f, zIndex = zIndex)
    }

    private fun render(key: Key): Bitmap {
        val metric = (minOf(key.width, key.height) / 720f).coerceAtLeast(.25f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = key.color
            alpha = (Color.alpha(key.color) * key.textOpacity.coerceIn(0f, 1f)).roundToInt()
            textSize = key.size * metric
        }
        while (textPaint.textSize > 8 * metric && textPaint.measureText(key.text) + 20 * metric > key.width * .92f) {
            textPaint.textSize *= .92f
        }
        val fm = textPaint.fontMetrics
        val boxWidth = ceil((textPaint.measureText(key.text) + 20 * metric).toDouble()).toFloat()
        val boxHeight = ceil((fm.descent - fm.ascent + 12 * metric).toDouble()).toFloat()
        // SDK BitmapLayer has a 1% minimum size. Pad tiny layers transparently instead of stretching glyphs.
        val width = maxOf(boxWidth.toInt(), ceil(key.width * .01).toInt()).coerceIn(1, key.width)
        val height = maxOf(boxHeight.toInt(), ceil(key.height * .01).toInt()).coerceIn(1, key.height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = key.background
                alpha = (Color.alpha(key.background) * key.backgroundOpacity.coerceIn(0f, 1f)).roundToInt()
            }
            canvas.drawRoundRect(RectF(0f, 0f, boxWidth, boxHeight), 6 * metric, 6 * metric, backgroundPaint)
            canvas.drawText(key.text, 10 * metric, 6 * metric - fm.ascent, textPaint)
        }
    }
}
