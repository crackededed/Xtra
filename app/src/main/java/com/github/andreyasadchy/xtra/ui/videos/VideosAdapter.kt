package com.github.andreyasadchy.xtra.ui.videos

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.loadImage
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible

class VideosAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Video) -> Unit,
    private val saveBookmark: (Video) -> Unit,
    private val hideGame: Boolean = false) : BaseVideosAdapter<Video, VideosAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Video, newItem: Video): Boolean =
            oldItem.viewCount == newItem.viewCount &&
                    oldItem.thumbnailUrl == newItem.thumbnailUrl &&
                    oldItem.title == newItem.title &&
                    oldItem.duration == newItem.duration
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentVideosListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, hideGame)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
        private val hideGame: Boolean): RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Video?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val channelListener: (View) -> Unit = { fragment.findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = item.channelId,
                        channelLogin = item.channelLogin,
                        channelName = item.channelName,
                        channelLogo = item.channelLogo,
                    )) }
                    val gameListener: (View) -> Unit = {
                        fragment.findNavController().navigate(
                            if (context.prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = item.gameId,
                                    gameName = item.gameName
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = item.gameId,
                                    gameName = item.gameName
                                )
                            }
                        )
                    }
                    val getDuration = item.duration?.let { TwitchApiHelper.getDuration(it) }
                    val position = item.id?.toLongOrNull()?.let { positions?.get(it) }
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startVideo(item, position?.toDouble())
                    }
                    root.setOnLongClickListener { showDownloadDialog(item); true }
                    thumbnail.loadImage(fragment, item.thumbnail, diskCacheStrategy = DiskCacheStrategy.NONE)
                    if (item.uploadDate != null) {
                        val text = TwitchApiHelper.formatTimeString(context, item.uploadDate)
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
                    if (getDuration != null) {
                        duration.visible()
                        duration.text = DateUtils.formatElapsedTime(getDuration)
                    } else {
                        duration.gone()
                    }
                    if (item.type != null) {
                        val text = TwitchApiHelper.getType(context, item.type)
                        if (text != null) {
                            type.visible()
                            type.text = text
                        } else {
                            type.gone()
                        }
                    } else {
                        type.gone()
                    }
                    if (position != null && getDuration != null && getDuration > 0L) {
                        progressBar.progress = (position / (getDuration * 10)).toInt()
                        progressBar.visible()
                    } else {
                        progressBar.gone()
                    }
                    if (item.channelLogo != null)  {
                        userImage.visible()
                        userImage.loadImage(fragment, item.channelLogo, circle = true)
                        userImage.setOnClickListener(channelListener)
                    } else {
                        userImage.gone()
                    }
                    if (item.channelName != null)  {
                        username.visible()
                        username.text = item.channelName
                        username.setOnClickListener(channelListener)
                    } else {
                        username.gone()
                    }
                    if (item.title != null && item.title != "")  {
                        title.visible()
                        title.text = item.title.trim()
                    } else {
                        title.gone()
                    }
                    if (!hideGame && item.gameName != null)  {
                        gameName.visible()
                        gameName.text = item.gameName
                        gameName.setOnClickListener(gameListener)
                    } else {
                        gameName.gone()
                    }
                    if (!item.tags.isNullOrEmpty() && context.prefs().getBoolean(C.UI_TAGS, true)) {
                        tagsLayout.removeAllViews()
                        tagsLayout.visible()
                        for (tag in item.tags) {
                            val text = TextView(context)
                            text.text = tag.name
                            if (tag.name != null) {
                                text.setOnClickListener {
                                    fragment.findNavController().navigate(GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        tags = arrayOf(tag.name),
                                    ))
                                }
                            }
                            tagsLayout.addView(text)
                        }
                    } else {
                        tagsLayout.gone()
                    }
                    options.setOnClickListener { it ->
                        PopupMenu(context, it).apply {
                            inflate(R.menu.media_item)
                            if (!item.id.isNullOrBlank()) {
                                menu.findItem(R.id.bookmark).isVisible = true
                                if (bookmarks?.find { it.videoId == item.id } != null) {
                                    menu.findItem(R.id.bookmark).title = context.getString(R.string.remove_bookmark)
                                } else {
                                    menu.findItem(R.id.bookmark).title = context.getString(R.string.add_bookmark)
                                }
                            }
                            setOnMenuItemClickListener {
                                when(it.itemId) {
                                    R.id.download -> showDownloadDialog(item)
                                    R.id.bookmark -> saveBookmark(item)
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