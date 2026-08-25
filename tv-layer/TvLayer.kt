package com.github.andreyasadchy.xtra.tv

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Minimal TV-only focus adapter. Playback/business logic remains in Xtra. */
object TvLayer {
    fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun configure(root: View, context: Context) {
        if (!isTv(context)) return
        configureFocus(root)
    }

    private fun configureFocus(view: View) {
        if (view.isClickable || view is RecyclerView) {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnFocusChangeListener { focusedView, focused ->
                focusedView.animate()
                    .scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f)
                    .translationZ(if (focused) 8f else 0f)
                    .setDuration(100L)
                    .start()
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) configureFocus(view.getChildAt(i))
        }
    }
}
