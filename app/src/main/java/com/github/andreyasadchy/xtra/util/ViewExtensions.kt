package com.github.andreyasadchy.xtra.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

fun View.gone() {
    visibility = View.GONE
}

fun View.toggleVisibility() = if (isVisible) gone() else visible()

@SuppressLint("CheckResult")
fun ImageView.loadImage(fragment: Fragment, url: String?, changes: Boolean = false, circle: Boolean = false, diskCacheStrategy: DiskCacheStrategy = DiskCacheStrategy.RESOURCE) {
    if (context.isActivityResumed) { //not enough on some devices?
        try {
            val request = Glide.with(fragment)
                .load(url)
                .diskCacheStrategy(diskCacheStrategy)
                .transition(DrawableTransitionOptions.withCrossFade())
            if (changes) {
                //update every 5 minutes
                val minutes = System.currentTimeMillis() / 60000L
                val lastMinute = minutes % 10
                val key = if (lastMinute < 5) minutes - lastMinute else minutes - (lastMinute - 5)
                request.signature(ObjectKey(key))
            }
            if (circle) {
                request.circleCrop()
            }
            request.into(this)
        } catch (e: IllegalArgumentException) {
        }
        return
    }
}

fun View.hideKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
}

val View.isKeyboardShown: Boolean
    get() {
        val rect = Rect()
        getWindowVisibleDisplayFrame(rect)
        val screenHeight = rootView.height

        // rect.bottom is the position above soft keypad or device button.
        // if keypad is shown, the r.bottom is smaller than that before.
        val keypadHeight = screenHeight - rect.bottom
        return keypadHeight > screenHeight * 0.15
    }

fun ImageView.enable() {
    isEnabled = true
    setColorFilter(Color.WHITE)
}

fun ImageView.disable() {
    isEnabled = false
    setColorFilter(Color.GRAY)
}

fun ViewPager2.reduceDragSensitivity() {
    try {
        val recyclerViewField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
        recyclerViewField.isAccessible = true
        val recyclerView = recyclerViewField.get(this) as RecyclerView

        val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
        touchSlopField.isAccessible = true
        val touchSlop = touchSlopField.get(recyclerView) as Int
        touchSlopField.set(recyclerView, touchSlop * 2)
    } catch (e: Exception) {
    }
}