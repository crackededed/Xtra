package com.github.andreyasadchy.xtra.ui.channel

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.andreyasadchy.xtra.ui.channel.about.ChannelAboutFragment
import com.github.andreyasadchy.xtra.ui.channel.clips.ChannelClipsFragment
import com.github.andreyasadchy.xtra.ui.channel.suggested.ChannelSuggestedFragment
import com.github.andreyasadchy.xtra.ui.channel.videos.ChannelVideosFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import kotlin.math.max

class ChannelPagerAdapter(
    private val fragment: Fragment,
    private val args: ChannelPagerFragmentArgs,
    private val tabs: List<String>,
) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment {
        return when (tabs.getOrNull(position)) {
            "0" -> ChannelSuggestedFragment().apply { arguments = fragment.arguments }
            "1" -> ChannelVideosFragment().apply { arguments = fragment.arguments }
            "2" -> ChannelClipsFragment().apply { arguments = fragment.arguments }
            "3" -> ChatFragment.newInstance(
                channelId = args.channelId,
                channelLogin = args.channelLogin,
                channelName = args.channelName,
                streamId = args.streamId
            )
            "4" -> ChannelAboutFragment().apply { arguments = fragment.arguments }
            else -> ChannelVideosFragment().apply { arguments = fragment.arguments }
        }
    }

    override fun getItemCount(): Int = max(tabs.size, 1)
}
