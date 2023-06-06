package com.github.andreyasadchy.xtra.ui.download

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogVideoDownloadBinding
import com.github.andreyasadchy.xtra.model.VideoDownloadInfo
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VideoDownloadDialog : BaseDownloadDialog() {

    companion object {
        private const val KEY_VIDEO_INFO = "videoInfo"
        private const val KEY_VIDEO = "video"

        fun newInstance(videoInfo: VideoDownloadInfo): VideoDownloadDialog {
            return VideoDownloadDialog().apply { arguments = bundleOf(KEY_VIDEO_INFO to videoInfo) }
        }

        fun newInstance(video: Video): VideoDownloadDialog {
            return VideoDownloadDialog().apply { arguments = bundleOf(KEY_VIDEO to video) }
        }
    }

    private var _binding: DialogVideoDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoDownloadViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogVideoDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel.videoInfo.observe(viewLifecycleOwner) {
            if (it != null) {
                ((requireView() as NestedScrollView).children.first() as ConstraintLayout).children.forEach { v -> v.isVisible = v.id != R.id.progressBar && v.id != R.id.storageSelectionContainer }
                init(it)
            } else {
                dismiss()
            }
        }
        requireArguments().getParcelable<VideoDownloadInfo?>(KEY_VIDEO_INFO).let {
            if (it == null) {
                viewModel.setVideo(
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), requireContext().prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                    video = requireArguments().getParcelable(KEY_VIDEO)!!,
                    playerType = requireContext().prefs().getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                    skipAccessToken = requireContext().prefs().getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                )
            } else {
                viewModel.setVideoInfo(it)
            }
        }
    }

    private fun init(videoInfo: VideoDownloadInfo) {
        with(binding) {
            val context = requireContext()
            init(context, storageSelectionContainer)
            with(videoInfo) {
                spinner.adapter = ArrayAdapter(context, R.layout.spinner_quality_item, qualities.keys.toTypedArray())
                with(DateUtils.formatElapsedTime(totalDuration / 1000L)) {
                    duration.text = context.getString(R.string.duration, this)
                    timeTo.hint = this.let { if (it.length != 5) it else "00:$it" }
                }
                timeFrom.hint = DateUtils.formatElapsedTime(currentPosition / 1000L).let { if (it.length == 5) "00:$it" else it }
                timeFrom.doOnTextChanged { text, _, _, _ -> if (text?.length == 8) timeTo.requestFocus() }
                addTextChangeListener(timeFrom)
                addTextChangeListener(timeTo)
                cancel.setOnClickListener { dismiss() }

                fun download() {
                    val from = parseTime(timeFrom) ?: return
                    val to = parseTime(timeTo) ?: return
                    when {
                        to > totalDuration -> {
                            timeTo.requestFocus()
                            timeTo.error = getString(R.string.to_is_longer)
                        }
                        from < to -> {
                            val fromIndex = if (from == 0L) {
                                0
                            } else {
                                val min = from - targetDuration
                                val tmpIndex = relativeStartTimes.binarySearch(comparison = { time ->
                                    when {
                                        time > from -> 1
                                        time < min -> -1
                                        else -> 0
                                    }
                                })
                                /***
                                 * If the item is not found by the binarySearch method, it will return a
                                 * negative value and the app will crash. On that case, the function
                                 * returns the inverted insertion point (-insertion point - 1).
                                 * */
                                if (tmpIndex < 0) -tmpIndex else tmpIndex

                            }
                            val toIndex = if (to in relativeStartTimes.last()..totalDuration) {
                                relativeStartTimes.lastIndex
                            } else {
                                val max = to + targetDuration
                                val tmpIndex= relativeStartTimes.binarySearch(comparison = { time ->
                                    when {
                                        time > max -> 1
                                        time < to -> -1
                                        else -> 0
                                    }
                                })
                                //Apply the same check to the toIndex result
                                if (tmpIndex < 0) -tmpIndex else tmpIndex
                            }
                            fun startDownload() {
                                val quality = spinner.selectedItem.toString()
                                val url = videoInfo.qualities.getValue(quality).substringBeforeLast('/') + "/"
                                viewModel.download(url, downloadPath, quality, fromIndex, toIndex)
                                dismiss()
                            }
                            startDownload()
                        }
                        from >= to -> {
                            timeFrom.requestFocus()
                            timeFrom.error = getString(R.string.from_is_greater)
                        }
                        else -> {
                            timeTo.requestFocus()
                            timeTo.error = getString(R.string.to_is_lesser)
                        }
                    }
                }
                timeTo.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        download()
                        true
                    } else {
                        false
                    }
                }
                download.setOnClickListener { download() }
            }
        }
    }

    private fun parseTime(textView: TextView): Long? {
        with(textView) {
            val value = if (text.isEmpty()) hint else text
            val time = value.split(':')
            try {
                if (time.size != 3) throw IllegalArgumentException()
                val hours = time[0].toLong()
                val minutes = time[1].toLong().also { if (it > 59) throw IllegalArgumentException()}
                val seconds = time[2].toLong().also { if (it > 59) throw IllegalArgumentException()}
                return ((hours * 3600) + (minutes * 60) + seconds) * 1000
            } catch (ex: Exception) {
                requestFocus()
                error = getString(R.string.invalid_time)
            }
        }
        return null
    }

    private fun addTextChangeListener(textView: TextView) {
        textView.addTextChangedListener(object : TextWatcher {
            private var lengthBeforeEdit = 0

            override fun afterTextChanged(s: Editable) {}
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                textView.error = null
                val length = s.length
                if (length == 2 || length == 5) {
                    if (lengthBeforeEdit < length) {
                        textView.append(":")
                    } else {
                        textView.editableText.delete(length - 1, length)
                    }
                }
                lengthBeforeEdit = length
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
