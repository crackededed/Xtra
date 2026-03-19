package com.github.andreyasadchy.xtra.ui.search.channels

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentSearchChannelsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class ChannelSearchAdapter(
    private val fragment: Fragment,
) : PagingDataAdapter<User, ChannelSearchAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean = true
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentSearchChannelsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentSearchChannelsListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: User?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    root.setOnClickListener {
                        fragment.findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = item.id,
                                channelLogin = item.login,
                                channelName = item.name,
                                channelImage = item.profileImage,
                            )
                        )
                    }
                    if (item.profileImage != null) {
                        userImage.visibility = View.VISIBLE
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.profileImage)
                                if (context.prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                    transformations(CircleCropTransformation())
                                }
                                crossfade(true)
                                target(userImage)
                            }.build()
                        )
                    } else {
                        userImage.visibility = View.GONE
                    }
                    if (item.name != null) {
                        userName.visibility = View.VISIBLE
                        userName.text = if (item.login != null && !item.login.equals(item.name, true)) {
                            when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                "0" -> "${item.name}(${item.login})"
                                "1" -> item.name
                                else -> item.login
                            }
                        } else {
                            item.name
                        }
                    } else {
                        userName.visibility = View.GONE
                    }
                    if (item.followerCount != null) {
                        userFollowers.visibility = View.VISIBLE
                        val count = item.followerCount
                        userFollowers.text = context.resources.getQuantityString(
                            R.plurals.followers,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                        )
                    } else {
                        userFollowers.visibility = View.GONE
                    }
                    if (item.isLive == true) {
                        typeText.visibility = View.VISIBLE
                        typeText.text = context.getString(R.string.live)
                    } else {
                        typeText.visibility = View.GONE
                    }
                }
            }
        }
    }
}