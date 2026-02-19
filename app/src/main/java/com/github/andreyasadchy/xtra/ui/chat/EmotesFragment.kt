package com.github.andreyasadchy.xtra.ui.chat

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
import com.github.andreyasadchy.xtra.databinding.FragmentEmotesBinding
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


@AndroidEntryPoint
class EmotesFragment : Fragment() {

    private var _binding: FragmentEmotesBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<ChatViewModel>(ownerProducer = { requireParentFragment() })
    private var recentEmotes = emptyList<RecentEmote>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val position = requireArguments().getInt(KEY_POSITION)
            val adapter = EmotesAdapter(
                this@EmotesFragment,
                { (parentFragment as? ChatFragment)?.appendEmote(it) },
                requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                requireContext().prefs().getString(C.CHAT_IMAGE_LIBRARY, "0")
            )
            root.itemAnimator = null
            root.adapter = adapter
            val columnWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics).toInt()
            root.layoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)
            when (position) {
                0 -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.recentEmotes.collectLatest {
                                if (it.isNotEmpty()) {
                                    recentEmotes = it
                                    updateList(position, adapter)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.userEmotesUpdated.collectLatest {
                                updateList(position, adapter)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.thirdPartyEmotesUpdated.collectLatest {
                                updateList(position, adapter)
                            }
                        }
                    }
                }
                1 -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.userEmotesUpdated.collectLatest {
                                updateList(position, adapter)
                            }
                        }
                    }
                    updateList(position, adapter)
                }
                else -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.thirdPartyEmotesUpdated.collectLatest {
                                updateList(position, adapter)
                            }
                        }
                    }
                    updateList(position, adapter)
                }
            }
        }
    }

    private fun updateList(position: Int, adapter: EmotesAdapter) {
        when (position) {
            0 -> {
                if (recentEmotes.isNotEmpty()) {
                    val personalEmotes = viewModel.userStvEmoteSetId?.let { setId ->
                        synchronized(viewModel.personalEmoteSets) {
                            viewModel.personalEmoteSets[setId]
                        }
                    } ?: emptyList()
                    val list = synchronized(viewModel.userEmotes) { viewModel.userEmotes } +
                            personalEmotes +
                            synchronized(viewModel.thirdPartyEmotes) { viewModel.thirdPartyEmotes }
                    adapter.submitList(recentEmotes.mapNotNull { emote -> list.find { it.name == emote.name } })
                }
            }
            1 -> {
                synchronized(viewModel.userEmotes) {
                    adapter.submitList(viewModel.userEmotes)
                }
            }
            else -> {
                val personalEmotes = viewModel.userStvEmoteSetId?.let { setId ->
                    synchronized(viewModel.personalEmoteSets) {
                        viewModel.personalEmoteSets[setId]
                    }
                } ?: emptyList()
                val list = personalEmotes + synchronized(viewModel.thirdPartyEmotes) {
                    viewModel.thirdPartyEmotes
                }
                adapter.submitList(list)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (binding.root.layoutManager as? GridAutofitLayoutManager)?.updateWidth()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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