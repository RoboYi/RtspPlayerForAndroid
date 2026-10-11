package com.roboyi.rtspdemo

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.roboyi.rtsp.WatermarkBitmapFactory
import com.roboyi.rtsp.WatermarkSpec
import com.roboyi.rtspdemo.data.InspectionRule

internal data class InspectionPreviewItem(val key: String, val text: String, val rule: InspectionRule)

/** Uses the same SDK renderer and video coordinate space as the exported watermark. */
internal class InspectionOsdPreview(context: Context, private val frame: Bitmap? = null) : View(context) {
    var items = emptyList<InspectionPreviewItem>(); set(value) { field = value; invalidate() }
    var selected: String? = null; set(value) { field = value; invalidate() }
    var onSelected: (String) -> Unit = {}
    var onMoved: (String, Float, Float) -> Unit = { _, _, _ -> }
    private val viewport = RectF()
    private val bounds = linkedMapOf<String, RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var moving: String? = null
    private var offsetX = 0f; private var offsetY = 0f
    private val videoWidth = 1280
    private val videoHeight = frame?.let { (1280f * it.height / it.width).toInt() } ?: 720
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 19, 27))
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        viewport.set((width - videoWidth * scale) / 2, (height - videoHeight * scale) / 2,
            (width + videoWidth * scale) / 2, (height + videoHeight * scale) / 2)
        canvas.save(); canvas.translate(viewport.left, viewport.top); canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat())
        if (frame != null && !frame.isRecycled) canvas.drawBitmap(frame, null, RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat()), null)
        else {
            paint.color = Color.rgb(36, 55, 65); paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.rgb(78, 98, 107)
            for (n in 1..6) {
                val rx = n * 145f; val ry = n * 80f
                canvas.drawOval(RectF(790f - rx, 360f - ry, 790f + rx, 360f + ry), paint)
            }
            paint.strokeWidth = 1f; paint.color = Color.argb(35, 255, 255, 255)
            for (n in 1..9) { canvas.drawLine(n * 128f, 0f, n * 128f, 720f, paint); canvas.drawLine(0f, n * 72f, 1280f, n * 72f, paint) }
        }
        val visible = items.filter { it.rule.enabled }
        val layers = visible.map { i -> InspectionWatermarkRenderer.item(i.key, i.text, i.rule, videoWidth, videoHeight) }
        if (visible.isNotEmpty()) WatermarkBitmapFactory.drawFullFrame(context, canvas, videoWidth, videoHeight,
            WatermarkSpec(text = "", showText = false, showOverlay = false, overlayItems = layers), false)
        bounds.clear()
        visible.zip(layers).forEach { (item, layer) ->
            val w = layer.width * videoWidth
            val h = layer.height * videoHeight
            val x = (item.rule.x / 100f * videoWidth).coerceIn(0f, (videoWidth - w).coerceAtLeast(0f))
            val y = (item.rule.y / 100f * videoHeight).coerceIn(0f, (videoHeight - h).coerceAtLeast(0f))
            bounds[item.key] = RectF(x, y, x + w, y + h)
        }
        bounds[selected]?.let { rect ->
            paint.color = Color.rgb(255, 64, 88); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f / scale
            canvas.drawRect(RectF(rect).apply { inset(-3f / scale, -3f / scale) }, paint)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (viewport.isEmpty) return false
        val x = (event.x - viewport.left) / viewport.width() * videoWidth
        val y = (event.y - viewport.top) / viewport.height() * videoHeight
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!viewport.contains(event.x, event.y)) return false
                val hit = bounds.entries.reversed().firstOrNull { RectF(it.value).apply { inset(-12f, -12f) }.contains(x, y) } ?: return false
                moving = hit.key; selected = hit.key; offsetX = x - hit.value.left; offsetY = y - hit.value.top
                parent.requestDisallowInterceptTouchEvent(true); onSelected(hit.key); performClick(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val key = moving ?: return false
                val rect = bounds[key] ?: return true
                val nx = ((x - offsetX).coerceIn(0f, (videoWidth - rect.width()).coerceAtLeast(0f)) / videoWidth * 100f)
                val ny = ((y - offsetY).coerceIn(0f, (videoHeight - rect.height()).coerceAtLeast(0f)) / videoHeight * 100f)
                onMoved(key, nx, ny); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { moving = null; parent.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
