package com.github.andreyasadchy.xtra.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.visible

class PlayerGamesDialogAdapter(
    private val fragment: Fragment,
) : ListAdapter<Game, PlayerGamesDialogAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<Game>() {
        override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean =
            oldItem.vodPosition == newItem.vodPosition

        override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean = true
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FragmentGamesListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: FragmentGamesListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Game?) {
            with(binding) {
                val context = fragment.requireContext()
                root.setOnClickListener {
                    item?.vodPosition?.let { position ->
                        (fragment.parentFragment as? PlayerFragment)?.seek(position.toLong())
                    }
                    (fragment as? PlayerGamesDialog)?.dismiss()
                }
                if (item?.boxArt != null) {
                    gameImage.visible()
                    fragment.requireContext().imageLoader.enqueue(
                        ImageRequest.Builder(fragment.requireContext()).apply {
                            data(item.boxArt)
                            crossfade(true)
                            target(gameImage)
                        }.build()
                    )
                } else {
                    gameImage.gone()
                }
                if (item?.gameName != null) {
                    gameName.visible()
                    gameName.text = item.gameName
                } else {
                    gameName.gone()
                }
                val position = item?.vodPosition?.div(1000)?.toString()?.let { TwitchApiHelper.getDurationFromSeconds(context, it, true) }
                if (!position.isNullOrBlank()) {
                    viewers.visible()
                    viewers.text = context.getString(R.string.position, position)
                } else {
                    viewers.gone()
                }
                val duration = item?.vodDuration?.div(1000)?.toString()?.let { TwitchApiHelper.getDurationFromSeconds(context, it, true) }
                if (!duration.isNullOrBlank()) {
                    broadcastersCount.visible()
                    broadcastersCount.text = context.getString(R.string.duration, duration)
                } else {
                    broadcastersCount.gone()
                }
            }
        }
    }
}