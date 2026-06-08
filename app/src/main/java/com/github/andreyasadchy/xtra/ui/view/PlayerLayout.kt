package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import com.github.andreyasadchy.xtra.R

class PlayerLayout : FrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var isPortrait = false
    var isZoomEnabled = false
    var scaleFactor = 1f
        private set
    private var lastScaleFactor = 1f
    private var normalizedTranslationX = 0f
    private var normalizedTranslationY = 0f
    private var lastNormalizedTranslationX = 0f
    private var lastNormalizedTranslationY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1

    val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1f, 3f)
            
            val view = findViewById<FrameLayout>(R.id.aspectRatioFrameLayout)
            view?.scaleX = scaleFactor
            view?.scaleY = scaleFactor
            
            applyConstraints(view)
            return true
        }
    })
private val doubleTapGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDoubleTap(e: MotionEvent): Boolean {
        val view = findViewById<FrameLayout>(R.id.aspectRatioFrameLayout) ?: return false
        if (scaleFactor > 1f) {
            lastScaleFactor = scaleFactor
            lastNormalizedTranslationX = normalizedTranslationX
            lastNormalizedTranslationY = normalizedTranslationY

            scaleFactor = 1f
            view.scaleX = scaleFactor
            view.scaleY = scaleFactor
            view.translationX = 0f
            view.translationY = 0f
            normalizedTranslationX = 0f
            normalizedTranslationY = 0f
        } else {
            scaleFactor = if (lastScaleFactor > 1f) lastScaleFactor else 2f
            view.scaleX = scaleFactor
            view.scaleY = scaleFactor

            normalizedTranslationX = lastNormalizedTranslationX
            normalizedTranslationY = lastNormalizedTranslationY

            val maxTranslationX = (view.width * (scaleFactor - 1f)) / 2f
            val maxTranslationY = (view.height * (scaleFactor - 1f)) / 2f
            view.translationX = normalizedTranslationX * maxTranslationX
            view.translationY = normalizedTranslationY * maxTranslationY

            applyConstraints(view)
        }
        return true
    }
})
    private fun applyConstraints(view: FrameLayout?) {
        if (view == null) return
        
        if (scaleFactor <= 1f) {
            view.translationX = 0f
            view.translationY = 0f
            normalizedTranslationX = 0f
            normalizedTranslationY = 0f
            return
        }

        val maxTranslationX = (view.width * (scaleFactor - 1f)) / 2f
        val maxTranslationY = (view.height * (scaleFactor - 1f)) / 2f
        
        if (maxTranslationX > 0) {
            view.translationX = view.translationX.coerceIn(-maxTranslationX, maxTranslationX)
            normalizedTranslationX = view.translationX / maxTranslationX
        } else {
            view.translationX = 0f
            normalizedTranslationX = 0f
        }
        
        if (maxTranslationY > 0) {
            view.translationY = view.translationY.coerceIn(-maxTranslationY, maxTranslationY)
            normalizedTranslationY = view.translationY / maxTranslationY
        } else {
            view.translationY = 0f
            normalizedTranslationY = 0f
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val view = findViewById<FrameLayout>(R.id.aspectRatioFrameLayout)
        if (view != null && scaleFactor > 1f) {
            val maxTranslationX = (view.width * (scaleFactor - 1f)) / 2f
            val maxTranslationY = (view.height * (scaleFactor - 1f)) / 2f
            view.translationX = normalizedTranslationX * maxTranslationX
            view.translationY = normalizedTranslationY * maxTranslationY
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (isPortrait) {
            val playerHeight = (measuredWidth / (16f / 9f)).toInt()
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(playerHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isZoomEnabled) return false

        scaleGestureDetector.onTouchEvent(event)
        doubleTapGestureDetector.onTouchEvent(event)
        
        val view = findViewById<FrameLayout>(R.id.aspectRatioFrameLayout) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && scaleFactor > 1f) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        
                        view.translationX += (x - lastTouchX)
                        view.translationY += (y - lastTouchY)
                        
                        applyConstraints(view)
                        
                        lastTouchX = x
                        lastTouchY = y
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
            }
        }
        return scaleGestureDetector.isInProgress || scaleFactor > 1f
    }
}