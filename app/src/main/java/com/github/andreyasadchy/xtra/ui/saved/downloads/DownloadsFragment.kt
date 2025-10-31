package com.github.andreyasadchy.xtra.ui.saved.downloads

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.StorageSelectionBinding
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.download.StreamDownloadWorker
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadWorker
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.convertDpToPixels
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DownloadsFragment : PagedListFragment(), Scrollable {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadsViewModel by viewModels()
    private lateinit var pagingAdapter: PagingDataAdapter<OfflineVideo, out RecyclerView.ViewHolder>
    override var enableNetworkCheck = false
    private var fileResultLauncher: ActivityResultLauncher<Intent>? = null
    private var chatFileResultLauncher: ActivityResultLauncher<Intent>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let {
                    viewModel.selectedVideo?.let { video ->
                        requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        viewModel.moveToSharedStorage(it, video)
                    }
                }
            }
        }
        chatFileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let {
                    viewModel.selectedVideo?.let { video ->
                        requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        viewModel.updateChatUrl(it, video)
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = DownloadsAdapter(this, {
            if (it.live) {
                val channelLogin = it.channelLogin
                if (!channelLogin.isNullOrBlank()) {
                    viewModel.checkLiveDownloadStatus(channelLogin)
                }
            } else {
                viewModel.checkDownloadStatus(it.id)
            }
        }, {
            if (it.live) {
                if (it.status != OfflineVideo.STATUS_PENDING) {
                    it.channelLogin?.let { channelLogin ->
                        WorkManager.getInstance(requireContext()).cancelUniqueWork(channelLogin)
                    }
                } else {
                    viewModel.finishDownload(it)
                }
            } else {
                WorkManager.getInstance(requireContext()).cancelAllWorkByTag(it.id.toString())
            }
        }, {
            if (it.live) {
                val channelLogin = it.channelLogin
                if (!channelLogin.isNullOrBlank()) {
                    WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                        channelLogin,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<StreamDownloadWorker>()
                            .setInputData(workDataOf(StreamDownloadWorker.KEY_VIDEO_ID to it.id))
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(
                                        if (requireContext().prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                            NetworkType.UNMETERED
                                        } else {
                                            NetworkType.CONNECTED
                                        }
                                    )
                                    .build()
                            )
                            .build()
                    )
                    viewModel.checkLiveDownloadStatus(channelLogin)
                }
            } else {
                WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                    "download",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                        .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ID to it.id))
                        .addTag(it.id.toString())
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(
                                    if (requireContext().prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                        NetworkType.UNMETERED
                                    } else {
                                        NetworkType.CONNECTED
                                    }
                                )
                                .build()
                        )
                        .build()
                )
                viewModel.checkDownloadStatus(it.id)
            }
        }, {
            val convert = getString(R.string.convert)
            requireActivity().getAlertDialogBuilder()
                .setTitle(convert)
                .setMessage(getString(R.string.convert_message))
                .setPositiveButton(convert) { _, _ -> viewModel.convertToFile(it) }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }, {
            if (it.url?.toUri()?.scheme == ContentResolver.SCHEME_CONTENT) {
                val storage = DownloadUtils.getAvailableStorage(requireContext())
                val binding = StorageSelectionBinding.inflate(layoutInflater).apply {
                    storageSpinner.gone()
                    if (DownloadUtils.isExternalStorageAvailable) {
                        appStorageLayout.visible()
                        for (s in storage) {
                            radioGroup.addView(RadioButton(context).apply {
                                id = s.id
                                text = s.name
                            })
                        }
                        radioGroup.check(if (storage.size == 1) 0 else requireContext().prefs().getInt(C.DOWNLOAD_STORAGE, 0))
                    } else {
                        noStorageDetected.apply {
                            visible()
                            layoutParams = layoutParams.apply {
                                width = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                        }
                    }
                }
                requireActivity().getAlertDialogBuilder()
                    .setView(binding.root)
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        val checked = binding.radioGroup.checkedRadioButtonId
                        storage.getOrNull(checked)?.let { storage ->
                            requireContext().prefs().edit { putInt(C.DOWNLOAD_STORAGE, checked) }
                            viewModel.moveToAppStorage(storage.path, it)
                        }
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            } else {
                viewModel.selectedVideo = it
                fileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            }
        }, {
            viewModel.selectedVideo = it
            chatFileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
        }, {
            it.url?.let { videoUrl ->
                val uri = if (videoUrl.endsWith(".m3u8")) {
                    videoUrl.substringBefore("%2F").toUri()
                } else {
                    videoUrl.toUri()
                }
                requireContext().startActivity(Intent.createChooser(Intent().apply {
                    action = Intent.ACTION_SEND
                    setDataAndType(uri, requireContext().contentResolver.getType(uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    it.name?.let { putExtra(Intent.EXTRA_TITLE, it) }
                }, null))
            }
        }, {
            val delete = getString(R.string.delete)
            val checkBox = CheckBox(requireContext()).apply {
                text = getString(R.string.keep_files)
                isChecked = true
            }
            val checkBoxView = LinearLayout(requireContext()).apply {
                addView(checkBox)
                val padding = requireContext().convertDpToPixels(20f)
                setPadding(padding, 0, padding, 0)
            }
            requireActivity().getAlertDialogBuilder()
                .setTitle(delete)
                .setMessage(getString(R.string.are_you_sure))
                .setView(checkBoxView)
                .setPositiveButton(delete) { _, _ -> viewModel.delete(it, checkBox.isChecked) }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        })
        with(binding) {
            pagingAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    pagingAdapter.unregisterAdapterDataObserver(this)
                    pagingAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                            if (positionStart == 0) {
                                recyclerView.smoothScrollToPosition(0)
                            }
                        }
                    })
                }
            })
            recyclerView.adapter = pagingAdapter
            (recyclerView.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerView.updatePadding(bottom = insets.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        initializeAdapter(binding, pagingAdapter, enableSwipeRefresh = false, enableScrollTopButton = false)
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
    }

    override fun onIntegrityDialogCallback(callback: String?) {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
