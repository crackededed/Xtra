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
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible

class ClipsAdapter(
    private val fragment: Fragment,
    private val showDownloadDialog: (Clip) -> Unit,
    private val showGame: Boolean = true,
    private val showChannel: Boolean = true,
) : PagingDataAdapter<Clip, ClipsAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Clip>() {
        override fun areItemsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Clip, newItem: Clip): Boolean =
            oldItem.viewCount == newItem.viewCount &&
                    oldItem.title == newItem.title
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentVideosListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, showGame, showChannel)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentVideosListItemBinding,
        private val fragment: Fragment,
        private val showGame: Boolean,
        private val showChannel: Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Clip?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    root.setOnClickListener {
                        (fragment.activity as MainActivity).startClip(item)
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
                        val count = item.viewCount
                        views.text = context.resources.getQuantityString(
                            R.plurals.views,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                        )
                    } else {
                        views.gone()
                    }
                    if (item.duration != null) {
                        duration.visible()
                        duration.text = DateUtils.formatElapsedTime(item.duration.toLong())
                    } else {
                        duration.gone()
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
                            userImage.visible()
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
                            userImage.gone()
                        }
                        if (item.channelName != null) {
                            username.visible()
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
                            username.gone()
                        }
                    } else {
                        userImage.gone()
                        username.gone()
                    }
                    if (item.title != null && item.title != "") {
                        title.visible()
                        title.text = item.title.trim()
                    } else {
                        title.gone()
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
                                when (it.itemId) {
                                    R.id.download -> showDownloadDialog(item)
                                    R.id.share -> {
                                        context.startActivity(Intent.createChooser(Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "https://twitch.tv/${item.channelLogin}/clip/${item.id}")
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