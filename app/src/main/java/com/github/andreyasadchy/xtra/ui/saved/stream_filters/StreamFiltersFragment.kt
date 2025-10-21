package com.github.andreyasadchy.xtra.ui.saved.stream_filters

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
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.model.ui.StreamFilter
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class StreamFiltersFragment : PagedListFragment(), Scrollable {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StreamFiltersViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<StreamFilter, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = StreamFiltersAdapter(this, {
            val delete = getString(R.string.delete)
            requireActivity().getAlertDialogBuilder()
                .setTitle(delete)
                .setMessage(getString(R.string.are_you_sure_delete_filter))
                .setPositiveButton(delete) {_, _ -> viewModel.delete(it) }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        })
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        with (binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pagingAdapter.loadStateFlow.collectLatest { loadStates ->
                        nothingHere.text = getString(R.string.how_save_filter)
                    }
                }
            }
        }
        initializeAdapter(binding, pagingAdapter, enableScrollTopButton = false)
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
        pagingAdapter.retry()
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback == "refresh") {
            pagingAdapter.refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}