package com.github.andreyasadchy.xtra.ui.cast

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp

class CastControllerDialogFactory : MediaRouteDialogFactory() {

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment {
        return PlayCurrentControllerDialogFragment()
    }

    class PlayCurrentControllerDialogFragment : MediaRouteControllerDialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return onCreateControllerDialog(requireContext(), savedInstanceState)
        }

        override fun onCreateControllerDialog(
            context: Context,
            savedInstanceState: Bundle?,
        ): MediaRouteControllerDialog {
            return PlayCurrentControllerDialog(context)
        }
    }

    private class PlayCurrentControllerDialog(context: Context) : MediaRouteControllerDialog(context) {

        private var playCurrentButton: Button? = null
        private var accentColor: Int? = null
        private var volumeBackgroundColor: Int? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            styleVolumeControls()
        }

        override fun onStart() {
            super.onStart()
            addPlayCurrentButton()
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus) {
                styleVolumeControls()
            }
        }

        private fun styleVolumeControls() {
            val accent = accentColor ?: resolveAccentColor()?.also { accentColor = it } ?: return
            val background = volumeBackgroundColor ?: resolveVolumeBackgroundColor().also { volumeBackgroundColor = it }
            findViewById<View>(androidx.mediarouter.R.id.mr_media_main_control)?.setBackgroundColor(background)
            val volumeControl = findViewById<ViewGroup>(androidx.mediarouter.R.id.mr_volume_control)
            if (volumeControl != null) {
                for (index in 0 until volumeControl.childCount) {
                    val child = volumeControl.getChildAt(index)
                    if (child is ImageView) {
                        ImageViewCompat.setImageTintList(child, ColorStateList.valueOf(accent))
                    }
                }
            }
            val slider = findViewById<View>(androidx.mediarouter.R.id.mr_volume_slider) as? SeekBar
            if (slider != null) {
                slider.progressTintList = ColorStateList.valueOf(accent)
                slider.progressBackgroundTintList = ColorStateList.valueOf(background)
                slider.thumbTintList = ColorStateList.valueOf(accent)
                try {
                    val method = slider.javaClass.getMethod("setColor", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(slider, accent)
                } catch (_: Exception) {
                }
            }
        }

        private fun resolveAccentColor(): Int? {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) {
                return null
            }
            return when {
                value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT -> value.data
                value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
                else -> null
            }
        }

        private fun resolveVolumeBackgroundColor(): Int {
            return (findViewById<View>(androidx.mediarouter.R.id.mr_dialog_area)?.background as? ColorDrawable)?.color
                ?: DEFAULT_VOLUME_BACKGROUND
        }

        private fun addPlayCurrentButton() {
            if (playCurrentButton != null) return
            val castManager = (context.applicationContext as? XtraApp)?.xtraModule?.castManager ?: return
            val reference = findActionButton() ?: return
            val bar = reference.parent as? ViewGroup ?: return
            val button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = context.getText(R.string.play_current_stream)
                setTextColor(reference.currentTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, reference.textSize)
                typeface = reference.typeface
                transformationMethod = reference.transformationMethod
                setPadding(reference.paddingLeft, reference.paddingTop, reference.paddingRight, reference.paddingBottom)
                layoutParams = (reference.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
                    ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener { castManager.playCurrentRequest?.invoke() }
            }
            bar.addView(button, bar.indexOfChild(reference))
            playCurrentButton = button
        }

        private fun findActionButton(): Button? {
            val root = window?.decorView as? ViewGroup ?: return null
            val actionTexts = listOf(
                context.getString(androidx.mediarouter.R.string.mr_controller_stop_casting),
                context.getString(androidx.mediarouter.R.string.mr_controller_disconnect),
            )
            val queue = ArrayDeque<View>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val view = queue.removeFirst()
                if (view is Button && view.visibility == View.VISIBLE && actionTexts.contains(view.text?.toString())) {
                    return view
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        queue.add(view.getChildAt(index))
                    }
                }
            }
            return null
        }

        companion object {
            private const val DEFAULT_VOLUME_BACKGROUND = 0xFF303030.toInt()
        }
    }
}