package com.github.andreyasadchy.xtra.ui.saved.downloads

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.FragmentDownloadsListItemBinding
import com.github.andreyasadchy.xtra.databinding.StorageSelectionBinding
import com.github.andreyasadchy.xtra.model.ui.DownloadProgress
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.download.StreamDownloadService
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadService
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsViewModel.Companion.DownloadsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadsFragment : PagedListFragment(), Scrollable {

    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadsViewModel by viewModels { DownloadsViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<OfflineVideo, out RecyclerView.ViewHolder>
    override var enableNetworkCheck = false
    private var fileResultLauncher: ActivityResultLauncher<Intent>? = null
    private var chatFileResultLauncher: ActivityResultLauncher<Intent>? = null
    private var videoDownloadService: VideoDownloadService? = null
    private var videoDownloadServiceConnection: ServiceConnection? = null
    private var streamDownloadService: StreamDownloadService? = null
    private var streamDownloadServiceConnection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
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
        } else {
            chatFileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.path?.let {
                        viewModel.selectedVideo?.let { video ->
                            viewModel.updateChatUrl(it.toUri(), video)
                        }
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
        pagingAdapter = DownloadsAdapter(
            fragment = this,
            stopDownload = { video ->
                if (video.status == OfflineVideo.STATUS_WAITING_FOR_NETWORK || video.status == OfflineVideo.STATUS_WAITING_FOR_WIFI) {
                    viewModel.updateDownloadStatus(video, false)
                } else {
                    if (video.live) {
                        if (video.status == OfflineVideo.STATUS_PENDING
                            || ((video.status == OfflineVideo.STATUS_DOWNLOADING
                                    || video.status == OfflineVideo.STATUS_QUEUED
                                    || video.status == OfflineVideo.STATUS_WAITING_FOR_STREAM)
                                    && streamDownloadService?.activeDownloads?.toList()?.find { it.id == video.id } == null)
                        ) {
                            viewModel.finishDownload(video)
                        } else {
                            val intent = Intent(requireContext(), StreamDownloadService::class.java).apply {
                                action = StreamDownloadService.INTENT_STOP
                                putExtra(StreamDownloadService.KEY_VIDEO_ID, video.id)
                            }
                            requireContext().startService(intent)
                            bindStreamDownloadService(true)
                        }
                    } else {
                        val intent = Intent(requireContext(), VideoDownloadService::class.java).apply {
                            action = VideoDownloadService.INTENT_STOP
                            putExtra(VideoDownloadService.KEY_VIDEO_ID, video.id)
                        }
                        requireContext().startService(intent)
                        bindVideoDownloadService(true)
                    }
                }
            },
            resumeDownload = {
                val waitForWifi = if (requireContext().prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    } else {
                        false
                    }
                } else false
                if (waitForWifi) {
                    viewModel.updateDownloadStatus(it, true)
                } else {
                    if (it.live) {
                        val intent = Intent(requireContext(), StreamDownloadService::class.java).apply {
                            action = StreamDownloadService.INTENT_START
                            putExtra(StreamDownloadService.KEY_VIDEO_ID, it.id)
                        }
                        requireContext().startService(intent)
                        bindStreamDownloadService(true)
                    } else {
                        val intent = Intent(requireContext(), VideoDownloadService::class.java).apply {
                            action = VideoDownloadService.INTENT_START
                            putExtra(VideoDownloadService.KEY_VIDEO_ID, it.id)
                        }
                        requireContext().startService(intent)
                        bindVideoDownloadService(true)
                    }
                }
            },
            convertVideo = {
                val convert = getString(R.string.convert)
                requireActivity().getAlertDialogBuilder()
                    .setTitle(convert)
                    .setMessage(getString(R.string.convert_message))
                    .setPositiveButton(convert) { _, _ -> viewModel.convertToFile(it) }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            },
            moveVideo = {
                if (it.url?.toUri()?.scheme == ContentResolver.SCHEME_CONTENT) {
                    val storage = ContextCompat.getExternalFilesDirs(requireContext(), ".downloads").mapIndexedNotNull { index, file ->
                        file?.absolutePath?.let { path ->
                            if (index == 0) {
                                getString(R.string.internal_storage) to path
                            } else {
                                path.substringBefore("/Android/data", "").takeIf { it.isNotBlank() }?.let {
                                    it.substringAfterLast(File.separatorChar) to path
                                }
                            }
                        }
                    }
                    val binding = StorageSelectionBinding.inflate(layoutInflater).apply {
                        storageSpinner.visibility = View.GONE
                        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                            appStorageLayout.visibility = View.VISIBLE
                            storage.forEachIndexed { index, pair ->
                                radioGroup.addView(
                                    RadioButton(requireContext()).apply {
                                        id = index
                                        text = pair.first
                                    }
                                )
                            }
                            radioGroup.check(
                                if (storage.size == 1) {
                                    0
                                } else {
                                    requireContext().prefs().getInt(C.DOWNLOAD_STORAGE, 0)
                                }
                            )
                        } else {
                            noStorageDetected.apply {
                                visibility = View.VISIBLE
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
                                viewModel.moveToAppStorage(storage.second, it)
                            }
                        }
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show()
                } else {
                    viewModel.selectedVideo = it
                    fileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                }
            },
            updateChatUrl = {
                viewModel.selectedVideo = it
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    chatFileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    })
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        chatFileResultLauncher?.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), R.string.no_file_manager_found, Toast.LENGTH_LONG).show()
                    }
                }
            },
            shareVideo = {
                it.url?.let { videoUrl ->
                    val uri = if (videoUrl.endsWith(".m3u8")) {
                        videoUrl.substringBefore("%2F").toUri()
                    } else {
                        videoUrl.toUri()
                    }
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        setDataAndType(uri, requireContext().contentResolver.getType(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        it.name?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    }, null))
                }
            },
            deleteVideo = { video ->
                val delete = getString(R.string.delete)
                val checkBox = CheckBox(requireContext()).apply {
                    text = getString(R.string.keep_files)
                    isChecked = true
                }
                val checkBoxView = LinearLayout(requireContext()).apply {
                    addView(checkBox)
                    val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
                    setPadding(padding, 0, padding, 0)
                }
                requireActivity().getAlertDialogBuilder()
                    .setTitle(delete)
                    .setMessage(getString(R.string.are_you_sure))
                    .setView(checkBoxView)
                    .setPositiveButton(delete) { _, _ ->
                        if (video.live) {
                            if (streamDownloadService?.activeDownloads?.find { it.id == video.id } != null) {
                                val intent = Intent(requireContext(), StreamDownloadService::class.java).apply {
                                    action = StreamDownloadService.INTENT_CANCEL
                                    putExtra(StreamDownloadService.KEY_VIDEO_ID, video.id)
                                }
                                requireContext().startService(intent)
                                bindStreamDownloadService(true)
                            }
                        } else {
                            if (videoDownloadService?.activeDownloads?.find { it.id == video.id } != null) {
                                val intent = Intent(requireContext(), VideoDownloadService::class.java).apply {
                                    action = VideoDownloadService.INTENT_CANCEL
                                    putExtra(VideoDownloadService.KEY_VIDEO_ID, video.id)
                                }
                                requireContext().startService(intent)
                                bindVideoDownloadService(true)
                            }
                        }
                        viewModel.delete(video, checkBox.isChecked)
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
        )
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

    override fun onStart() {
        super.onStart()
        viewLifecycleOwner.lifecycleScope.launch {
            val activeDownloads = viewModel.getActiveDownloads()
            if (activeDownloads.isNotEmpty()) {
                if (activeDownloads.any { it.live }) {
                    bindStreamDownloadService()
                }
                if (activeDownloads.any { !it.live }) {
                    bindVideoDownloadService()
                }
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

    fun bindVideoDownloadService(started: Boolean = false) {
        if (videoDownloadServiceConnection == null) {
            val listener = object : VideoDownloadService.Listener {
                override fun update(downloadProgress: DownloadProgress) {
                    val list = pagingAdapter.snapshot()
                    val item = list.find { it?.id == downloadProgress.id }
                    if (item != null) {
                        val index = list.indexOf(item)
                        (binding.recyclerView.layoutManager?.findViewByPosition(index) as? MaterialCardView)?.let {
                            val binding = FragmentDownloadsListItemBinding.bind(it)
                            (pagingAdapter as? DownloadsAdapter)?.updateStatus(binding, requireContext(), item, downloadProgress)
                        } ?: pagingAdapter.notifyItemChanged(index)
                    }
                }

                override fun unbind() {
                    (pagingAdapter as? DownloadsAdapter)?.activeVideoDownloads = null
                    videoDownloadService?.listener = null
                    videoDownloadServiceConnection?.let { requireContext().unbindService(it) }
                    videoDownloadServiceConnection = null
                    videoDownloadService = null
                }
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (view != null) {
                        val binder = service as VideoDownloadService.ServiceBinder
                        videoDownloadService = binder.getService()
                        if (started || !videoDownloadService?.activeDownloads.isNullOrEmpty()) {
                            (pagingAdapter as? DownloadsAdapter)?.activeVideoDownloads = videoDownloadService?.activeDownloads
                            videoDownloadService?.listener = listener
                            videoDownloadService?.activeDownloads?.toList()?.forEach {
                                listener.update(it)
                            }
                        } else {
                            videoDownloadServiceConnection?.let { requireContext().unbindService(it) }
                            videoDownloadServiceConnection = null
                            videoDownloadService?.stopSelf()
                            videoDownloadService = null
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    videoDownloadService = null
                    (pagingAdapter as? DownloadsAdapter)?.activeVideoDownloads = null
                }
            }
            val intent = Intent(requireContext(), VideoDownloadService::class.java)
            requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
            videoDownloadServiceConnection = connection
        }
    }

    fun bindStreamDownloadService(started: Boolean = false) {
        if (streamDownloadServiceConnection == null) {
            val listener = object : StreamDownloadService.Listener {
                override fun unbind() {
                    (pagingAdapter as? DownloadsAdapter)?.activeStreamDownloads = null
                    streamDownloadService?.listener = null
                    streamDownloadServiceConnection?.let { requireContext().unbindService(it) }
                    streamDownloadServiceConnection = null
                    streamDownloadService?.stopSelf()
                    streamDownloadService = null
                }
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (view != null) {
                        val binder = service as StreamDownloadService.ServiceBinder
                        streamDownloadService = binder.getService()
                        if (started || !streamDownloadService?.activeDownloads.isNullOrEmpty()) {
                            (pagingAdapter as? DownloadsAdapter)?.activeStreamDownloads = streamDownloadService?.activeDownloads
                            streamDownloadService?.listener = listener
                        } else {
                            streamDownloadServiceConnection?.let { requireContext().unbindService(it) }
                            streamDownloadServiceConnection = null
                            streamDownloadService = null
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    streamDownloadService = null
                    (pagingAdapter as? DownloadsAdapter)?.activeStreamDownloads = null
                }
            }
            val intent = Intent(requireContext(), StreamDownloadService::class.java)
            requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
            streamDownloadServiceConnection = connection
        }
    }

    override fun onStop() {
        super.onStop()
        (pagingAdapter as? DownloadsAdapter)?.activeVideoDownloads = null
        videoDownloadService?.listener = null
        videoDownloadServiceConnection?.let { requireContext().unbindService(it) }
        videoDownloadServiceConnection = null
        videoDownloadService = null
        (pagingAdapter as? DownloadsAdapter)?.activeStreamDownloads = null
        streamDownloadService?.listener = null
        streamDownloadServiceConnection?.let { requireContext().unbindService(it) }
        streamDownloadServiceConnection = null
        streamDownloadService = null
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onNetworkRestored() {
    }

    override fun onIntegrityTokenLoaded(callback: String?) {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
