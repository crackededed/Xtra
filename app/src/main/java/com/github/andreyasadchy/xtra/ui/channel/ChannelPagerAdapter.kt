package com.github.andreyasadchy.xtra.ui.channel

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.andreyasadchy.xtra.ui.channel.clips.ChannelClipsFragment
import com.github.andreyasadchy.xtra.ui.channel.suggested.ChannelSuggestedFragment
import com.github.andreyasadchy.xtra.ui.channel.videos.ChannelVideosFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment

class ChannelPagerAdapter(
    private val fragment: Fragment,
    private val args: ChannelPagerFragmentArgs,
) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChannelVideosFragment().apply { arguments = fragment.arguments }
            1 -> ChannelClipsFragment().apply { arguments = fragment.arguments }
            2 -> ChannelSuggestedFragment().apply { arguments = fragment.arguments }
            else -> ChatFragment.newInstance(
                channelId = args.channelId,
                channelLogin = args.channelLogin,
                channelName = args.channelName,
                streamId = args.streamId
            )
        }
    }

    override fun getItemCount(): Int = 4
}
