package com.github.andreyasadchy.xtra.ui.common

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

class GamesAdapter(
    private val fragment: Fragment,
    private val selectTag: (Tag) -> Unit,
) : PagingDataAdapter<Game, GamesAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Game>() {
        override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.gameId == newItem.gameId

        override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.viewersCount == newItem.viewersCount
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentGamesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentGamesListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Game?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    root.setOnClickListener {
                        fragment.findNavController().navigate(
                            if (context.prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = item.gameId,
                                    gameSlug = item.gameSlug,
                                    gameName = item.gameName,
                                    boxArt = item.boxArt
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = item.gameId,
                                    gameSlug = item.gameSlug,
                                    gameName = item.gameName,
                                    boxArt = item.boxArt
                                )
                            }
                        )
                    }
                    if (item.boxArt != null) {
                        gameImage.visibility = View.VISIBLE
                        fragment.requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(fragment.requireContext()).apply {
                                data(item.boxArt)
                                crossfade(true)
                                target(gameImage)
                            }.build()
                        )
                    } else {
                        gameImage.visibility = View.GONE
                    }
                    if (item.gameName != null) {
                        gameName.visibility = View.VISIBLE
                        gameName.text = item.gameName
                    } else {
                        gameName.visibility = View.GONE
                    }
                    if (item.viewersCount != null) {
                        viewers.visibility = View.VISIBLE
                        val count = item.viewersCount ?: 0
                        viewers.text = context.resources.getQuantityString(
                            R.plurals.viewers,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                        )
                    } else {
                        viewers.visibility = View.GONE
                    }
                    if (item.broadcastersCount != null && context.prefs().getBoolean(C.UI_BROADCASTERSCOUNT, true)) {
                        broadcastersCount.visibility = View.VISIBLE
                        val count = item.broadcastersCount ?: 0
                        broadcastersCount.text = context.resources.getQuantityString(
                            R.plurals.broadcasters,
                            count,
                            TwitchApiHelper.formatCount(count, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                        )
                    } else {
                        broadcastersCount.visibility = View.GONE
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
                        for (tag in item.tags!!) {
                            val text = TextView(context)
                            val id = View.generateViewId()
                            text.id = id
                            ids.add(id)
                            text.text = tag.name
                            context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                                TextViewCompat.setTextAppearance(text, it.getResourceId(0, 0))
                            }
                            if (tag.id != null) {
                                text.setOnClickListener {
                                    selectTag(tag)
                                }
                            }
                            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, context.resources.displayMetrics).toInt()
                            text.setPadding(padding, 0, padding, 0)
                            tagsLayout.addView(text)
                        }
                        tagsFlowLayout.referencedIds = ids.toIntArray()
                    } else {
                        tagsLayout.visibility = View.GONE
                    }
                }
            }
        }
    }
}
