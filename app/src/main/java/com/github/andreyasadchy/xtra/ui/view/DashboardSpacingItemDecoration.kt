package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class DashboardSpacingItemDecoration(
    private val spacingPx: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return
        }

        val halfSpacing = spacingPx / 2
        outRect.left = halfSpacing
        outRect.right = halfSpacing
        outRect.bottom = spacingPx
        outRect.top = 0
    }
}
