package com.github.andreyasadchy.xtra.ui.view

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.customview.widget.ViewDragHelper
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getActivity
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isClick
import com.github.andreyasadchy.xtra.util.isKeyboardShown
import com.github.andreyasadchy.xtra.util.isLightTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors

private const val ANIMATION_DURATION = 250L

class SlidingLayout : LinearLayout {

    private var debug = false
    private val viewDragHelper = ViewDragHelper.create(this, 1f, SlidingCallback())
    private lateinit var dragView: View
    var secondView: View? = null
    var savedInsets: Insets? = null
    private var backgroundColor: Int? = null
    private var backgroundVisible = true
    private var isLightTheme: Boolean? = null

    private var timeBar: View? = null
    private var topBound = 0
    private var bottomBound = 0
    private var minimizeThreshold = 0

    private var bottomMargin = 0f
    private var dragViewTop = 0
    private var dragViewLeft = 0
    private var minScaleX = 0f

    private var minScaleY = 0f

    private var downTouchLocation = FloatArray(2)
    private val isPortrait: Boolean
        get() = orientation == 1
    var isMaximized = true
        private set
    private var isAnimating = false
    private var shouldUpdateDragLayout = false

    var maximizedSecondViewVisibility: Int? = null //TODO make private
    private var listeners = arrayListOf<Listener>()

