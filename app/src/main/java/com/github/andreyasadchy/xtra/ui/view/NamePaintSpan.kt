package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.text.style.ReplacementSpan
import android.view.View
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class NamePaintSpan(
    private val name: String,
    private val type: String,
    private val colors: IntArray,
    private val colorPositions: FloatArray,
    private val angle: Int?,
    private val repeat: Boolean?,
    private val shadows: List<NamePaint.Shadow>?,
    private val tiles: Int? = null,
    private val animation: NamePaint.Animation? = null,
    private val itemView: View? = null,
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
        val yOffset = y.toFloat()
        shadows?.forEach {
            paint.setShadowLayer(it.radius, it.xOffset, it.yOffset, it.color)
            canvas.drawText(name, x, yOffset, paint)
        }
        paint.clearShadowLayer()
        if (tiles != null) {
            val original = paint.shader
            paint.shader = plusShader(x, top.toFloat(), bottom.toFloat(), paint.measureText(name), tiles)
            canvas.drawText(name, x, yOffset, paint)
            paint.shader = original
            if (animation != null && itemView?.isAttachedToWindow == true && itemView.isShown) {
                itemView.postInvalidateDelayed(33)
            }
            return
        }
        when (type) {
            "LINEAR_GRADIENT" -> {
                val width = paint.measureText(name)
                val height = (bottom - top).toFloat()
                val topFloat = top.toFloat()
                val points = floatArrayOf(x, topFloat, x + width, topFloat)
                val matrix = Matrix()
                matrix.setRotate(((angle ?: 0) - 90).toFloat(), x + (width / 2), top + (height / 2))
                matrix.mapPoints(points)
                paint.shader = LinearGradient(
                    points[0],
                    min(points[1], bottom.toFloat()),
                    points[2],
                    max(points[3], topFloat),
                    colors,
                    colorPositions,
                    if (repeat == true) {
                        Shader.TileMode.REPEAT
                    } else {
                        Shader.TileMode.CLAMP
                    }
                )
            }
            "RADIAL_GRADIENT" -> {
                val halfWidth = paint.measureText(name) / 2
                val height = (bottom - top).toFloat()
                paint.shader = RadialGradient(
                    x + halfWidth,
                    top + (height / 2),
                    halfWidth,
                    colors,
                    colorPositions,
                    if (repeat == true) {
                        Shader.TileMode.REPEAT
                    } else {
                        Shader.TileMode.CLAMP
                    }
                )
            }
        }
        canvas.drawText(name, x, yOffset, paint)
    }

    private fun plusShader(x: Float, top: Float, bottom: Float, width: Float, tiles: Int): Shader {
        val progress = animation?.let { (SystemClock.elapsedRealtime() % it.durationMs).toFloat() / it.durationMs } ?: 0f
        val height = (bottom - top).coerceAtLeast(1f)
        val cx = x + width / 2f
        val cy = top + height / 2f
        val slide = animation?.kind == "slide"
        val pulse = if (animation?.kind == "pulse") {
            1f + 0.18f * sin(progress * 2 * Math.PI).toFloat()
        } else 1f
        val repeated = tiles > 1 || slide
        val tileMode = if (repeated) Shader.TileMode.MIRROR else Shader.TileMode.CLAMP
        val divisions = if (repeated) tiles * 2 else 1
        if (type == "RADIAL_GRADIENT") {
            val phase = if (slide) 1f + 0.2f * sin(progress * 2 * Math.PI).toFloat() else 1f
            return RadialGradient(cx, cy, (width / 2f * pulse * phase / divisions).coerceAtLeast(1f), colors, colorPositions, tileMode)
        }
        val degrees = (angle ?: 0) + if (animation?.kind == "spin") 360f * progress else 0f
        val radians = Math.toRadians(degrees.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val length = (abs(dx) * width + abs(dy) * height).coerceAtLeast(1f) * pulse
        val segment = length / divisions
        val offset = -length / 2f + if (slide) progress * segment * 2 else 0f
        return LinearGradient(
            cx + dx * offset,
            cy + dy * offset,
            cx + dx * (offset + segment),
            cy + dy * (offset + segment),
            colors,
            colorPositions,
            tileMode,
        )
    }
}
