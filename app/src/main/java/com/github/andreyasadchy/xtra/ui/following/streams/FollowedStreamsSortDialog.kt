package com.github.andreyasadchy.xtra.ui.following.streams

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogFollowedStreamsSortBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FollowedStreamsSortDialog : BottomSheetDialogFragment() {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, order: String, orderText: CharSequence, changed: Boolean, saveDefault: Boolean)
    }

    companion object {
        const val ORDER_ASC = "asc"
        const val ORDER_DESC = "desc"
        const val SORT_VIEWER_COUNT = "viewer_count"
        const val SORT_STARTED_AT = "started_at"

        private const val SORT = "sort"
        private const val ORDER = "order"

        fun newInstance(sort: String?, order: String?): FollowedStreamsSortDialog {
            return FollowedStreamsSortDialog().apply {
                arguments = bundleOf(SORT to sort, ORDER to order)
            }
        }
    }

    private var _binding: DialogFollowedStreamsSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogFollowedStreamsSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            val args = requireArguments()
            val originalSortId = when (args.getString(SORT)) {
                SORT_VIEWER_COUNT -> R.id.viewer_count
                SORT_STARTED_AT -> R.id.started_at
                else -> R.id.viewer_count
            }
            val originalOrderId = when (args.getString(ORDER)) {
                ORDER_DESC -> R.id.newest_first
                ORDER_ASC -> R.id.oldest_first
                else -> R.id.newest_first
            }
            sort.check(originalSortId)
            order.check(originalOrderId)
            saveDefault.setOnClickListener {
                applyFilters(originalSortId, originalOrderId, true)
                dismiss()
            }
            apply.setOnClickListener {
                applyFilters(originalSortId, originalOrderId, false)
                dismiss()
            }
        }
    }

    private fun applyFilters(originalSortId: Int, originalOrderId: Int, saveDefault: Boolean) {
        with(binding) {
            val checkedSortId = sort.checkedRadioButtonId
            val checkedOrderId = order.checkedRadioButtonId
            val sortBtn = requireView().findViewById<RadioButton>(checkedSortId)
            val orderBtn = requireView().findViewById<RadioButton>(checkedOrderId)
            listener.onChange(
                when (checkedSortId) {
                    R.id.viewer_count -> SORT_VIEWER_COUNT
                    R.id.started_at -> SORT_STARTED_AT
                    else -> SORT_VIEWER_COUNT
                },
                sortBtn.text,
                when (checkedOrderId) {
                    R.id.newest_first -> ORDER_DESC
                    R.id.oldest_first -> ORDER_ASC
                    else -> ORDER_DESC
                },
                orderBtn.text,
                checkedSortId != originalSortId || checkedOrderId != originalOrderId,
                saveDefault
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}