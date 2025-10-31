package com.github.andreyasadchy.xtra.ui.search.tags

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentSearchTagsBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TagSearchFragment : PagedListFragment() {

    private var _binding: FragmentSearchTagsBinding? = null
    private val binding get() = _binding!!
    private val args: TagSearchFragmentArgs by navArgs()
    private val viewModel: TagSearchViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Tag, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchTagsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = TagSearchAdapter(this, args)
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
        with(binding) {
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            searchView.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, searchView).show(WindowInsetsCompat.Type.ime())
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                if ((requireContext().prefs().getString(C.UI_NAVIGATION_TAB_LIST, null) ?: C.DEFAULT_NAVIGATION_TAB_LIST).split(',').all { it.split(':')[2] == "0" }) {
                    val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerViewLayout.recyclerView.updatePadding(bottom = systemBars.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    override fun initialize() {
        with(binding.recyclerViewLayout) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.flow.collectLatest { pagingData ->
                        pagingAdapter.submitData(pagingData)
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    pagingAdapter.loadStateFlow.collectLatest { loadState ->
                        progressBar.isVisible = loadState.refresh is LoadState.Loading && pagingAdapter.itemCount == 0
                        nothingHere.isVisible = loadState.refresh !is LoadState.Loading && pagingAdapter.itemCount == 0 && viewModel.query.value.isNotBlank()
                        if ((loadState.refresh as? LoadState.Error ?:
                            loadState.append as? LoadState.Error ?:
                            loadState.prepend as? LoadState.Error)?.error?.message == "failed integrity check" &&
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                            requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                        ) {
                            IntegrityDialog.show(childFragmentManager, "refresh")
                        }
                    }
                }
            }
        }
        if (requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
            requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true) &&
            TwitchApiHelper.isIntegrityTokenExpired(requireContext())
        ) {
            IntegrityDialog.show(childFragmentManager, "refresh")
        }
        with(binding) {
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                private var job: Job? = null

                override fun onQueryTextSubmit(query: String): Boolean {
                    search(query)
                    return false
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    job?.cancel()
                    if (newText.isNotEmpty()) {
                        job = lifecycleScope.launch {
                            delay(750)
                            withResumed {
                                search(newText)
                            }
                        }
                    } else {
                        search(newText)
                    }
                    return false
                }
            })
        }
    }

    private fun search(query: String) {
        viewModel.setQuery(query)
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
