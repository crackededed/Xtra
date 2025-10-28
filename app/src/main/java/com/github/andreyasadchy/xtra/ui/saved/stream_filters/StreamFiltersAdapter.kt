package com.github.andreyasadchy.xtra.ui.saved.stream_filters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentStreamFiltersListItemBinding
import com.github.andreyasadchy.xtra.model.ui.StreamFilter
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentDirections
import com.github.andreyasadchy.xtra.util.visible

class StreamFiltersAdapter(
    private val fragment: Fragment,
    private val deleteFilter: (StreamFilter) -> Unit,
) : PagingDataAdapter<StreamFilter, StreamFiltersAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<StreamFilter>() {
        override fun areItemsTheSame(
            oldItem: StreamFilter,
            newItem: StreamFilter
        ): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: StreamFilter,
            newItem: StreamFilter
        ): Boolean =
            oldItem.gameId == newItem.gameId &&
                    oldItem.tags == newItem.tags &&
                    oldItem.languages == newItem.languages

    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentStreamFiltersListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding, fragment)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PagingViewHolder(
        private val binding : FragmentStreamFiltersListItemBinding,
        private val fragment: Fragment,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StreamFilter?) {
            with(binding) {
                if (item != null) {
                    val context = fragment.requireContext()
                    root.setOnClickListener {
                        if (item.gameSlug != null && item.gameName != null) {
                            fragment.findNavController().navigate(
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = item.gameId,
                                    gameName = item.gameName,
                                    tags = item.tags?.split(",")?.toTypedArray<String>(),
                                    languages = item.languages?.split(",")?.toTypedArray()
                                )
                            )
                        } else {
                            fragment.findNavController().navigate(
                                TopStreamsFragmentDirections.actionGlobalTopFragment(
                                    tags = item.tags?.split(",")?.toTypedArray<String>(),
                                    languages = item.languages?.split(",")?.toTypedArray()
                                )
                            )
                        }
                    }
                    root.setOnLongClickListener { deleteFilter(item); true }
                    if (!item.tags.isNullOrEmpty()) {
                        val tags = item.tags.split(",")
                        tagsLabel.text = context.resources.getQuantityString(
                            R.plurals.tags,
                            tags.size,
                            tags.joinToString()
                        )
                        tagsLabel.visible()
                    }
                    if (!item.languages.isNullOrEmpty()) {
                        val languages = item.languages.split(",")
                        languagesLabel.text = context.resources.getQuantityString(
                            R.plurals.languages,
                            languages.size,
                            languages.joinToString()
                        )
                        languagesLabel.visible()
                    }
                    if (item.gameName != null) {
                        gameName.text = item.gameName
                        gameName.visible()
                    }
                    options.setOnClickListener { it ->
                        PopupMenu(context, it).apply {
                            inflate(R.menu.bookmark_item)
                            setOnMenuItemClickListener {
                                when(it.itemId) {
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