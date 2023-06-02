package com.github.andreyasadchy.xtra.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogClipDownloadBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ClipDownloadDialog : BaseDownloadDialog() {

    companion object {
        private const val KEY_QUALITIES = "urls"
        private const val KEY_CLIP = "clip"

        fun newInstance(clip: Clip, qualities: Map<String, String>? = null): ClipDownloadDialog {
            return ClipDownloadDialog().apply {
                arguments = bundleOf(KEY_CLIP to clip, KEY_QUALITIES to qualities)
            }
        }
    }

    private var _binding: DialogClipDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ClipDownloadViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogClipDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Deprecated("Deprecated in Java")
    @Suppress("UNCHECKED_CAST")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        with(requireArguments()) {
            viewModel.init(
                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                clip = getParcelable(KEY_CLIP)!!,
                qualities = getSerializable(KEY_QUALITIES) as Map<String, String>?,
                skipAccessToken = requireContext().prefs().getString(C.TOKEN_SKIP_CLIP_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
            )
        }
        viewModel.qualities.observe(viewLifecycleOwner) {
            ((requireView() as NestedScrollView).children.first() as ConstraintLayout).children.forEach { v -> v.isVisible = v.id != R.id.progressBar && v.id != R.id.storageSelectionContainer }
            init(it)
        }
    }

    private fun init(qualities: Map<String, String>) {
        with(binding) {
            val context = requireContext()
            init(context, binding.storageSelectionContainer)
            spinner.adapter = ArrayAdapter(context, R.layout.spinner_quality_item, qualities.keys.toTypedArray())
            cancel.setOnClickListener { dismiss() }
            download.setOnClickListener {
                val quality = spinner.selectedItem.toString()
                viewModel.download(qualities.getValue(quality), downloadPath, quality)
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
