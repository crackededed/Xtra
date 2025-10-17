package com.github.andreyasadchy.xtra.ui.game.streams

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.StreamsAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.RECENT
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.SORT_VIEWERS
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog.Companion.SORT_VIEWERS_ASC
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GameStreamsFragment : PagedListFragment(), Scrollable, Sortable, StreamsSortDialog.OnFilter {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: GameStreamsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = if (requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
            StreamsCompactAdapter(this, args, showGame = false)
        } else {
            StreamsAdapter(this, args, showGame = false)
        }
        setAdapter(binding.recyclerView, pagingAdapter)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (requireContext().prefs().getStringSet(C.UI_NAVIGATION_TABS, resources.getStringArray(R.array.pageValues).toSet()).isNullOrEmpty()) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                viewModel.setFilter(viewModel.sort, args.languages ?: viewModel.languages)
            }
            viewModel.sortText.value = requireContext().getString(
                R.string.sort_by,
                requireContext().getString(
                    when (viewModel.sort) {
                        SORT_VIEWERS -> R.string.viewers_high
                        SORT_VIEWERS_ASC -> R.string.viewers_low
                        RECENT -> R.string.recent
                        else -> R.string.viewers_high
                    }
                )
            )
            viewModel.filtersText.value = buildString {
                args.tags?.takeIf { it.isNotEmpty() }?.let {
                    append(requireContext().getString(R.string.tags_list, it.joinToString(", ")))
                    append(" ")
                }
                if (viewModel.languages.isNotEmpty()) {
                    append(requireContext().getString(R.string.languages_list, viewModel.languages.joinToString(", ")))
                }
            }.trimEnd()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter, enableScrollTopButton = args.gameId != null || args.gameName != null || !args.tags.isNullOrEmpty())
    }

    override fun setupSortBar(sortBar: SortBarBinding) {
        sortBar.root.visible()
        sortBar.sortText.text = null
        sortBar.filtersText.text = null
        sortBar.root.setOnClickListener {
            StreamsSortDialog.newInstance(
                sort = viewModel.sort,
                languages = viewModel.languages
            ).show(childFragmentManager, null)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sortText.collectLatest {
                    sortBar.sortText.text = it
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.filtersText.collectLatest {
                    sortBar.filtersText.text = it
                }
            }
        }
    }

    override fun onChange(sort: String, languages: Array<String>) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                pagingAdapter.submitData(PagingData.empty())
                viewModel.setFilter(sort, languages)
                viewModel.sortText.value = requireContext().getString(
                    R.string.sort_by,
                    requireContext().getString(
                        when (viewModel.sort) {
                            SORT_VIEWERS -> R.string.viewers_high
                            SORT_VIEWERS_ASC -> R.string.viewers_low
                            RECENT -> R.string.recent
                            else -> R.string.viewers_high
                        }
                    )
                )
                viewModel.filtersText.value = buildString {
                    args.tags?.takeIf { it.isNotEmpty() }?.let {
                        append(
                            requireContext().getString(
                                R.string.tags_list,
                                it.joinToString(", ")
                            )
                        )
                        append(" ")
                    }
                    if (viewModel.languages.isNotEmpty()) {
                        append(
                            requireContext().getString(
                                R.string.languages_list,
                                viewModel.languages.joinToString(", ")
                            )
                        )
                    }
                }.trimEnd()
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        (parentFragment as? IntegrityDialog.CallbackListener)?.onIntegrityDialogCallback("refresh")
        if (callback == "refresh") {
            pagingAdapter.refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}