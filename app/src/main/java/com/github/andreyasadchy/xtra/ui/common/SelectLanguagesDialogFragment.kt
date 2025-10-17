package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.res.use
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment


class SelectLanguagesDialogFragment : BottomSheetDialogFragment() {

    interface OnSortOptionChanged {
        fun onChange(languages: Array<String>)
    }

    companion object {
        private const val CHECKED_LANGUAGES = "checked"

        fun newInstance(languages: Array<String>? = null): SelectLanguagesDialogFragment {
            return SelectLanguagesDialogFragment().apply {
                arguments = bundleOf(CHECKED_LANGUAGES to languages)
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

        val languageEntries = resources.getStringArray(R.array.gqlUserLanguageEntries)
        val languageValues = resources.getStringArray(R.array.gqlUserLanguageValues)

        val checked = arguments.getStringArray(CHECKED_LANGUAGES)?.toMutableList() ?: mutableListOf()
        val checkBoxGroup = LinearLayout(context).apply {
            layoutParams = params
            orientation = LinearLayout.VERTICAL
            context.obtainStyledAttributes(intArrayOf(R.attr.dialogLayoutPadding)).use {
                setPadding(it.getDimensionPixelSize(0, 0))
            }
            // ignoring the "All" entry
            languageEntries.drop(1).forEachIndexed { index, entry ->
                val value = languageValues[index + 1]
                val checkBox = AppCompatCheckBox(context).apply {
                    id = index
                    text = entry
                    isChecked = checked.contains(value)
                    setOnClickListener {
                        if (checked.contains(value)) {
                            checked.remove(value)
                        } else {
                            checked.add(value)
                        }
                        listenerSort.onChange(checked.toTypedArray())
                    }
                }
                addView(checkBox)
            }
        }
        return NestedScrollView(context).apply { addView(checkBoxGroup) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}