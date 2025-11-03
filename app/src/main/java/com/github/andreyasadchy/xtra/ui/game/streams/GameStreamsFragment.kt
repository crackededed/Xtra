package com.github.andreyasadchy.xtra.ui.game.streams

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.model.ui.SortGame
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
import com.github.andreyasadchy.xtra.util.gone
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
            StreamsCompactAdapter(this, { addTag(it) }, showGame = false)
        } else {
            StreamsAdapter(this, { addTag(it) }, showGame = false)
        }
        setAdapter(binding.recyclerView, pagingAdapter)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                val sortValues = args.gameId?.let { viewModel.getSortGame(it) } ?: viewModel.getSortGame("default")
                viewModel.setFilter(
                    sort = sortValues?.streamSort,
                    tags = args.tags ?: sortValues?.streamTags?.split(',')?.toTypedArray(),
                    languages = args.languages ?: sortValues?.streamLanguages?.split(',')?.toTypedArray(),
                )
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
                viewModel.filtersText.value = if (viewModel.tags.isNotEmpty() || viewModel.languages.isNotEmpty()) {
                    buildString {
                        if (viewModel.tags.isNotEmpty()) {
                            append(
                                requireContext().resources.getQuantityString(
                                    R.plurals.tags,
                                    viewModel.tags.size,
                                    viewModel.tags.joinToString()
                                )
                            )
                        }
                        if (viewModel.languages.isNotEmpty()) {
                            if (isNotEmpty()) {
                                append(". ")
                            }
                            append(
                                requireContext().resources.getQuantityString(
                                    R.plurals.languages,
                                    viewModel.languages.size,
                                    viewModel.languages.joinToString()
                                )
                            )
                        }
                    }
                } else null
            }
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
        sortBar.root.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                StreamsSortDialog.newInstance(
                    sort = viewModel.sort,
                    tags = viewModel.tags,
                    languages = viewModel.languages,
                    saved = args.gameId?.let { viewModel.getSortGame(it) } != null
                ).show(childFragmentManager, null)
            }
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
                    if (it != null) {
                        sortBar.filtersText.visible()
                        sortBar.filtersText.text = it
                    } else {
                        sortBar.filtersText.gone()
                    }
                }
            }
        }
    }

    private fun addTag(tag: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            pagingAdapter.submitData(PagingData.empty())
            val tags = viewModel.tags.plus(tag).sortedArray()
            viewModel.setFilter(viewModel.sort, tags, viewModel.languages)
            viewModel.filtersText.value = buildString {
                if (viewModel.tags.isNotEmpty()) {
                    append(
                        requireContext().resources.getQuantityString(
                            R.plurals.tags,
                            viewModel.tags.size,
                            viewModel.tags.joinToString()
                        )
                    )
                }
                if (viewModel.languages.isNotEmpty()) {
                    if (isNotEmpty()) {
                        append(". ")
                    }
                    append(
                        requireContext().resources.getQuantityString(
                            R.plurals.languages,
                            viewModel.languages.size,
                            viewModel.languages.joinToString()
                        )
                    )
                }
            }
        }
    }

    override fun onChange(sort: String, sortText: CharSequence, tags: Array<String>, languages: Array<String>, changed: Boolean, saveFilters: Boolean, saveSort: Boolean, saveDefault: Boolean) {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (changed) {
                    pagingAdapter.submitData(PagingData.empty())
                    viewModel.setFilter(sort, tags, languages)
                    viewModel.sortText.value = requireContext().getString(R.string.sort_by, sortText)
                    viewModel.filtersText.value = if (viewModel.tags.isNotEmpty() || viewModel.languages.isNotEmpty()) {
                        buildString {
                            if (viewModel.tags.isNotEmpty()) {
                                append(
                                    requireContext().resources.getQuantityString(
                                        R.plurals.tags,
                                        viewModel.tags.size,
                                        viewModel.tags.joinToString()
                                    )
                                )
                            }
                            if (viewModel.languages.isNotEmpty()) {
                                if (isNotEmpty()) {
                                    append(". ")
                                }
                                append(
                                    requireContext().resources.getQuantityString(
                                        R.plurals.languages,
                                        viewModel.languages.size,
                                        viewModel.languages.joinToString()
                                    )
                                )
                            }
                        }
                    } else null
                }
                if (saveFilters && (tags.isNotEmpty() || languages.isNotEmpty())) {
                    viewModel.saveFilters(
                        SavedFilter(
                            gameId = args.gameId,
                            gameSlug = args.gameSlug,
                            gameName = args.gameName,
                            tags = tags.takeIf { it.isNotEmpty() }?.joinToString(","),
                            languages = languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                        )
                    )
                }
                if (saveSort) {
                    args.gameId?.let { id ->
                        val item = viewModel.getSortGame(id)?.apply {
                            streamSort = sort
                            streamTags = tags.takeIf { it.isNotEmpty() }?.joinToString(",")
                            streamLanguages = languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                        } ?: SortGame(
                            id = id,
                            streamSort = sort,
                            streamTags = tags.takeIf { it.isNotEmpty() }?.joinToString(","),
                            streamLanguages = languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                        )
                        viewModel.saveSortGame(item)
                    }
                }
                if (saveDefault) {
                    val item = viewModel.getSortGame("default")?.apply {
                        streamSort = sort
                        streamTags = tags.takeIf { it.isNotEmpty() }?.joinToString(",")
                        streamLanguages = languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                    } ?: SortGame(
                        id = "default",
                        streamSort = sort,
                        streamTags = tags.takeIf { it.isNotEmpty() }?.joinToString(","),
                        streamLanguages = languages.takeIf { it.isNotEmpty() }?.joinToString(",")
                    )
                    viewModel.saveSortGame(item)
                }
            }
        }
    }

    override fun deleteSavedSort() {
        if ((parentFragment as? FragmentHost)?.currentFragment == this) {
            viewLifecycleOwner.lifecycleScope.launch {
                args.gameId?.let { viewModel.getSortGame(it) }?.let { viewModel.deleteSortGame(it) }
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