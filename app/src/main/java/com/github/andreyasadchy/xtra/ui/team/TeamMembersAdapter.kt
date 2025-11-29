package com.github.andreyasadchy.xtra.ui.team

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemCompactBinding
import com.github.andreyasadchy.xtra.model.ui.TeamMember
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible

class TeamMembersAdapter(
    private val fragment: Fragment
) : PagingDataAdapter<TeamMember, TeamMembersAdapter.PagingViewHolder>(
    object: DiffUtil.ItemCallback<TeamMember>() {
        override fun areItemsTheSame(oldItem: TeamMember, newItem: TeamMember): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TeamMember, newItem: TeamMember): Boolean =
            oldItem.displayName == newItem.displayName &&
                    oldItem.login == newItem.login &&
                    oldItem.profileImageURL == newItem.profileImageURL
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TeamMembersAdapter.PagingViewHolder {
        val binding = FragmentStreamsListItemCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentStreamsListItemCompactBinding,
        private val fragment: Fragment
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TeamMember?) {
            with (binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val channelListener: (View) -> Unit = {
                        fragment.findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = item.id,
                                channelLogin = item.login,
                                channelName = item.displayName,
                                channelLogo = item.profileImageURL,
                                streamId = item.stream?.id
                            )
                        )
                    }
                    root.setOnClickListener(channelListener)

                    username.visible()
                    username.text = if (!item.login.equals(item.displayName, true)) {
                        when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${item.displayName}(${item.login})"
                            "1" -> item.displayName
                            else -> item.login
                        }
                    } else {
                        item.displayName
                    }
                    userImage.visible()
                    fragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(fragment.requireContext()).apply {
                            data(item.profileImageURL)
                            if (context.prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                transformations(CircleCropTransformation())
                            }
                            crossfade(true)
                            target(userImage)
                        }.build()
                    )
                    if (item.stream?.viewerCount != null) {
                        viewers.visible()
                        viewers.text = TwitchApiHelper.formatCount(
                            item.stream.viewerCount ?: 0,
                            context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true)
                        )
                    } else {
                        viewers.gone()
                    }
                }
            }
        }
    }

}