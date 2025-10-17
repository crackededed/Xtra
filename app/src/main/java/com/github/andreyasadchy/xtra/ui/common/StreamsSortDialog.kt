package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogStreamsSortBinding
import com.github.andreyasadchy.xtra.ui.search.tags.TagSearchFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StreamsSortDialog : BottomSheetDialogFragment(), SelectLanguagesDialogFragment.OnSortOptionChanged {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, languages: Array<String>, languagesText: CharSequence)
    }

    companion object {
        const val SORT_VIEWERS = "VIEWER_COUNT"
        const val SORT_VIEWERS_ASC = "VIEWER_COUNT_ASC"
        const val RECENT = "RECENT"

        private const val SORT = "sort"
        private const val LANGUAGES = "languages"

        fun newInstance(sort: String?, languages: Array<String>?): StreamsSortDialog {
            return StreamsSortDialog().apply {
                arguments = bundleOf(SORT to sort, LANGUAGES to languages)
            }
        }
    }

    private var _binding: DialogStreamsSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter
    private lateinit var languages: Array<String>

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
            sort.check(originalSortId)
            val originalLanguages = args.getStringArray(LANGUAGES) ?: emptyArray()
            languages = originalLanguages
            apply.setOnClickListener {
                val checkedSortId = sort.checkedRadioButtonId
                if (checkedSortId != originalSortId || !originalLanguages.contentEquals(languages)) {
                    val sortBtn = view.findViewById<RadioButton>(checkedSortId)
                    listener.onChange(
                        when (checkedSortId) {
                            R.id.viewers_high -> SORT_VIEWERS
                            R.id.viewers_low -> SORT_VIEWERS_ASC
                            R.id.recent -> RECENT
                            else -> SORT_VIEWERS
                        },
                        sortBtn.text,
                        languages = languages,
                        "yyyy" // languages.joinToString(", ")
                    )
                }
                dismiss()
            }
            selectTags.setOnClickListener {
                findNavController().navigate(
                    TagSearchFragmentDirections.actionGlobalTagSearchFragment(
                        gameId = parentFragment?.arguments?.getString(C.GAME_ID),
                        gameSlug = parentFragment?.arguments?.getString(C.GAME_SLUG),
                        gameName = parentFragment?.arguments?.getString(C.GAME_NAME)
                    )
                )
                dismiss()
            }
            selectLangs.setOnClickListener {
                SelectLanguagesDialogFragment.newInstance(languages).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onChange(languages: Array<String>) {
        this.languages = languages
    }
}