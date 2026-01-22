package com.github.andreyasadchy.xtra.ui.common

import android.content.Intent
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
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class VideosAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Video) -> Unit,
    private val saveBookmark: (Video) -> Unit,
    private val showGame: Boolean = true,
    private val showChannel: Boolean = true,
) : PagingDataAdapter<Video, VideosAdapter.PagingViewHolder>(
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
        return PagingViewHolder(binding, fragment, showGame, showChannel)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private var positions: List<VideoPosition>? = null

    fun setVideoPositions(positions: List<VideoPosition>) {
        this.positions = positions
        if (itemCount != 0) {
            notifyDataSetChanged()
        }
    }

    private var bookmarks: List<Bookmark>? = null

    fun setBookmarksList(list: List<Bookmark>) {
        this.bookmarks = list
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val showChannel: Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Video?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val getDuration = item.duration?.let { TwitchApiHelper.getDuration(it) }
                    val position = item.id?.toLongOrNull()?.let { id -> positions?.find { it.id == id }?.position }
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startVideo(item, position)
                    }
                    root.setOnLongClickListener { showDownloadDialog(item); true }
                    fragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(fragment.requireContext()).apply {
                            data(item.thumbnail)
                            diskCachePolicy(CachePolicy.DISABLED)
                            crossfade(true)
                            target(thumbnail)
                        }.build()
                    )
                    if (item.uploadDate != null) {
                        val text = TwitchApiHelper.formatTimeString(context, item.uploadDate)
                        if (text != null) {
                            date.visibility = View.VISIBLE
                            date.text = text
                        } else {
                            date.visibility = View.GONE
                        }
                    } else {
                        date.visibility = View.GONE
                    }
                    if (item.viewCount != null) {
                        views.visibility = View.VISIBLE
                        val count = item.viewCount
                        views.text = context.resources.getQuantityString(
                            R.plurals.views,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                        )
                    } else {
                        views.visibility = View.GONE
                    }
                    if (getDuration != null) {
                        duration.visibility = View.VISIBLE
                        duration.text = DateUtils.formatElapsedTime(getDuration)
                    } else {
                        duration.visibility = View.GONE
                    }
                    if (item.type != null) {
                        val text = TwitchApiHelper.getType(context, item.type)
                        if (text != null) {
                            type.visibility = View.VISIBLE
                            type.text = text
                        } else {
                            type.visibility = View.GONE
                        }
                    } else {
                        type.visibility = View.GONE
                    }
                    if (position != null && getDuration != null && getDuration > 0L) {
                        progressBar.progress = (position / (getDuration * 10)).toInt()
                        progressBar.visibility = View.VISIBLE
                    } else {
                        progressBar.visibility = View.GONE
                    }
                    if (showChannel) {
                        val channelListener: (View) -> Unit = {
                            fragment.findNavController().navigate(
                                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                    channelId = item.channelId,
                                    channelLogin = item.channelLogin,
                                    channelName = item.channelName,
                                    channelLogo = item.channelLogo,
                                )
                            )
                        }
                        if (item.channelLogo != null) {
                            userImage.visibility = View.VISIBLE
                            fragment.requireContext().imageLoader.enqueue(
                                ImageRequest.Builder(fragment.requireContext()).apply {
                                    data(item.channelLogo)
                                    if (context.prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                        transformations(CircleCropTransformation())
                                    }
                                    crossfade(true)
                                    target(userImage)
                                }.build()
                            )
                            userImage.setOnClickListener(channelListener)
                        } else {
                            userImage.visibility = View.GONE
                        }
                        if (item.channelName != null) {
                            username.visibility = View.VISIBLE
                            username.text = if (item.channelLogin != null && !item.channelLogin.equals(item.channelName, true)) {
                                when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                    "0" -> "${item.channelName}(${item.channelLogin})"
                                    "1" -> item.channelName
                                    else -> item.channelLogin
                                }
                            } else {
                                item.channelName
                            }
                            username.setOnClickListener(channelListener)
                        } else {
                            username.visibility = View.GONE
                        }
                    } else {
                        userImage.visibility = View.GONE
                        username.visibility = View.GONE
                    }
                    if (item.title != null && item.title != "") {
                        title.visibility = View.VISIBLE
                        title.text = item.title.trim()
                    } else {
                        title.visibility = View.GONE
                    }
                    if (showGame && item.gameName != null) {
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
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                        gameName.setOnClickListener(gameListener)
                    } else {
                        gameName.visibility = View.GONE
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
                                when (it.itemId) {
                                    R.id.download -> showDownloadDialog(item)
                                    R.id.bookmark -> saveBookmark(item)
                                    R.id.share -> {
                                        context.startActivity(Intent.createChooser(Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/videos/${item.id}")
                                            item.title?.let {
                                                putExtra(Intent.EXTRA_TITLE, it)
                                            }
                                            type = "text/plain"
                                        }, null))
                                    }
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