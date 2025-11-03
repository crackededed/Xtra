package com.github.andreyasadchy.xtra.ui.saved.filters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentFiltersListItemBinding
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible

class FiltersAdapter(
    private val fragment: Fragment,
    private val deleteFilter: (SavedFilter) -> Unit,
) : PagingDataAdapter<SavedFilter, FiltersAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<SavedFilter>() {
        override fun areItemsTheSame(oldItem: SavedFilter, newItem: SavedFilter): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SavedFilter, newItem: SavedFilter): Boolean =
            oldItem.gameId == newItem.gameId &&
                    oldItem.tags == newItem.tags &&
                    oldItem.languages == newItem.languages
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentFiltersListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding: FragmentFiltersListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SavedFilter?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    root.setOnClickListener {
                        if (item.gameId != null || item.gameSlug != null || item.gameName != null) {
                            fragment.findNavController().navigate(
                                if (context.prefs().getBoolean(C.UI_GAMEPAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameId = item.gameId,
                                        gameSlug = item.gameSlug,
                                        gameName = item.gameName,
                                        tags = item.tags?.split(',')?.toTypedArray(),
                                        languages = item.languages?.split(',')?.toTypedArray()
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameId = item.gameId,
                                        gameSlug = item.gameSlug,
                                        gameName = item.gameName,
                                        tags = item.tags?.split(',')?.toTypedArray(),
                                        languages = item.languages?.split(',')?.toTypedArray()
                                    )
                                }
                            )
                        } else {
                            fragment.findNavController().navigate(
                                TopStreamsFragmentDirections.actionGlobalTopFragment(
                                    tags = item.tags?.split(',')?.toTypedArray(),
                                    languages = item.languages?.split(',')?.toTypedArray()
                                )
                            )
                        }
                    }
                    root.setOnLongClickListener { deleteFilter(item); true }
                    if (item.gameName != null) {
                        gameName.visible()
                        gameName.text = item.gameName
                    } else {
                        gameName.gone()
                    }
                    if (item.tags != null) {
                        val list = item.tags.split(',')
                        tags.visible()
                        tags.text = context.resources.getQuantityString(R.plurals.tags, list.size, list.joinToString())
                    } else {
                        tags.gone()
                    }
                    if (item.languages != null) {
                        val list = item.languages.split(',')
                        languages.visible()
                        languages.text = context.resources.getQuantityString(R.plurals.languages, list.size, list.joinToString())
                    } else {
                        languages.gone()
                    }
                    options.setOnClickListener { it ->
                        PopupMenu(context, it).apply {
                            inflate(R.menu.bookmark_item)
                            setOnMenuItemClickListener {
                                when (it.itemId) {
                                    R.id.delete -> deleteFilter(item)
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