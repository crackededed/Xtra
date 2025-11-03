package com.github.andreyasadchy.xtra.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogSelectLanguagesBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SelectLanguagesDialog : BottomSheetDialogFragment() {

    interface OnSelectedLanguagesChanged {
        fun onChange(languages: Array<String>)
    }

    companion object {
        private const val SELECTED_LANGUAGES = "languages"

        fun newInstance(languages: Array<String>): SelectLanguagesDialog {
            return SelectLanguagesDialog().apply {
                arguments = bundleOf(SELECTED_LANGUAGES to languages)
            }
        }
    }

    private var _binding: DialogSelectLanguagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnSelectedLanguagesChanged

    private var selectedLanguages = mutableListOf<String>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnSelectedLanguagesChanged
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogSelectLanguagesBinding.inflate(inflater, container, false)
        requireArguments().getStringArray(SELECTED_LANGUAGES)?.let { selectedLanguages = it.toMutableList() }
        val languageEntries = resources.getStringArray(R.array.gqlUserLanguageEntries)
        resources.getStringArray(R.array.gqlUserLanguageValues).forEachIndexed { index, language ->
            binding.languageLayout.addView(AppCompatCheckBox(requireContext()).apply {
                id = index
                text = languageEntries[index]
                isChecked = selectedLanguages.contains(language)
                setOnClickListener {
                    if (selectedLanguages.contains(language)) {
                        selectedLanguages.remove(language)
                    } else {
                        selectedLanguages.add(language)
                    }
                }
            })
        }
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            languageScrollView.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> behavior.isDraggable = false
                    MotionEvent.ACTION_UP -> behavior.isDraggable = true
                }
                false
            }
            apply.setOnClickListener {
                listener.onChange(selectedLanguages.toTypedArray().sortedArray())
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}