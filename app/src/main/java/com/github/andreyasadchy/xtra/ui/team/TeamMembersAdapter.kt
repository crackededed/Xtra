package com.github.andreyasadchy.xtra.ui.team

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemCompactBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.R

class TeamMembersAdapter(
    private val fragment: Fragment,
    private val selectTag: (String) -> Unit,
) : PagingDataAdapter<Stream, TeamMembersAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Stream>() {
        override fun areItemsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.channelId == newItem.channelId

        override fun areContentsTheSame(oldItem: Stream, newItem: Stream): Boolean =
            oldItem.viewerCount == newItem.viewerCount &&
                    oldItem.gameName == newItem.gameName &&
                    oldItem.title == newItem.title
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamsListItemCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentStreamsListItemCompactBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Stream?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    val channelListener: (View) -> Unit = {
                        fragment.findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = item.channelId,
                                channelLogin = item.channelLogin,
                                channelName = item.channelName,
                                channelLogo = item.channelLogo,
                                streamId = item.id
                            )
                        )
                    }
                    if (item.channelLogo != null) {
                        userImage.visibility = View.VISIBLE
                        Glide.with(fragment)
                            .load(item.channelLogo)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .apply {
                                if (context.prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                                    circleCrop()
                                }
                            }
                            .into(userImage)
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
                    if (item.viewerCount != null) {
                        viewers.visibility = View.VISIBLE
                        viewers.text = TwitchApiHelper.formatCount(item.viewerCount ?: 0, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                        root.setOnClickListener {
                            (fragment.activity as MainActivity).startStream(item)
                        }
                        if (item.title != null && item.title != "") {
                            title.visibility = View.VISIBLE
                            title.text = item.title?.trim()
                        } else {
                            title.visibility = View.GONE
                        }
                        if (item.gameName != null) {
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
                        if (context.prefs().getBoolean(C.UI_UPTIME, true) && item.startedAt != null) {
                            val text = TwitchApiHelper.getUptime(startedAt = item.startedAt)
                            if (text != null) {
                                uptime.visibility = View.VISIBLE
                                uptime.text = text
                            } else {
                                uptime.visibility = View.GONE
                            }
                        } else {
                            uptime.visibility = View.GONE
                        }
                        if (!item.tags.isNullOrEmpty() && context.prefs().getBoolean(C.UI_TAGS, true)) {
                            tagsLayout.removeAllViews()
                            tagsLayout.visibility = View.VISIBLE
                            val tagsFlowLayout = Flow(context).apply {
                                layoutParams = ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    topToTop = tagsLayout.id
                                    bottomToBottom = tagsLayout.id
                                    startToStart = tagsLayout.id
                                    endToEnd = tagsLayout.id
                                }
                                setWrapMode(Flow.WRAP_CHAIN)
                            }
                            tagsLayout.addView(tagsFlowLayout)
                            val ids = mutableListOf<Int>()
                            for (tag in item.tags) {
                                val text = TextView(context)
                                val id = ViewCompat.generateViewId()
                                text.id = id
                                ids.add(id)
                                text.text = tag
                                context.obtainStyledAttributes(intArrayOf(R.attr.textAppearanceBodyMedium)).use {
                                    TextViewCompat.setTextAppearance(text, it.getResourceId(0, 0))
                                }
                                text.setOnClickListener {
                                    selectTag(tag)
                                }
                                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, context.resources.displayMetrics).toInt()
                                text.setPadding(padding, 0, padding, 0)
                                tagsLayout.addView(text)
                            }
                            tagsFlowLayout.referencedIds = ids.toIntArray()
                        } else {
                            tagsLayout.visibility = View.GONE
                        }
                    } else {
                        viewers.visibility = View.GONE
                        root.setOnClickListener(channelListener)
                        title.visibility = View.GONE
                        gameName.visibility = View.GONE
                        type.visibility = View.GONE
                        uptime.visibility = View.GONE
                        tagsLayout.visibility = View.GONE
                    }
                }
            }
        }
    }
}