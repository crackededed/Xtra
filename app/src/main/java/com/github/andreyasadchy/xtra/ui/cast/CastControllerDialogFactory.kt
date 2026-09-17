package com.github.andreyasadchy.xtra.ui.cast

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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

        override fun onStart() {
            super.onStart()
            addPlayCurrentButton()
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
    }
}