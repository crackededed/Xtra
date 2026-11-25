package com.github.andreyasadchy.xtra.ui.player

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import com.otaliastudios.zoom.ZoomApi
import com.otaliastudios.zoom.ZoomEngine

internal class VideoZoom(private val viewport: View, private val videoFrame: View) {
    private val engine = ZoomEngine(viewport.context, viewport).apply {
        setTransformation(ZoomApi.TRANSFORMATION_NONE)
        setMinZoom(1f)
        setMaxZoom(4f)
        setOverPinchable(false)
        setOverScrollHorizontal(false)
        setOverScrollVertical(false)
        setFlingEnabled(false)
        setThreeFingersScrollEnabled(false)
        addListener(object : ZoomEngine.Listener {
            override fun onIdle(engine: ZoomEngine) = Unit

            override fun onUpdate(engine: ZoomEngine, matrix: Matrix) {
                val zoom = engine.realZoom
                val zoomed = zoom > BASE_ZOOM_THRESHOLD
                val scale = if (zoomed) zoom else 1f
                videoFrame.scaleX = scale
                videoFrame.scaleY = scale
                // At 1x, keep the original aspect-ratio layout, including its built-in crop.
                videoFrame.translationX = if (zoomed) engine.scaledPanX - videoFrame.left else 0f
                videoFrame.translationY = if (zoomed) engine.scaledPanY - videoFrame.top else 0f
            }
        })
    }

    val isZoomed: Boolean
        get() = engine.realZoom > BASE_ZOOM_THRESHOLD

    init {
        videoFrame.pivotX = 0f
        videoFrame.pivotY = 0f
        val onLayout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                updateSize()
            }
        }
        viewport.addOnLayoutChangeListener(onLayout)
        videoFrame.addOnLayoutChangeListener(onLayout)
    }

    private fun updateSize() {
        if (viewport.width <= 0 || viewport.height <= 0 || videoFrame.width <= 0 || videoFrame.height <= 0) return
        engine.setContainerSize(viewport.width.toFloat(), viewport.height.toFloat())
        engine.setContentSize(videoFrame.width.toFloat(), videoFrame.height.toFloat())
        reset()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            engine.setOneFingerScrollEnabled(isZoomed)
        }
        return engine.onInterceptTouchEvent(event)
    }

    fun reset() {
        if (videoFrame.width > 0 && videoFrame.height > 0 && viewport.width > 0 && viewport.height > 0) {
            engine.moveToCenter(1f, animate = false)
        }
    }

    private companion object {
        const val BASE_ZOOM_THRESHOLD = 1.001f
    }
}
