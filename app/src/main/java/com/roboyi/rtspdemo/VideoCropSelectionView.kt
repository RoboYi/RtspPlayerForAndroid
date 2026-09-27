package com.roboyi.rtspdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.roboyi.rtsp.VideoCropRegion
import kotlin.math.max
import kotlin.math.min

/** A small demo-only editor that lets the user drag a crop rectangle over a full-frame snapshot. */
internal class VideoCropSelectionView(
    context: Context,
    private val bitmap: Bitmap,
    initialRegion: VideoCropRegion,
    private val onRegionChanged: (VideoCropRegion) -> Unit
) : View(context) {
    private val imageRect = RectF()
    private val selectionRect = RectF()
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private var region = initialRegion
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var regionBeforeDrag = initialRegion

    val selectedRegion: VideoCropRegion get() = region

    init {
        setBackgroundColor(Color.BLACK)
        contentDescription = context.getString(R.string.video_crop_canvas_description)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateImageRect()
        canvas.drawBitmap(bitmap, null, imageRect, null)
        regionToView(region, selectionRect)

        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, selectionRect.top, dimPaint)
        canvas.drawRect(imageRect.left, selectionRect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, selectionRect.top, selectionRect.left, selectionRect.bottom, dimPaint)
        canvas.drawRect(selectionRect.right, selectionRect.top, imageRect.right, selectionRect.bottom, dimPaint)

        canvas.drawRect(selectionRect, borderPaint)
        val thirdWidth = selectionRect.width() / 3f
        val thirdHeight = selectionRect.height() / 3f
        for (index in 1..2) {
            val x = selectionRect.left + thirdWidth * index
            val y = selectionRect.top + thirdHeight * index
            canvas.drawLine(x, selectionRect.top, x, selectionRect.bottom, gridPaint)
            canvas.drawLine(selectionRect.left, y, selectionRect.right, y, gridPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) updateImageRect()
        val nx = ((event.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ny = ((event.y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = nx
                dragStartY = ny
                regionBeforeDrag = region
                true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val left = min(dragStartX, nx)
                val top = min(dragStartY, ny)
                val right = max(dragStartX, nx)
                val bottom = max(dragStartY, ny)
                if (right - left >= MIN_SELECTION_SIZE && bottom - top >= MIN_SELECTION_SIZE) {
                    region = VideoCropRegion(left, top, right, bottom)
                    onRegionChanged(region)
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                region = regionBeforeDrag
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateImageRect() {
        if (width <= 0 || height <= 0) return
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val viewAspect = width.toFloat() / height.coerceAtLeast(1)
        if (bitmapAspect > viewAspect) {
            val drawHeight = width / bitmapAspect
            val top = (height - drawHeight) / 2f
            imageRect.set(0f, top, width.toFloat(), top + drawHeight)
        } else {
            val drawWidth = height * bitmapAspect
            val left = (width - drawWidth) / 2f
            imageRect.set(left, 0f, left + drawWidth, height.toFloat())
        }
    }

    private fun regionToView(source: VideoCropRegion, destination: RectF) {
        destination.set(
            imageRect.left + source.left * imageRect.width(),
            imageRect.top + source.top * imageRect.height(),
            imageRect.left + source.right * imageRect.width(),
            imageRect.top + source.bottom * imageRect.height()
        )
    }

    private companion object {
        const val MIN_SELECTION_SIZE = 0.02f
    }
}
