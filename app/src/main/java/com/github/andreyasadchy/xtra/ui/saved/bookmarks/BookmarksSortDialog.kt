package com.github.andreyasadchy.xtra.ui.saved.bookmarks

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogBookmarksSortBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BookmarksSortDialog: BottomSheetDialogFragment() {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, order: String, orderText: CharSequence, changed: Boolean, saveDefault: Boolean)
    }

    companion object {
        const val ORDER_ASC = "asc"
        const val ORDER_DESC = "desc"
        const val SORT_EXPIRES_AT = "expires_at"
        const val SORT_CREATED_AT = "created_at"
        const val SORT_SAVED_AT = "saved_at"

        private const val SORT = "sort"
        private const val ORDER = "order"

        fun newInstance(sort: String?, order: String?): BookmarksSortDialog {
            return BookmarksSortDialog().apply {
                arguments = bundleOf(SORT to sort, ORDER to order)
            }
        }
    }

    private var _binding: DialogBookmarksSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = DialogBookmarksSortBinding.inflate(inflater, container, false)
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
                SORT_EXPIRES_AT -> R.id.deletion_date
                SORT_CREATED_AT -> R.id.creation_date
                SORT_SAVED_AT -> R.id.saved_date
                else -> R.id.saved_date
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
                    R.id.deletion_date -> SORT_EXPIRES_AT
                    R.id.creation_date -> SORT_CREATED_AT
                    R.id.saved_date -> SORT_SAVED_AT
                    else -> SORT_SAVED_AT
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