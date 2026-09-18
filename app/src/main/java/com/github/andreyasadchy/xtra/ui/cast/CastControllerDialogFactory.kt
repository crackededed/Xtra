package com.github.andreyasadchy.xtra.ui.cast

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

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
            styleTitleAndButtons()
        }

        private fun resolveThemeColor(attributeRes: Int): Int {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(attributeRes, value, true)) {
                return 0
            }
            return when {
                value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT -> value.data
                value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
                else -> 0
            }
        }

        override fun onStart() {
            super.onStart()
            addPlayCurrentButton()
            styleTitleAndButtons()
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus) {
                styleVolumeControls()
                styleTitleAndButtons()
            }
        }

        private fun styleTitleAndButtons() {
            val accent = accentColor ?: resolveAccentColor()?.also { accentColor = it } ?: return
            val background = volumeBackgroundColor ?: resolveVolumeBackgroundColor().also { volumeBackgroundColor = it }

            applyDialogBackground(background)
            findViewById<View>(androidx.mediarouter.R.id.mr_media_main_control)?.setBackgroundColor(Color.TRANSPARENT)
            styleTitleBar(accent)
            styleActionBar()
            stylePlayButton(accent)
        }

        private fun stylePlayButton(accent: Int) {
            playCurrentButton?.let { button ->
                (button as? TextView)?.setTextColor(ColorStateList.valueOf(accent))
            }
        }

        private fun applyDialogBackground(background: Int) {
            val radiusPx = resolveCornerRadiusPx()
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dialogBackground = GradientDrawable().apply {
                setColor(background)
                cornerRadius = radiusPx
            }
            val dialogArea = findViewById<View>(androidx.mediarouter.R.id.mr_dialog_area)
            if (dialogArea != null) {
                dialogArea.background = dialogBackground
            } else {
                window?.setBackgroundDrawable(dialogBackground)
            }
        }

        private fun resolveCornerRadiusPx(): Float {
            val cornerPreference = try {
                context.prefs().getString(C.UI_THEME_ROUNDED_CORNERS, "0")
            } catch (_: Exception) {
                "0"
            }
            if (cornerPreference == "2") {
                return 0f
            }
            val value = TypedValue()
            if (context.theme.resolveAttribute(com.google.android.material.R.attr.shapeCornerSizeExtraLarge, value, true) &&
                value.type == TypedValue.TYPE_DIMENSION
            ) {
                return TypedValue.complexToDimension(value.data, context.resources.displayMetrics)
            }
            return 28f * context.resources.displayMetrics.density
        }

        private fun styleTitleBar(accent: Int) {
            val titleBar = findTitleBarView()
            titleBar?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                setTextViewColors(it, accent)
            }
        }

        private fun styleActionBar() {
            val actionBar = findActionBarView()
            actionBar?.setBackgroundColor(Color.TRANSPARENT)
        }

        private fun findTitleBarView(): View? {
            val root = window?.decorView as? ViewGroup ?: return null
            val stopText = context.getString(androidx.mediarouter.R.string.mr_controller_stop_casting)
            val disconnectText = context.getString(androidx.mediarouter.R.string.mr_controller_disconnect)
            
            val queue = ArrayDeque<View>()
            queue.add(root)
            
            while (queue.isNotEmpty()) {
                val view = queue.removeFirst()
                if (view is ViewGroup) {
                    var hasDeviceName = false
                    var hasActionButton = false
                    
                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index)
                        when (child) {
                            is TextView -> {
                                val text = child.text?.toString() ?: continue
                                if (text.isNotEmpty() && text != stopText && text != disconnectText) {
                                    hasDeviceName = true
                                }
                            }
                            is Button -> {
                                if (child.visibility == View.VISIBLE &&
                                    (child.text?.toString() == stopText || child.text?.toString() == disconnectText)) {
                                    hasActionButton = true
                                }
                            }
                        }
                    }
                    
                    if (hasDeviceName && !hasActionButton) {
                        return view
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        queue.add(view.getChildAt(index))
                    }
                }
            }
            return null
        }

        private fun findActionBarView(): View? {
            val root = window?.decorView as? ViewGroup ?: return null
            val stopText = context.getString(androidx.mediarouter.R.string.mr_controller_stop_casting)
            val disconnectText = context.getString(androidx.mediarouter.R.string.mr_controller_disconnect)
            
            val queue = ArrayDeque<View>()
            queue.add(root)
            
            while (queue.isNotEmpty()) {
                val view = queue.removeFirst()
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index)
                        if (child is Button && child.visibility == View.VISIBLE &&
                            (child.text?.toString() == stopText || child.text?.toString() == disconnectText ||
                             child.text?.toString() == context.getString(R.string.play_current_stream))) {
                            return view
                        }
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        queue.add(view.getChildAt(index))
                    }
                }
            }
            return null
        }

        private fun setTextViewColors(view: View, color: Int) {
            if (view is TextView) {
                view.setTextColor(color)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    setTextViewColors(view.getChildAt(index), color)
                }
            }
        }

        private fun resolveAccentColor(): Int? {
            val color = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
            return if (color != 0) color else null
        }

        private fun resolveVolumeBackgroundColor(): Int {
            val surfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
            if (surfaceColor != 0) return surfaceColor

            val dialogArea = findViewById<View>(androidx.mediarouter.R.id.mr_dialog_area)
            val background = dialogArea?.background
            if (background is ColorDrawable) {
                return background.color
            }

            val isLightTheme = try {
                context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                    it.getBoolean(0, false)
                }
            } catch (_: Exception) {
                false
            }
            return if (isLightTheme) {
                ContextCompat.getColor(context, R.color.lightScrim)
            } else {
                ContextCompat.getColor(context, R.color.darkScrim)
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

        private fun addPlayCurrentButton() {
            if (playCurrentButton != null) return
            val castManager = (context.applicationContext as? XtraApp)?.xtraModule?.castManager ?: return
            val reference = findActionButton() ?: return
            val bar = reference.parent as? ViewGroup ?: return
            val button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = context.getText(R.string.play_current_stream)
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
