package com.github.andreyasadchy.xtra.ui.search

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.andreyasadchy.xtra.ui.search.channels.ChannelSearchFragment
import com.github.andreyasadchy.xtra.ui.search.games.GameSearchFragment
import com.github.andreyasadchy.xtra.ui.search.streams.StreamSearchFragment
import com.github.andreyasadchy.xtra.ui.search.videos.VideoSearchFragment
import kotlin.math.max

class SearchPagerAdapter(
    fragment: Fragment,
    private val tabs: List<String>,
) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment {
        return when (tabs.getOrNull(position)) {
            "0" -> VideoSearchFragment()
            "1" -> StreamSearchFragment()
            "2" -> ChannelSearchFragment()
            "3" -> GameSearchFragment()
            else -> ChannelSearchFragment()
        }
    }

    override fun getItemCount(): Int = max(tabs.size, 1)
}
