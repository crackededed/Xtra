package com.github.andreyasadchy.xtra.ui.common

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.DialogSearchTagsBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchTagsDialog : DialogFragment() {

    interface OnTagSelectedListener {
        fun onTagSelected(tag: Tag)
    }

    companion object {
        private const val GET_GAME_TAGS = "getGameTags"

        fun newInstance(getGameTags: Boolean): SearchTagsDialog {
            return SearchTagsDialog().apply {
                arguments = bundleOf(GET_GAME_TAGS to getGameTags)
            }
        }
    }

    private var _binding: DialogSearchTagsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchTagsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<Tag, out RecyclerView.ViewHolder>
    private var listener: OnTagSelectedListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? OnTagSelectedListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSearchTagsBinding.inflate(layoutInflater)
        val builder = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
        with(binding) {
            viewModel.getGameTags = requireArguments().getBoolean(GET_GAME_TAGS)
            pagingAdapter = SearchTagsAdapter { tag ->
                listener?.onTagSelected(tag)
                dismiss()
            }
            pagingAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    pagingAdapter.unregisterAdapterDataObserver(this)
                    pagingAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                            try {
                                if (positionStart == 0) {
                                    recyclerView.scrollToPosition(0)
                                }
                            } catch (e: Exception) {

                            }
                        }
                    })
                }
            })
            recyclerView.adapter = pagingAdapter
            searchView.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, searchView).show(WindowInsetsCompat.Type.ime())
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.flow.collectLatest { pagingData ->
                        pagingAdapter.submitData(pagingData)
                    }
                }
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                private var job: Job? = null

                override fun onQueryTextSubmit(query: String): Boolean {
                    viewModel.setQuery(query)
                    return false
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    job?.cancel()
                    if (newText.isNotEmpty()) {
                        job = lifecycleScope.launch {
                            delay(750)
                            withResumed {
                                viewModel.setQuery(newText)
                            }
                        }
                    } else {
                        viewModel.setQuery(newText)
                    }
                    return false
                }
            })
        }
        return builder.create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}