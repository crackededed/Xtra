package com.github.andreyasadchy.xtra.ui.saved

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.andreyasadchy.xtra.ui.saved.bookmarks.BookmarksFragment
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.saved.filters.FiltersFragment
import kotlin.math.max

class SavedPagerAdapter(
    fragment: Fragment,
    private val tabs: List<String>,
) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment {
        return when (tabs.getOrNull(position)) {
            "0" -> BookmarksFragment()
            "1" -> DownloadsFragment()
            "2" -> FiltersFragment()
            else -> BookmarksFragment()
        }
    }

    override fun getItemCount(): Int = max(tabs.size, 1)
}
