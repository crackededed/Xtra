package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import kotlin.math.ceil
import kotlin.math.max

class NamePaintImageSpan(
    private val name: String,
    private val shadows: List<NamePaint.Shadow>?,
    var backgroundColor: Int?,
    private val bottomBackgroundColor: Int,
    val drawable: Drawable,
    private val fit: String? = null,
) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val paintFm = paint.fontMetrics
            fm.ascent = paintFm.ascent.toInt()
            fm.bottom = paintFm.bottom.toInt()
            fm.descent = paintFm.descent.toInt()
            fm.leading = paintFm.leading.toInt()
            fm.top = paintFm.top.toInt()
        }
        return paint.measureText(name).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        if (fit != null) {
            drawPlusImage(canvas, x, top, y, bottom, paint, fit)
            return
        }
        val xOffset = x.toInt()
        val width = paint.measureText(name).toInt()
        val height = bottom - top
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        val widthRatio = drawableWidth.toFloat() / drawableHeight.toFloat()
        val fullWidth: Int
        val fullHeight: Int
        if (height > drawableHeight) {
            val addedWidth = ceil((height - drawableHeight) * widthRatio).toInt()
            val newWidth = drawableWidth + addedWidth
            if (width > newWidth) {
                val addedHeight = ceil((width - newWidth) / widthRatio).toInt()
                fullWidth = xOffset + width
                fullHeight = bottom + addedHeight
            } else {
                fullWidth = xOffset + newWidth
                fullHeight = bottom
            }
        } else {
            if (width > drawableWidth) {
                val addedHeight = ceil((width - drawableWidth) / widthRatio).toInt()
                fullWidth = xOffset + width
                fullHeight = top + drawableHeight + addedHeight
            } else {
                fullWidth = xOffset + drawableWidth
                fullHeight = top + drawableHeight
            }
        }
        drawable.setBounds(xOffset, top, fullWidth, fullHeight)
        drawable.draw(canvas)
        val maskBitmap = Bitmap.createBitmap(max(fullWidth - xOffset, 0), max(fullHeight - top, 0), Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val maskPaint = Paint(paint)
        maskPaint.style = Paint.Style.FILL
        maskPaint.color = bottomBackgroundColor
        maskCanvas.drawPaint(maskPaint)
        backgroundColor?.let {
            maskPaint.color = it
            maskCanvas.drawPaint(maskPaint)
        }
        maskPaint.color = paint.color
        val yOffset = y.toFloat() - top
        shadows?.forEach {
            maskPaint.setShadowLayer(it.radius, it.xOffset, it.yOffset, it.color)
            maskCanvas.drawText(name, 0f, yOffset, maskPaint)
        }
        maskPaint.clearShadowLayer()
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        maskPaint.alpha = 0
        maskCanvas.drawText(name, 0f, yOffset, maskPaint)
        canvas.drawBitmap(maskBitmap, xOffset.toFloat(), top.toFloat(), paint)
    }

    private fun drawPlusImage(canvas: Canvas, x: Float, top: Int, y: Int, bottom: Int, paint: Paint, fit: String) {
        val width = ceil(paint.measureText(name)).toInt()
        val height = bottom - top
        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            canvas.drawText(name, x, y.toFloat(), paint)
            return
        }
        val glyph = Paint(paint).apply { shader = null }
        shadows?.forEach {
            glyph.setShadowLayer(it.radius, it.xOffset, it.yOffset, it.color)
            canvas.drawText(name, x, y.toFloat(), glyph)
        }
        glyph.clearShadowLayer()
        glyph.color = android.graphics.Color.WHITE
        val layer = canvas.saveLayer(x, top.toFloat(), x + width, bottom.toFloat(), null)
        canvas.drawText(name, x, y.toFloat(), glyph)
        val composite = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        val imageLayer = canvas.saveLayer(x, top.toFloat(), x + width, bottom.toFloat(), composite)
        val left = x.toInt()
        when (fit) {
            "tile" -> {
                val tileWidth = ceil(height * imageWidth.toFloat() / imageHeight).toInt().coerceAtLeast(1)
                for (offset in 0 until width step tileWidth) {
                    drawable.setBounds(left + offset, top, left + offset + tileWidth, bottom)
                    drawable.draw(canvas)
                }
            }
            "stretch" -> {
                drawable.setBounds(left, top, left + width, bottom)
                drawable.draw(canvas)
            }
            else -> {
                val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
                val scaledWidth = ceil(imageWidth * scale).toInt()
                val scaledHeight = ceil(imageHeight * scale).toInt()
                val imageLeft = left + (width - scaledWidth) / 2
                val imageTop = top + (height - scaledHeight) / 2
                drawable.setBounds(imageLeft, imageTop, imageLeft + scaledWidth, imageTop + scaledHeight)
                drawable.draw(canvas)
            }
        }
        canvas.restoreToCount(imageLayer)
        canvas.restoreToCount(layer)
    }
}
