package com.github.andreyasadchy.xtra.ui.common

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
        fun onChange(sort: String, sortText: CharSequence, changed: Boolean, saveDefault: Boolean)
    }

    companion object {
        const val SORT_CREATED_AT = "CREATED_AT"
        const val SORT_CREATED_AT_ASC = "CREATED_AT_ASC"
        const val SORT_EXPIRED_AT = "EXPIRED_AT"
        const val SORT_EXPIRED_AT_ASC = "EXPIRED_AT_ASC"
        const val SORT_SAVED_AT = "SAVED_AT"
        const val SORT_SAVED_AT_ASC = "SAVED_AT_ASC"

        private const val SORT = "sort"

        fun newInstance(sort: String?): BookmarksSortDialog {
            return BookmarksSortDialog().apply {
                arguments = bundleOf(SORT to sort)
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
            val originalSortId = when(args.getString(SORT)) {
                SORT_EXPIRED_AT -> R.id.expired_at
                SORT_EXPIRED_AT_ASC -> R.id.expired_at_asc
                SORT_CREATED_AT -> R.id.created_at
                SORT_CREATED_AT_ASC -> R.id.created_at_asc
                SORT_SAVED_AT -> R.id.saved_at
                SORT_SAVED_AT_ASC -> R.id.saved_at_asc
                else -> R.id.created_at_asc
            }
            sort.check(originalSortId)
            saveDefault.setOnClickListener {
                applyFilters(originalSortId, saveDefault = true)
                dismiss()
            }
            apply.setOnClickListener {
                applyFilters(originalSortId, saveDefault = false)
                dismiss()
            }
        }
    }

    private fun applyFilters(originalSortId: Int, saveDefault: Boolean) {
        with (binding) {
            val checkedSortId = sort.checkedRadioButtonId
            val sortBtn = requireView().findViewById<RadioButton>(checkedSortId)
            listener.onChange(
                sort = when (checkedSortId) {
                    R.id.expired_at -> SORT_EXPIRED_AT
                    R.id.expired_at_asc -> SORT_EXPIRED_AT_ASC
                    R.id.created_at -> SORT_CREATED_AT
                    R.id.created_at_asc -> SORT_CREATED_AT_ASC
                    R.id.saved_at -> SORT_SAVED_AT
                    R.id.saved_at_asc -> SORT_SAVED_AT_ASC
                    else -> SORT_EXPIRED_AT_ASC
                },
                sortBtn.text,
                checkedSortId != originalSortId,
                saveDefault
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}