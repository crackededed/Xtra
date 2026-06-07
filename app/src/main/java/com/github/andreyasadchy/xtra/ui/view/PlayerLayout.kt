package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import com.github.andreyasadchy.xtra.R
import kotlin.math.max
import kotlin.math.min

class PlayerLayout : FrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    var isPortrait = false
    var scaleFactor = 1f
        private set
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
            
            // Constrain translation after scaling
            applyConstraints(view)
            return true
        }
    })

    private fun applyConstraints(view: FrameLayout?) {
        if (view == null || scaleFactor <= 1f) {
            view?.translationX = 0f
            view?.translationY = 0f
            return
        }

        val maxTranslationX = (view.width * (scaleFactor - 1f)) / 2f
        val maxTranslationY = (view.height * (scaleFactor - 1f)) / 2f
        
        view.translationX = view.translationX.coerceIn(-maxTranslationX, maxTranslationX)
        view.translationY = view.translationY.coerceIn(-maxTranslationY, maxTranslationY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        
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
                        return true // Pan handled
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
            }
        }
        return scaleGestureDetector.isInProgress || scaleFactor > 1f
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
}