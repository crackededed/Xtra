package com.github.andreyasadchy.xtra.ui.clips.common

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.loadImage
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import com.github.andreyasadchy.xtra.util.FragmentUtils

class ChannelClipsAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Clip) -> Unit) : PagingDataAdapter<Clip, ChannelClipsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Clip>() {
        override fun areItemsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.viewCount == newItem.viewCount &&
                    oldItem.title == newItem.title
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentVideosListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment): RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Clip?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val gameListener: (View) -> Unit = {
                        fragment.findNavController().navigate(
                            if (context.prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = item.gameId,
                                    gameSlug = item.gameSlug,
                                    gameName = item.gameName
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = item.gameId,
                                    gameSlug = item.gameSlug,
                                    gameName = item.gameName
                                )
                            }
                        )
                    }
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startClip(item)
                    }
                    root.setOnLongClickListener { showDownloadDialog(item); true }
                    thumbnail.loadImage(fragment, item.thumbnail, diskCacheStrategy = DiskCacheStrategy.NONE)
                    if (item.uploadDate != null) {
                        val text = item.uploadDate.let { TwitchApiHelper.formatTimeString(context, it) }
                        if (text != null) {
                            date.visible()
                            date.text = text
                        } else {
                            date.gone()
                        }
                    } else {
                        date.gone()
                    }
                    if (item.viewCount != null) {
                        views.visible()
                        views.text = TwitchApiHelper.formatViewsCount(context, item.viewCount)
                    } else {
                        views.gone()
                    }
                    if (item.duration != null) {
                        duration.visible()
                        duration.text = DateUtils.formatElapsedTime(item.duration.toLong())
                    } else {
                        duration.gone()
                    }
                    if (item.title != null && item.title != "")  {
                        title.visible()
                        title.text = item.title.trim()
                    } else {
                        title.gone()
                    }
                    if (item.gameName != null)  {
                        gameName.visible()
                        gameName.text = item.gameName
                        gameName.setOnClickListener(gameListener)
                    } else {
                        gameName.gone()
                    }
                    options.setOnClickListener { it ->
                        PopupMenu(context, it).apply {
                            inflate(R.menu.media_item)
                            setOnMenuItemClickListener {
                                when(it.itemId) {
                                    R.id.download -> showDownloadDialog(item)
                                    R.id.share -> FragmentUtils.shareLink(context, "https://twitch.tv/${item.channelLogin}/clip/${item.id}", item.title)
                                    else -> menu.close()
                                }
                                true
                            }
                            show()
                        }
                    }
                }
            }
        }
    }
}