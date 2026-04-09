@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.ui.stats.StatsLayoutHelper

class GridAutofitLayoutManager : GridLayoutManager {

    private var columnWidth = 0
    private var widthChanged = true

    constructor(context: Context, columnWidth: Int) : super(context, 1) {
        setColumnWidth(columnWidth)
    }

    constructor(context: Context, columnWidth: Int, orientation: Int, reverseLayout: Boolean) : super(context, 1, orientation, reverseLayout) {
        setColumnWidth(columnWidth)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State) {
        if (widthChanged && width > 0 && height > 0) {
            val totalSpace: Int = if (orientation == VERTICAL) {
                width - paddingRight - paddingLeft
            } else {
                height - paddingTop - paddingBottom
            }
            val spanCount = StatsLayoutHelper.calculateSpanCount(totalSpace, columnWidth)
            setSpanCount(spanCount)
            widthChanged = false
        }
        super.onLayoutChildren(recycler, state)
    }

    fun setColumnWidth(width: Int) {
        if (width <= 0) {
            throw IllegalArgumentException("Width should be more than 0. Provided $width")
        }
        if (columnWidth != width) {
            columnWidth = width
            widthChanged = true
        }
    }

    fun updateWidth() {
        widthChanged = true
    }
}
