package com.github.andreyasadchy.xtra.ui.common

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView
import com.google.android.material.color.MaterialColors

internal fun styleStreamTag(textView: TextView) {
    val context = textView.context
    val density = context.resources.displayMetrics.density
    val fillColor = MaterialColors.getColor(
        textView,
        com.google.android.material.R.attr.colorSecondaryContainer
    )
    val textColor = MaterialColors.getColor(
        textView,
        com.google.android.material.R.attr.colorOnSecondaryContainer
    )
    val strokeColor = MaterialColors.getColor(
        textView,
        com.google.android.material.R.attr.colorOutlineVariant
    )

    textView.setTextColor(textColor)
    textView.textSize = 11f
    textView.includeFontPadding = false
    textView.minHeight = (26 * density).toInt()
    textView.setPadding(
        (7 * density).toInt(),
        (3 * density).toInt(),
        (7 * density).toInt(),
        (3 * density).toInt()
    )
    textView.background = GradientDrawable().apply {
        cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            context.resources.displayMetrics
        )
        setColor(fillColor)
        setStroke((1 * density).toInt(), strokeColor)
    }
}
