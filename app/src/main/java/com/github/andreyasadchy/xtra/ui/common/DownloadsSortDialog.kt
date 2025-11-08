package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogDownloadsSortBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DownloadsSortDialog : BottomSheetDialogFragment() {
    interface OnFilter {
        fun onChange(status: String, statusText: CharSequence, changed: Boolean, saveDefault: Boolean)
    }

    companion object {
        const val STATUS_ALL = "STATUS_ALL"
        const val STATUS_PENDING = "STATUS_PENDING"
        const val STATUS_DOWNLOADING = "STATUS_DOWNLOADING"
        const val STATUS_DOWNLOADED = "STATUS_DOWNLOADED"

        private const val STATUS = "status"

        fun newInstance(status: String?) : DownloadsSortDialog {
            return DownloadsSortDialog().apply {
                arguments = bundleOf(STATUS to status)
            }
        }
    }

    private var _binding: DialogDownloadsSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = DialogDownloadsSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            val args = requireArguments()
            val originalStatusId = when(args.getString(STATUS)) {
                STATUS_ALL -> R.id.all
                STATUS_PENDING -> R.id.pending
                STATUS_DOWNLOADING -> R.id.downloading
                STATUS_DOWNLOADED -> R.id.downloaded
                else -> R.id.all
            }
            status.check(originalStatusId)
            saveDefault.setOnClickListener {
                applyFilters(originalStatusId, saveDefault = true)
                dismiss()
            }
            apply.setOnClickListener {
                applyFilters(originalStatusId, saveDefault = false)
                dismiss()
            }
        }
    }

    private fun applyFilters(originalStatusId: Int, saveDefault: Boolean) {
        with(binding) {
            val checkedStatusId = status.checkedRadioButtonId
            Log.i("applyFilters", "$originalStatusId <> $checkedStatusId")
            val statusBtn = requireView().findViewById<RadioButton>(checkedStatusId)
            listener.onChange(
                status = when (checkedStatusId) {
                    R.id.all -> STATUS_ALL
                    R.id.pending -> STATUS_PENDING
                    R.id.downloading -> STATUS_DOWNLOADING
                    R.id.downloaded -> STATUS_DOWNLOADED
                    else -> STATUS_ALL
                },
                statusBtn.text,
                checkedStatusId != originalStatusId,
                saveDefault
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}