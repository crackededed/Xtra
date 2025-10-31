package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogStreamsSortBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

class StreamsSortDialog : BottomSheetDialogFragment(), SearchTagsDialog.OnTagSelectedListener, SelectLanguagesDialog.OnSelectedLanguagesChanged {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, tags: Array<String>, languages: Array<String>)
    }

    companion object {
        const val SORT_VIEWERS = "VIEWER_COUNT"
        const val SORT_VIEWERS_ASC = "VIEWER_COUNT_ASC"
        const val RECENT = "RECENT"

        private const val SORT = "sort"
        private const val TAGS = "tags"
        private const val LANGUAGES = "languages"

        fun newInstance(sort: String?, tags: Array<String>?, languages: Array<String>?): StreamsSortDialog {
            return StreamsSortDialog().apply {
                arguments = bundleOf(SORT to sort, TAGS to tags, LANGUAGES to languages)
            }
        }
    }

    private var _binding: DialogStreamsSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    private var selectedTags = mutableListOf<String>()
    private var selectedLanguages: Array<String> = emptyArray()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogStreamsSortBinding.inflate(inflater, container, false)
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
                SORT_VIEWERS -> R.id.viewers_high
                SORT_VIEWERS_ASC -> R.id.viewers_low
                RECENT -> R.id.recent
                else -> R.id.viewers_high
            }
            val originalTags = args.getStringArray(TAGS) ?: emptyArray()
            val originalLanguages = args.getStringArray(LANGUAGES) ?: emptyArray()
            sort.check(originalSortId)
            selectedTags = originalTags.toMutableList()
            selectedLanguages = originalLanguages
            originalTags.forEach { name ->
                tagGroup.addView(
                    Chip(requireContext()).apply {
                        text = name
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            selectedTags.remove(name)
                            tagGroup.removeView(this)
                        }
                    }
                )
            }
            selectTags.setOnClickListener {
                SearchTagsDialog.newInstance(false).show(childFragmentManager, null)
            }
            selectLanguages.setOnClickListener {
                SelectLanguagesDialog.newInstance(selectedLanguages).show(childFragmentManager, "closeOnPip")
            }
            apply.setOnClickListener {
                val checkedSortId = sort.checkedRadioButtonId
                val tags = selectedTags.toTypedArray().sortedArray()
                if (checkedSortId != originalSortId ||
                    !tags.contentEquals(originalTags) ||
                    !selectedLanguages.contentEquals(originalLanguages)
                ) {
                    val sortBtn = view.findViewById<RadioButton>(checkedSortId)
                    listener.onChange(
                        when (checkedSortId) {
                            R.id.viewers_high -> SORT_VIEWERS
                            R.id.viewers_low -> SORT_VIEWERS_ASC
                            R.id.recent -> RECENT
                            else -> SORT_VIEWERS
                        },
                        sortBtn.text,
                        tags,
                        selectedLanguages
                    )
                }
                dismiss()
            }
        }
    }

    override fun onTagSelected(tag: Tag) {
        tag.name?.let { name ->
            if (!selectedTags.contains(name)) {
                selectedTags.add(name)
                binding.tagGroup.addView(
                    Chip(requireContext()).apply {
                        text = name
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            selectedTags.remove(name)
                            binding.tagGroup.removeView(this)
                        }
                    }
                )
            }
        }
    }

    override fun onChange(languages: Array<String>) {
        selectedLanguages = languages
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}