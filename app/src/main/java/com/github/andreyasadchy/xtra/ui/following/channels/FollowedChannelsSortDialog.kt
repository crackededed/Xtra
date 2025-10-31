package com.github.andreyasadchy.xtra.ui.following.channels

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogFollowedChannelsSortBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FollowedChannelsSortDialog : BottomSheetDialogFragment() {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, order: String, orderText: CharSequence, changed: Boolean, saveDefault: Boolean)
    }

    companion object {
        const val ORDER_ASC = "asc"
        const val ORDER_DESC = "desc"
        const val SORT_FOLLOWED_AT = "created_at"
        const val SORT_ALPHABETICALLY = "login"
        const val SORT_LAST_BROADCAST = "last_broadcast"

        private const val SORT = "sort"
        private const val ORDER = "order"

        fun newInstance(sort: String?, order: String?): FollowedChannelsSortDialog {
            return FollowedChannelsSortDialog().apply {
                arguments = bundleOf(SORT to sort, ORDER to order)
            }
        }
    }

    private var _binding: DialogFollowedChannelsSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogFollowedChannelsSortBinding.inflate(inflater, container, false)
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
                SORT_FOLLOWED_AT -> R.id.time_followed
                SORT_ALPHABETICALLY -> R.id.alphabetically
                SORT_LAST_BROADCAST -> R.id.last_broadcast
                else -> R.id.last_broadcast
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
                    R.id.time_followed -> SORT_FOLLOWED_AT
                    R.id.alphabetically -> SORT_ALPHABETICALLY
                    R.id.last_broadcast -> SORT_LAST_BROADCAST
                    else -> SORT_LAST_BROADCAST
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