    private val animatorListener = object : Animator.AnimatorListener {
        override fun onAnimationRepeat(animation: Animator) {}
        override fun onAnimationCancel(animation: Animator) {}
        override fun onAnimationStart(animation: Animator) {
            isAnimating = true
            if (!isMaximized) {
                disableBackground()
            }
        }

        override fun onAnimationEnd(animation: Animator) {
            isAnimating = false
            shouldUpdateDragLayout = false
            dragViewTop = 0
            dragViewLeft = 0
            requestLayout()
            secondView?.postInvalidate()
            if (isMaximized) {
                enableBackground(isPortrait)
            }
        }
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        id = R.id.slidingLayout
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        dragView = getChildAt(0)
        secondView = getChildAt(1)
        init()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val height = dragView.measuredHeight
        if (!isAnimating || shouldUpdateDragLayout) {
            dragView.layout(dragViewLeft, dragViewTop, if (isMaximized) dragView.measuredWidth + dragViewLeft else width, height + dragViewTop)
        }
        secondView?.let {
            if (isMaximized && it.isVisible) {
                if (isPortrait) {
                    it.layout(l, height + dragViewTop, r, b + dragViewTop)
                } else {
                    it.layout(dragView.measuredWidth, dragViewTop, width, height + dragViewTop)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) VERTICAL else HORIZONTAL
        init()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                viewDragHelper.cancel()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                if (ev.getPointerId(ev.actionIndex) == ViewDragHelper.INVALID_POINTER) {
                    return false
                }
            }
        }
        val interceptTap = viewDragHelper.isViewUnder(dragView, ev.x.toInt(), ev.y.toInt())
        return (viewDragHelper.shouldInterceptTouchEvent(ev) || interceptTap)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            if (isAnimating) return true
            if (timeBar?.isPressed == true) {
                dragView.dispatchTouchEvent(event)
                return true
            }
            val x = event.x.toInt()
            val y = event.y.toInt()
            val isDragViewHit = isViewHit(dragView, x, y)
            val isClick = event.isClick(downTouchLocation)
            if (y > 100 || !isMaximized) {
                viewDragHelper.processTouchEvent(event)
            }
            if (isDragViewHit) {
                if (isMaximized) {
                    dragView.dispatchTouchEvent(event)
                } else if (isClick) {
                    maximize()
                    return true
                }
            }
            if (isClick) {
                performClick()
            }
            return isDragViewHit || secondView?.let { isViewHit(it, x, y) } == true
        } catch (e: Exception) {

        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun computeScroll() {
        if (viewDragHelper.continueSettling(true)) {
            postInvalidateOnAnimation()
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        secondView?.let {
            if (!isPortrait && isMaximized) {
                maximizedSecondViewVisibility = it.visibility
            }
        }
        return bundleOf(
            "superState" to super.onSaveInstanceState(),
            "isMaximized" to isMaximized,
            "secondViewVisibility" to maximizedSecondViewVisibility
        )
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState(state.let {
            if (it is Bundle) {
                isMaximized = it.getBoolean("isMaximized")
                maximizedSecondViewVisibility = it.getInt("secondViewVisibility")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.getParcelable("superState", Parcelable::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelable("superState")
                }
            } else {
                it
            }
        })
    }

    fun maximize() {
        isMaximized = true
        secondView?.apply {
            requestLayout()
            visibility = if (isPortrait) {
                VISIBLE
            } else {
                shouldUpdateDragLayout = true
                maximizedSecondViewVisibility!!
            }
        }
        animate(1f, 1f)
        listeners.forEach { it.onMaximize() }
    }

    fun minimize() {
        isMaximized = false
        secondView?.apply {
            layout(0, 0, 0, 0)
            if (!isPortrait) {
                shouldUpdateDragLayout = true
                maximizedSecondViewVisibility = visibility
            }
            gone()
        }
        animate(minScaleX, minScaleY)
        if (dragViewTop != 0) {
            val top = PropertyValuesHolder.ofInt("top", 0)
            val bot = PropertyValuesHolder.ofInt("bottom", dragView.height)
            ObjectAnimator.ofPropertyValuesHolder(dragView, top, bot).apply {
                duration = ANIMATION_DURATION
                start()
            }
        }
        listeners.forEach { it.onMinimize() }
    }

    fun init() {
        debug = context.prefs().getBoolean(C.DEBUG_SECONDVIEW, false)
        dragView.post {
            topBound = paddingTop
            if (isPortrait) { //portrait
                minScaleX = 0.5f
                minScaleY = 0.5f
            } else { //landscape
                minScaleX = 0.3f
                minScaleY = 0.325f
            }
            val navBarHeight = rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height ?: 100
            bottomMargin = (navBarHeight / (1f - minScaleY)) + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics)

            fun initialize() {
                minimizeThreshold = height / 5
                pivotX = (width - ((savedInsets?.right ?: 0) / (1f - minScaleX))) * 0.95f
                if (isPortrait) {
                    bottomBound = height / 2
                    pivotY = height * 2 - dragView.height - bottomMargin
                } else {
                    bottomBound = (height / 1.5f).toInt()
                    pivotY = height - bottomMargin
                }
            }

            if (!isPortrait || !isMaximized || !isKeyboardShown) {
                initialize()
            } else {
                postDelayed(750L) {
                    //delay to avoid issue after rotating from landscape with opened keyboard to portrait
                    initialize()
                }
            }
            if (!isMaximized) {
                scaleX = minScaleX
                scaleY = minScaleY
            }
            timeBar = dragView.findViewById(androidx.media3.ui.R.id.exo_progress)
        }
        secondView?.post {
            if (!isMaximized) {
                if (!isPortrait) {
                    secondView?.gone()
                }
            }
        }
    }

    private fun isViewHit(view: View, x: Int, y: Int): Boolean {
        val viewLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        val parentLocation = IntArray(2)
        getLocationOnScreen(parentLocation)
        val screenX = parentLocation[0] + x
        val screenY = parentLocation[1] + y
        return (screenX >= viewLocation[0]
                && screenX < viewLocation[0] + view.width
                && screenY >= viewLocation[1]
                && screenY < viewLocation[1] + view.height)
    }

    private fun animate(scaleX: Float, scaleY: Float) {
        val sclX = PropertyValuesHolder.ofFloat("scaleX", scaleX)
        val sclY = PropertyValuesHolder.ofFloat("scaleY", scaleY)
        ObjectAnimator.ofPropertyValuesHolder(this, sclX, sclY).apply {
            duration = ANIMATION_DURATION
            addListener(animatorListener)
            start()
        }
    }

    private fun smoothSlideTo(left: Int, top: Int) {
        if (viewDragHelper.smoothSlideViewTo(dragView, left, top)) {
            postInvalidateOnAnimation()
        }
    }

    private fun closeTo(left: Int) {
        smoothSlideTo(left, dragViewTop)
        listeners.forEach { it.onClose() }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun removeListeners() {
        listeners.clear()
    }

    fun updateBackgroundColor(isPortrait: Boolean) {
        if (isMaximized) {
            enableBackground(isPortrait)
        } else {
            disableBackground()
        }
    }

    private fun enableBackground(isPortrait: Boolean) {
        backgroundVisible = true
        (parent as View).setBackgroundColor(
            if (isPortrait) {
                backgroundColor ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && isLightTheme ?: context.isLightTheme.also { isLightTheme = it }) {
                    ContextCompat.getColor(context, R.color.darkScrimOnLightSurface)
                } else {
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
                }.also { backgroundColor = it }
            } else {
                Color.BLACK
            }
        )
        (parent as View).isClickable = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            context.getActivity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
    }

    private fun disableBackground() {
        backgroundVisible = false
        (parent as View).setBackgroundColor(Color.TRANSPARENT)
        (parent as View).isClickable = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            context.getActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
    }

    private inner class SlidingCallback : ViewDragHelper.Callback() {

        override fun tryCaptureView(child: View, pointerId: Int): Boolean {
            return child == dragView
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            return if (isMaximized) top.coerceAtLeast(topBound).coerceAtMost(bottomBound) else if (debug) top else child.top
        }

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            return if (!isMaximized) left else child.left
        }

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            dragViewTop = top
            dragViewLeft = left
            secondView?.let {
                it.top = if (isPortrait) dragView.measuredHeight + top else top
                it.bottom = height + top
            }
            if (!isAnimating) {
                if (isMaximized && top <= 10) {
                    if (!backgroundVisible) {
                        enableBackground(isPortrait)
                    }
                } else {
                    if (backgroundVisible) {
                        disableBackground()
                    }
                }
            }
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            if (isMaximized) {
                when {
                    releasedChild.top >= minimizeThreshold -> minimize()
                    else -> smoothSlideTo(0, 0)
                }
            } else {
                if (debug) {
                    pivotX += releasedChild.left
                    pivotY += releasedChild.top
                }
                when {
                    xvel > 1500 -> closeTo(width)
                    xvel < -1500 -> closeTo(-dragView.width)
                    else -> smoothSlideTo(0, 0)
                }
            }
        }
    }

    interface Listener {
        fun onMinimize()
        fun onMaximize()
        fun onClose()
    }
}