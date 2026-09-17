package com.roboyi.rtspdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Full-resolution transparent bitmap; touch coordinates are mapped from the preview to video pixels. */
internal class BitmapWatermarkEditorView(context: Context, val bitmap: Bitmap) : View(context) {
    private val displayRect = RectF()
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (bitmap.width * 0.004f).coerceAtLeast(3f)
    }
    private var pendingText: String? = null
    private var lastPoint: Pair<Float, Float>? = null

    fun placeTextOnNextTap(text: String) {
        pendingText = text
    }

    fun addSampleGraphic() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = (bitmap.width * 0.005f).coerceAtLeast(4f)
        }
        Canvas(bitmap).drawRoundRect(
            bitmap.width * 0.12f, bitmap.height * 0.20f,
            bitmap.width * 0.42f, bitmap.height * 0.42f,
            bitmap.height * 0.02f, bitmap.height * 0.02f, paint
        )
        invalidate()
    }

    fun clearDrawing() {
        bitmap.eraseColor(Color.TRANSPARENT)
        pendingText = null
        lastPoint = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(22, 31, 43))
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val displayWidth = bitmap.width * scale
        val displayHeight = bitmap.height * scale
        displayRect.set(
            (width - displayWidth) / 2f,
            (height - displayHeight) / 2f,
            (width + displayWidth) / 2f,
            (height + displayHeight) / 2f
        )
        canvas.drawBitmap(bitmap, null, displayRect, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = videoPoint(event.x, event.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val text = pendingText
                if (text != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = (bitmap.height * 0.05f).coerceAtLeast(24f)
                        setShadowLayer(3f, 2f, 2f, Color.BLACK)
                    }
                    val baseline = (point.second + paint.textSize).coerceAtMost(bitmap.height.toFloat())
                    Canvas(bitmap).drawText(text, point.first, baseline, paint)
                    pendingText = null
                    performClick()
                } else {
                    Canvas(bitmap).drawPoint(point.first, point.second, brush)
                    lastPoint = point
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var previous = lastPoint ?: return true
                val drawingCanvas = Canvas(bitmap)
                for (index in 0 until event.historySize) {
                    val point = videoPoint(event.getHistoricalX(index), event.getHistoricalY(index)) ?: continue
                    drawingCanvas.drawLine(previous.first, previous.second, point.first, point.second, brush)
                    previous = point
                }
                videoPoint(event.x, event.y)?.let { point ->
                    drawingCanvas.drawLine(previous.first, previous.second, point.first, point.second, brush)
                    lastPoint = point
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPoint = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun videoPoint(x: Float, y: Float): Pair<Float, Float>? {
        if (!displayRect.contains(x, y)) return null
        return Pair(
            ((x - displayRect.left) / displayRect.width() * bitmap.width).coerceIn(0f, bitmap.width.toFloat()),
            ((y - displayRect.top) / displayRect.height() * bitmap.height).coerceIn(0f, bitmap.height.toFloat())
        )
    }
}
