package com.github.andreyasadchy.xtra.ui.streams.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.StreamSortEnum
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.search.tags.TagSearchFragmentDirections
import com.github.andreyasadchy.xtra.ui.streams.StreamsAdapter
import com.github.andreyasadchy.xtra.ui.streams.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class StreamsFragment : PagedListFragment(), Scrollable, StreamsSortDialog.OnFilter {

    private var _binding: FragmentStreamsBinding? = null
    private val binding get() = _binding!!
    private val args: GamePagerFragmentArgs by navArgs()
    private val viewModel: StreamsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStreamsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = if (requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") == "all") {
            StreamsCompactAdapter(this, args, args.gameId != null || args.gameName != null)
        } else {
            StreamsAdapter(this, args, args.gameId != null || args.gameName != null)
        }
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        initializeAdapter(binding.recyclerViewLayout, pagingAdapter, viewModel.flow, enableScrollTopButton = args.gameId != null || args.gameName != null || !args.tags.isNullOrEmpty())
        with(binding) {
            sortBar.root.visible()
            if (args.gameId != null && args.gameName != null) {
                sortBar.root.setOnClickListener {
                    StreamsSortDialog.newInstance(
                        sort = viewModel.sort
                    ).show(childFragmentManager, null)
                }
            } else {
                sortBar.root.setOnClickListener { findNavController().navigate(TagSearchFragmentDirections.actionGlobalTagSearchFragment()) }
            }
        }
    }

    override fun onChange(sort: StreamSortEnum) {
        viewLifecycleOwner.lifecycleScope.launch {
            pagingAdapter.submitData(PagingData.empty())
            viewModel.filter(
                sort = sort
            )
        }
    }

    override fun scrollToTop() {
        binding.recyclerViewLayout.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}