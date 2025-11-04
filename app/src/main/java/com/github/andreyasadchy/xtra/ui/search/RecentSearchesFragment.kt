package com.github.andreyasadchy.xtra.ui.search

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentRecentSearchBinding
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecentSearchesFragment : Fragment() {
    private var _binding: FragmentRecentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<SearchPagerViewModel>(ownerProducer = { requireParentFragment() })
    private lateinit var onItemSelected: (String) -> Unit
    private lateinit var onItemInserted: (String) -> Unit
    private lateinit var onItemLongClicked: (RecentSearch) -> Unit

    override fun onAttach(context: Context) {
        super.onAttach(context)
        onItemSelected = (requireParentFragment() as SearchPagerFragment)::setSelectedQuery
        onItemInserted = (requireParentFragment() as SearchPagerFragment)::setInsertedQuery
        onItemLongClicked = { item ->
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.delete_recent_search_item)).setTitle(item.query)
                .setPositiveButton(getString(R.string.delete))
                { _: DialogInterface, _: Int ->
                    viewModel.deleteRecentSearch(item)
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .setCancelable(false)
                .create().show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recentSearchAdapter = RecentSearchAdapter(
            onItemSelected,
            onItemInserted,
            onItemLongClicked
        )

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentSearches.collectLatest {
                    recentSearchAdapter.submitList(it)
                }
            }
        }

        binding.recentSearchesList.adapter = recentSearchAdapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentRecentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}