package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


@AndroidEntryPoint
class EmotesFragment : Fragment() {

    private lateinit var listener: (Emote) -> Unit
    private lateinit var layoutManager: GridAutofitLayoutManager

    private val viewModel by viewModels<ChatViewModel>(ownerProducer = { requireParentFragment() })

    private var recentEmotes = emptyList<RecentEmote>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = (requireParentFragment() as ChatFragment)::appendEmote
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_emotes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val args = requireArguments()
        val position = args.getInt(KEY_POSITION)
        val emotesAdapter = EmotesAdapter(
            this,
            listener,
            context.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
            context.prefs().getString(C.CHAT_IMAGE_LIBRARY, "0")
        )
        with(view as RecyclerView) {
            itemAnimator = null
            adapter = emotesAdapter
            val columnWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics).toInt()
            layoutManager = GridAutofitLayoutManager(context, columnWidth).also { this@EmotesFragment.layoutManager = it }
        }
        if (position == 1) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.userEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
        } else {
            if (position == 0) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.recentEmotes.collectLatest {
                            if (it.isNotEmpty()) {
                                recentEmotes = it
                                updateList(emotesAdapter)
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.userPersonalEmoteSet.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.globalStvEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.channelStvEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.globalBttvEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.channelBttvEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.globalFfzEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.channelFfzEmotes.collectLatest {
                        if (it != null) {
                            updateList(emotesAdapter)
                        }
                    }
                }
            }
        }
    }

    private fun updateList(emotesAdapter: EmotesAdapter) {
        when (requireArguments().getInt(KEY_POSITION)) {
            0 -> {
                if (recentEmotes.isNotEmpty()) {
                    val list = (viewModel.userEmotes.value ?: emptyList()) +
                            (viewModel.userPersonalEmoteSet.value?.second ?: emptyList()) +
                            (viewModel.channelStvEmotes.value ?: emptyList()) +
                            (viewModel.channelBttvEmotes.value ?: emptyList()) +
                            (viewModel.channelFfzEmotes.value ?: emptyList()) +
                            (viewModel.globalStvEmotes.value ?: emptyList()) +
                            (viewModel.globalBttvEmotes.value ?: emptyList()) +
                            (viewModel.globalFfzEmotes.value ?: emptyList())
                    emotesAdapter.submitList(recentEmotes.mapNotNull { emote -> list.find { it.name == emote.name } })
                }
            }
            1 -> emotesAdapter.submitList(viewModel.userEmotes.value ?: emptyList())
            else -> {
                emotesAdapter.submitList((viewModel.userPersonalEmoteSet.value?.second ?: emptyList()) +
                        (viewModel.channelStvEmotes.value ?: emptyList()) +
                        (viewModel.channelBttvEmotes.value ?: emptyList()) +
                        (viewModel.channelFfzEmotes.value ?: emptyList()) +
                        (viewModel.globalStvEmotes.value ?: emptyList()) +
                        (viewModel.globalBttvEmotes.value ?: emptyList()) +
                        (viewModel.globalFfzEmotes.value ?: emptyList())
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutManager.updateWidth()
    }

    companion object {
        private const val KEY_POSITION = "position"

        fun newInstance(position: Int): EmotesFragment {
            return EmotesFragment().apply {
                arguments = bundleOf(
                    KEY_POSITION to position
                )
            }
        }
    }
}