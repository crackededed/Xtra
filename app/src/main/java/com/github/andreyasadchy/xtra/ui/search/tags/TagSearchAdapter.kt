package com.github.andreyasadchy.xtra.ui.search.tags

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.FragmentSearchChannelsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentDirections
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible

class TagSearchAdapter(
    private val fragment: Fragment,
    private val args: TagSearchFragmentArgs,
) : PagingDataAdapter<Tag, TagSearchAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<Tag>() {
        override fun areItemsTheSame(oldItem: Tag, newItem: Tag): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: Tag, newItem: Tag): Boolean = true
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentSearchChannelsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment, args)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentSearchChannelsListItemBinding,
        private val fragment: Fragment,
        private val args: TagSearchFragmentArgs,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Tag?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    if (item.name != null) {
                        userName.visible()
                        userName.text = item.name
                    } else {
                        userName.gone()
                    }
                    if (item.scope == "CATEGORY") {
                        if (item.id != null) {
                            root.setOnClickListener {
                                fragment.findNavController().navigate(
                                    GamesFragmentDirections.actionGlobalGamesFragment(
                                        tags = arrayOf(item.id)
                                    )
                                )
                            }
                        }
                    } else {
                        if (item.name != null) {
                            if (args.gameId != null && args.gameName != null) {
                                root.setOnClickListener {
                                    fragment.findNavController().navigate(
                                        if (context.prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                                gameId = args.gameId,
                                                gameSlug = args.gameSlug,
                                                gameName = args.gameName,
                                                tags = arrayOf(item.name),
                                                languages = args.languages,
                                            )
                                        } else {
                                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                                gameId = args.gameId,
                                                gameSlug = args.gameSlug,
                                                gameName = args.gameName,
                                                tags = arrayOf(item.name)
                                            )
                                        }
                                    )
                                }
                            } else {
                                root.setOnClickListener {
                                    fragment.findNavController().navigate(
                                        TopStreamsFragmentDirections.actionGlobalTopFragment(
                                            tags = arrayOf(item.name),
                                            languages = args.languages
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}