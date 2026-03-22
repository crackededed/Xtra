package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.res.use
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment


class RadioButtonDialogFragment : BottomSheetDialogFragment() {

    interface OnSortOptionChanged {
        fun onChange(requestCode: Int, index: Int, text: CharSequence, tag: String?)
    }

    companion object {

        private const val REQUEST_CODE = "requestCode"
        private const val LABELS = "labels"
        private const val TAGS = "tags"
        private const val CHECKED = "checked"

        fun newInstance(requestCode: Int, labels: Collection<CharSequence>, tags: Array<String>? = null, checkedIndex: Int): RadioButtonDialogFragment {
            return RadioButtonDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(REQUEST_CODE, requestCode)
                    putCharSequenceArrayList(LABELS, ArrayList(labels))
                    putStringArray(TAGS, tags)
                    putInt(CHECKED, checkedIndex)
                }
            }
        }
    }

    private lateinit var listenerSort: OnSortOptionChanged

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listenerSort = parentFragment as OnSortOptionChanged
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()
        val arguments = requireArguments()
        val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val radioGroup = RadioGroup(context).apply {
            layoutParams = params
            context.obtainStyledAttributes(intArrayOf(R.attr.dialogLayoutPadding)).use {
                setPadding(it.getDimensionPixelSize(0, 0))
            }
        }
        val checkedId = arguments.getInt(CHECKED)
        val clickListener = View.OnClickListener { v ->
            val clickedId = v.id
            if (clickedId != checkedId) {
                listenerSort.onChange(arguments.getInt(REQUEST_CODE), clickedId, (v as RadioButton).text, v.tag as String?)
            }
            dismiss()
        }
        val tags = arguments.getStringArray(TAGS)
        arguments.getCharSequenceArrayList(LABELS)?.forEachIndexed { index, label ->
            val button = AppCompatRadioButton(context).apply {
                id = index
                text = label
                tag = tags?.getOrNull(index)
                setOnClickListener(clickListener)
            }
            radioGroup.addView(button, params)
        }
        radioGroup.check(checkedId)
        return NestedScrollView(context).apply { addView(radioGroup) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}