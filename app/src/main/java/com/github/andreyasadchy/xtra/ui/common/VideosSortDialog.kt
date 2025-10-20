package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogVideosSortBinding
import com.github.andreyasadchy.xtra.ui.channel.clips.ChannelClipsFragment
import com.github.andreyasadchy.xtra.ui.channel.videos.ChannelVideosFragment
import com.github.andreyasadchy.xtra.ui.following.videos.FollowedVideosFragment
import com.github.andreyasadchy.xtra.ui.game.clips.GameClipsFragment
import com.github.andreyasadchy.xtra.ui.game.videos.GameVideosFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.gone
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class VideosSortDialog : BottomSheetDialogFragment(), SelectLanguagesDialog.OnSelectedLanguagesChanged {

    interface OnFilter {
        fun onChange(sort: String, sortText: CharSequence, period: String, periodText: CharSequence, type: String, typeText: CharSequence, languages: Array<String>, saveSort: Boolean, saveDefault: Boolean)
    }

    companion object {
        const val PERIOD_DAY = "day"
        const val PERIOD_WEEK = "week"
        const val PERIOD_MONTH = "month"
        const val PERIOD_ALL = "all"
        const val SORT_TIME = "time"
        const val SORT_VIEWS = "views"
        const val VIDEO_TYPE_ALL = "all"
        const val VIDEO_TYPE_ARCHIVE = "archive"
        const val VIDEO_TYPE_HIGHLIGHT = "highlight"
        const val VIDEO_TYPE_UPLOAD = "upload"

        private const val SORT = "sort"
        private const val PERIOD = "period"
        private const val TYPE = "type"
        private const val LANGUAGES = "languages"
        private const val SAVE_SORT = "save_sort"
        private const val SAVE_DEFAULT = "save_default"

        fun newInstance(sort: String? = SORT_TIME, period: String? = PERIOD_WEEK, type: String? = VIDEO_TYPE_ALL, languages: Array<String>? = null, saveSort: Boolean? = false, saveDefault: Boolean? = false): VideosSortDialog {
            return VideosSortDialog().apply {
                arguments = bundleOf(
                    SORT to sort,
                    PERIOD to period,
                    TYPE to type,
                    LANGUAGES to languages,
                    SAVE_SORT to saveSort,
                    SAVE_DEFAULT to saveDefault,
                )
            }
        }
    }

    private var _binding: DialogVideosSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    private var selectedLanguages: Array<String> = emptyArray()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogVideosSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            val args = requireArguments()
            when (parentFragment) {
                is ChannelClipsFragment -> {
                    sort.gone()
                    sortType.gone()
                    selectLanguages.gone()
                    saveSort.text = requireContext().getString(R.string.save_sort_channel)
                    saveSort.isVisible = parentFragment?.arguments?.getString(C.CHANNEL_ID).isNullOrBlank() == false
                }
                is GameClipsFragment -> {
                    sort.gone()
                    sortType.gone()
                    saveSort.text = requireContext().getString(R.string.save_sort_game)
                    saveSort.isVisible = parentFragment?.arguments?.getString(C.GAME_ID).isNullOrBlank() == false
                }
                is ChannelVideosFragment -> {
                    period.gone()
                    selectLanguages.gone()
                    saveSort.text = requireContext().getString(R.string.save_sort_channel)
                    saveSort.isVisible = parentFragment?.arguments?.getString(C.CHANNEL_ID).isNullOrBlank() == false
                }
                is FollowedVideosFragment -> {
                    period.gone()
                    selectLanguages.gone()
                    saveSort.gone()
                }
                is GameVideosFragment -> {
                    if (TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()) {
                        period.gone()
                    }
                    saveSort.text = requireContext().getString(R.string.save_sort_game)
                    saveSort.isVisible = parentFragment?.arguments?.getString(C.GAME_ID).isNullOrBlank() == false
                }
            }
            val originalSortId = when (args.getString(SORT)) {
                SORT_TIME -> R.id.time
                SORT_VIEWS -> R.id.views
                else -> R.id.time
            }
            val originalPeriodId = when (args.getString(PERIOD)) {
                PERIOD_DAY -> R.id.today
                PERIOD_WEEK -> R.id.week
                PERIOD_MONTH -> R.id.month
                PERIOD_ALL -> R.id.all
                else -> R.id.week
            }
            val originalTypeId = when (args.getString(TYPE)) {
                VIDEO_TYPE_ALL -> R.id.typeAll
                VIDEO_TYPE_ARCHIVE -> R.id.typeArchive
                VIDEO_TYPE_HIGHLIGHT -> R.id.typeHighlight
                VIDEO_TYPE_UPLOAD -> R.id.typeUpload
                else -> R.id.typeAll
            }
            val originalLanguages = args.getStringArray(LANGUAGES) ?: emptyArray()
            val originalSaveSort = args.getBoolean(SAVE_SORT)
            val originalSaveDefault = args.getBoolean(SAVE_DEFAULT)
            sort.check(originalSortId)
            period.check(originalPeriodId)
            sortType.check(originalTypeId)
            selectedLanguages = originalLanguages
            saveSort.isChecked = originalSaveSort
            saveDefault.isChecked = originalSaveDefault
            apply.setOnClickListener {
                val checkedPeriodId = period.checkedRadioButtonId
                val checkedSortId = sort.checkedRadioButtonId
                val checkedTypeId = sortType.checkedRadioButtonId
                val checkedSaveSort = saveSort.isChecked
                val checkedSaveDefault = saveDefault.isChecked
                if (checkedPeriodId != originalPeriodId ||
                    checkedSortId != originalSortId ||
                    checkedTypeId != originalTypeId ||
                    !selectedLanguages.contentEquals(originalLanguages) ||
                    checkedSaveSort != originalSaveSort ||
                    checkedSaveDefault != originalSaveDefault
                ) {
                    val sortBtn = view.findViewById<RadioButton>(checkedSortId)
                    val periodBtn = view.findViewById<RadioButton>(checkedPeriodId)
                    val typeBtn = view.findViewById<RadioButton>(checkedTypeId)
                    listener.onChange(
                        when (checkedSortId) {
                            R.id.time -> SORT_TIME
                            R.id.views -> SORT_VIEWS
                            else -> SORT_TIME
                        },
                        sortBtn.text,
                        when (checkedPeriodId) {
                            R.id.today -> PERIOD_DAY
                            R.id.week -> PERIOD_WEEK
                            R.id.month -> PERIOD_MONTH
                            R.id.all -> PERIOD_ALL
                            else -> PERIOD_WEEK
                        },
                        periodBtn.text,
                        when (checkedTypeId) {
                            R.id.typeAll -> VIDEO_TYPE_ALL
                            R.id.typeArchive -> VIDEO_TYPE_ARCHIVE
                            R.id.typeHighlight -> VIDEO_TYPE_HIGHLIGHT
                            R.id.typeUpload -> VIDEO_TYPE_UPLOAD
                            else -> VIDEO_TYPE_ALL
                        },
                        typeBtn.text,
                        selectedLanguages,
                        checkedSaveSort,
                        checkedSaveDefault
                    )
                }
                dismiss()
            }
            selectLanguages.setOnClickListener {
                SelectLanguagesDialog.newInstance(selectedLanguages).show(childFragmentManager, "closeOnPip")
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
