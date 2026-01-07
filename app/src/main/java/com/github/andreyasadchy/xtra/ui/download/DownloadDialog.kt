package com.github.andreyasadchy.xtra.ui.download

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogVideoDownloadBinding
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class DownloadDialog : DialogFragment(), IntegrityDialog.CallbackListener {

    companion object {
        private const val STREAM = "stream"
        private const val VIDEO = "video"
        private const val CLIP = "clip"

        private const val KEY_TYPE = "type"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CLIP_ID = "clipId"
        private const val KEY_TITLE = "title"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_UPLOAD_DATE = "uploadDate"
        private const val KEY_DURATION = "duration"
        private const val KEY_VIDEO_TYPE = "videoType"
        private const val KEY_VIDEO_ANIMATED_PREVIEW = "animatedPreviewUrl"
        private const val KEY_VOD_OFFSET = "vodOffset"
        private const val KEY_CHANNEL_ID = "channelId"
        private const val KEY_CHANNEL_LOGIN = "channelLogin"
        private const val KEY_CHANNEL_NAME = "channelName"
        private const val KEY_CHANNEL_LOGO = "channelLogo"
        private const val KEY_THUMBNAIL = "thumbnail"
        private const val KEY_GAME_ID = "gameId"
        private const val KEY_GAME_SLUG = "gameSlug"
        private const val KEY_GAME_NAME = "gameName"
        private const val KEY_VIDEO_TOTAL_DURATION = "totalDuration"
        private const val KEY_VIDEO_CURRENT_POSITION = "currentPosition"
        private const val KEY_QUALITY_KEYS = "quality_keys"
        private const val KEY_QUALITY_NAMES = "quality_names"
        private const val KEY_QUALITY_URLS = "quality_urls"

        fun newInstance(id: String?, title: String?, startedAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, qualityKeys: Array<String>? = null, qualityNames: Array<String>? = null, qualityUrls: Array<String>? = null): DownloadDialog {
            return DownloadDialog().apply {
                arguments = bundleOf(
                    KEY_TYPE to STREAM,
                    KEY_STREAM_ID to id,
                    KEY_TITLE to title,
                    KEY_STARTED_AT to startedAt,
                    KEY_CHANNEL_ID to channelId,
                    KEY_CHANNEL_LOGIN to channelLogin,
                    KEY_CHANNEL_NAME to channelName,
                    KEY_CHANNEL_LOGO to channelLogo,
                    KEY_THUMBNAIL to thumbnail,
                    KEY_GAME_ID to gameId,
                    KEY_GAME_SLUG to gameSlug,
                    KEY_GAME_NAME to gameName,
                    KEY_QUALITY_KEYS to qualityKeys,
                    KEY_QUALITY_NAMES to qualityNames,
                    KEY_QUALITY_URLS to qualityUrls
                )
            }
        }

        fun newInstance(id: String?, title: String?, uploadDate: String?, duration: String?, videoType: String?, animatedPreviewUrl: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, totalDuration: Long? = null, currentPosition: Long? = null, qualityKeys: Array<String>? = null, qualityNames: Array<String>? = null, qualityUrls: Array<String>? = null): DownloadDialog {
            return DownloadDialog().apply {
                arguments = bundleOf(
                    KEY_TYPE to VIDEO,
                    KEY_VIDEO_ID to id,
                    KEY_TITLE to title,
                    KEY_UPLOAD_DATE to uploadDate,
                    KEY_DURATION to duration,
                    KEY_VIDEO_TYPE to videoType,
                    KEY_VIDEO_ANIMATED_PREVIEW to animatedPreviewUrl,
                    KEY_CHANNEL_ID to channelId,
                    KEY_CHANNEL_LOGIN to channelLogin,
                    KEY_CHANNEL_NAME to channelName,
                    KEY_CHANNEL_LOGO to channelLogo,
                    KEY_THUMBNAIL to thumbnail,
                    KEY_GAME_ID to gameId,
                    KEY_GAME_SLUG to gameSlug,
                    KEY_GAME_NAME to gameName,
                    KEY_VIDEO_TOTAL_DURATION to totalDuration,
                    KEY_VIDEO_CURRENT_POSITION to currentPosition,
                    KEY_QUALITY_KEYS to qualityKeys,
                    KEY_QUALITY_NAMES to qualityNames,
                    KEY_QUALITY_URLS to qualityUrls
                )
            }
        }

        fun newInstance(clipId: String?, title: String?, uploadDate: String?, duration: Double?, videoId: String?, vodOffset: Int?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, qualityKeys: Array<String>? = null, qualityNames: Array<String>? = null, qualityUrls: Array<String>? = null): DownloadDialog {
            return DownloadDialog().apply {
                arguments = bundleOf(
                    KEY_TYPE to CLIP,
                    KEY_CLIP_ID to clipId,
                    KEY_TITLE to title,
                    KEY_UPLOAD_DATE to uploadDate,
                    KEY_DURATION to duration,
                    KEY_VIDEO_ID to videoId,
                    KEY_VOD_OFFSET to vodOffset,
                    KEY_CHANNEL_ID to channelId,
                    KEY_CHANNEL_LOGIN to channelLogin,
                    KEY_CHANNEL_NAME to channelName,
                    KEY_CHANNEL_LOGO to channelLogo,
                    KEY_THUMBNAIL to thumbnail,
                    KEY_GAME_ID to gameId,
                    KEY_GAME_SLUG to gameSlug,
                    KEY_GAME_NAME to gameName,
                    KEY_QUALITY_KEYS to qualityKeys,
                    KEY_QUALITY_NAMES to qualityNames,
                    KEY_QUALITY_URLS to qualityUrls
                )
            }
        }
    }

    private var _binding: DialogVideoDownloadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadViewModel by viewModels()
    private var sharedPath: String? = null
    private var directoryResultLauncher: ActivityResultLauncher<Intent>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogVideoDownloadBinding.inflate(layoutInflater)
        val builder = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collectLatest {
                    if (it != null &&
                        it != "done" &&
                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                        requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                    ) {
                        IntegrityDialog.show(childFragmentManager, it)
                        viewModel.integrity.value = "done"
                    }
                }
            }
        }
        directoryResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let {
                    when {
                        it.authority?.startsWith("com.android.providers") == true -> requireActivity().toast(getString(R.string.invalid_directory))
                        else -> {
                            requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            sharedPath = it.toString()
                            binding.download.isEnabled = true
                            binding.storageSelectionContainer.directory.visible()
                            binding.storageSelectionContainer.directory.text = it.path?.substringAfter("/tree/")?.removeSuffix(":")
                        }
                    }
                }
            }
        }
        when (requireArguments().getString(KEY_TYPE)) {
            STREAM -> {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.qualities.collectLatest {
                            if (!it.isNullOrEmpty()) {
                                init(it)
                            }
                        }
                    }
                }
                viewModel.setStream(
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), requireContext().prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    qualities = requireArguments().getStringArray(KEY_QUALITY_KEYS)?.let { keys ->
                        requireArguments().getStringArray(KEY_QUALITY_NAMES)?.let { names ->
                            requireArguments().getStringArray(KEY_QUALITY_URLS)?.let { urls ->
                                keys.zip(names.zip(urls)).toMap(mutableMapOf())
                            }
                        }
                    },
                    randomDeviceId = requireContext().prefs().getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                    xDeviceId = requireContext().prefs().getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                    playerType = requireContext().prefs().getString(C.TOKEN_PLAYERTYPE, "site"),
                    supportedCodecs = requireContext().prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            VIDEO -> {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.qualities.collectLatest {
                            if (!it.isNullOrEmpty()) {
                                init(
                                    it,
                                    requireArguments().getLong(KEY_VIDEO_TOTAL_DURATION).takeIf { it > 0 }
                                        ?: requireArguments().getString(KEY_DURATION)?.let { TwitchApiHelper.getDuration(it)?.times(1000) }
                                        ?: 0,
                                    requireArguments().getLong(KEY_VIDEO_CURRENT_POSITION)
                                )
                            }
                        }
                    }
                }
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.dismiss.collectLatest {
                            if (it) {
                                requireActivity().toast(getString(R.string.video_subscribers_only))
                                dismiss()
                            }
                        }
                    }
                }
                viewModel.setVideo(
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), requireContext().prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                    videoId = requireArguments().getString(KEY_VIDEO_ID),
                    animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
                    videoType = requireArguments().getString(KEY_VIDEO_TYPE),
                    qualities = requireArguments().getStringArray(KEY_QUALITY_KEYS)?.let { keys ->
                        requireArguments().getStringArray(KEY_QUALITY_NAMES)?.let { names ->
                            requireArguments().getStringArray(KEY_QUALITY_URLS)?.let { urls ->
                                keys.zip(names.zip(urls)).toMap(mutableMapOf())
                            }
                        }
                    },
                    playerType = requireContext().prefs().getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                    supportedCodecs = requireContext().prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    skipAccessToken = requireContext().prefs().getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2,
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            CLIP -> {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.qualities.collectLatest {
                            if (!it.isNullOrEmpty()) {
                                init(it)
                            }
                        }
                    }
                }
                viewModel.setClip(
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    clipId = requireArguments().getString(KEY_CLIP_ID),
                    qualities = requireArguments().getStringArray(KEY_QUALITY_KEYS)?.let { keys ->
                        requireArguments().getStringArray(KEY_QUALITY_NAMES)?.let { names ->
                            requireArguments().getStringArray(KEY_QUALITY_URLS)?.let { urls ->
                                keys.zip(names.zip(urls)).toMap(mutableMapOf())
                            }
                        }
                    },
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
        }
        return builder.create()
    }

    private fun init(qualities: Map<String, Pair<String, String>>, totalDuration: Long = 0, currentPosition: Long = 0) {
        val type = requireArguments().getString(KEY_TYPE)
        binding.layout.children.forEach {
            it.isVisible = it.id != R.id.progressBar && it.id != R.id.timeLayout && it.id != R.id.sharedStorageLayout && it.id != R.id.appStorageLayout
        }
        val storageLocations = resources.getStringArray(R.array.spinnerStorage)
        val storage = requireContext().getExternalFilesDirs(".downloads").mapIndexedNotNull { index, file ->
            file?.absolutePath?.let { path ->
                if (index == 0) {
                    requireContext().getString(R.string.internal_storage) to path
                } else {
                    path.substringBefore("/Android/data", "").takeIf { it.isNotBlank() }?.let {
                        it.substringAfterLast(File.separatorChar) to path
                    }
                }
            }
        }
        with(binding) {
            (spinner.editText as? MaterialAutoCompleteTextView)?.apply {
                val array = qualities.map { it.value.first }.toTypedArray()
                val selectedQuality = viewModel.selectedQuality?.let { quality ->
                    array.find { it == quality }
                } ?: array.first()
                setSimpleItems(array)
                setText(selectedQuality, false)
            }
            if (type == VIDEO) {
                timeLayout.visible()
                val defaultFrom = DateUtils.formatElapsedTime(currentPosition / 1000L).let { if (it.length == 5) "00:$it" else it }
                val totalTime = DateUtils.formatElapsedTime(totalDuration / 1000L)
                val defaultTo = totalTime.let { if (it.length != 5) it else "00:$it" }
                duration.text = requireContext().getString(R.string.duration, totalTime)
                timeTo.editText?.hint = defaultTo
                timeFrom.editText?.hint = defaultFrom
                timeFrom.editText?.doOnTextChanged { text, _, _, _ -> if (text?.length == 8) timeTo.requestFocus() }
                addTextChangeListener(timeFrom.editText)
                addTextChangeListener(timeTo.editText)
            }
            with(storageSelectionContainer) {
                if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                    val location = requireContext().prefs().getInt(C.DOWNLOAD_LOCATION, 0)
                    (storageSpinner.editText as? MaterialAutoCompleteTextView)?.apply {
                        setSimpleItems(storageLocations)
                        setOnItemClickListener { _, _, position, _ ->
                            when (position) {
                                0 -> {
                                    sharedStorageLayout.visible()
                                    appStorageLayout.gone()
                                    binding.download.isEnabled = sharedPath != null
                                }
                                1 -> {
                                    appStorageLayout.visible()
                                    sharedStorageLayout.gone()
                                    binding.download.isEnabled = true
                                }
                            }
                        }
                        setText(adapter.getItem(location).toString(), false)
                    }
                    sharedPath = requireContext().prefs().getString(C.DOWNLOAD_SHARED_PATH, null)
                    when (location) {
                        0 -> {
                            sharedStorageLayout.visible()
                            appStorageLayout.gone()
                            binding.download.isEnabled = sharedPath != null
                        }
                        1 -> {
                            appStorageLayout.visible()
                            sharedStorageLayout.gone()
                        }
                    }
                    sharedPath?.let {
                        directory.visible()
                        directory.text = Uri.decode(it.substringAfter("/tree/"))
                    }
                    selectDirectory.setOnClickListener {
                        viewModel.selectedQuality = viewModel.qualities.value?.entries?.find { it.value.first == binding.spinner.editText?.text.toString() }?.value?.first
                        val location = resources.getStringArray(R.array.spinnerStorage).indexOf(storageSpinner.editText?.text.toString())
                        val downloadChat = binding.downloadChat.isChecked
                        val downloadChatEmotes = binding.downloadChatEmotes.isChecked
                        requireContext().prefs().edit {
                            putInt(C.DOWNLOAD_LOCATION, location)
                            when (location) {
                                0 -> putString(C.DOWNLOAD_SHARED_PATH, sharedPath)
                                1 -> putInt(C.DOWNLOAD_STORAGE,
                                    if (storage.size > 1) {
                                        storageSelectionContainer.radioGroup.checkedRadioButtonId
                                    } else {
                                        0
                                    }
                                )
                            }
                            putBoolean(C.DOWNLOAD_CHAT, downloadChat)
                            putBoolean(C.DOWNLOAD_CHAT_EMOTES, downloadChatEmotes)
                        }
                        directoryResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                putExtra(DocumentsContract.EXTRA_INITIAL_URI, sharedPath)
                            }
                        })
                    }
                    radioGroup.removeAllViews()
                    radioGroup.clearCheck()
                    if (storage.size > 1) {
                        storage.forEachIndexed { index, pair ->
                            radioGroup.addView(
                                RadioButton(requireContext()).apply {
                                    id = index
                                    text = pair.first
                                }
                            )
                        }
                        radioGroup.check(requireContext().prefs().getInt(C.DOWNLOAD_STORAGE, 0))
                    }
                } else {
                    noStorageDetected.visible()
                    storageSpinner.gone()
                    sharedStorageLayout.gone()
                    appStorageLayout.gone()
                    binding.download.gone()
                }
            }
            downloadChat.apply {
                isChecked = requireContext().prefs().getBoolean(C.DOWNLOAD_CHAT, false)
                setOnCheckedChangeListener { _, isChecked ->
                    downloadChatEmotes.isEnabled = isChecked
                }
            }
            downloadChatEmotes.apply {
                isChecked = requireContext().prefs().getBoolean(C.DOWNLOAD_CHAT_EMOTES, false)
                isEnabled = downloadChat.isChecked
            }
            cancel.setOnClickListener { dismiss() }
            download.setOnClickListener {
                val quality = qualities.entries.find { it.value.first == spinner.editText?.text.toString() }
                val location = storageLocations.indexOf(storageSelectionContainer.storageSpinner.editText?.text.toString())
                val path = when (location) {
                    0 -> sharedPath
                    1 -> storage.getOrNull(
                        if (storage.size > 1) {
                            storageSelectionContainer.radioGroup.checkedRadioButtonId
                        } else {
                            0
                        }
                    )?.second
                    else -> null
                }
                if (quality != null && !path.isNullOrBlank()) {
                    val downloadChat = downloadChat.isChecked
                    val downloadChatEmotes = downloadChatEmotes.isChecked
                    when (type) {
                        STREAM -> {
                            (requireActivity() as? MainActivity)?.downloadStream(
                                filesDir = requireContext().filesDir.path,
                                id = requireArguments().getString(KEY_STREAM_ID),
                                title = requireArguments().getString(KEY_TITLE),
                                startedAt = requireArguments().getString(KEY_STARTED_AT),
                                channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                                channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
                                thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                                gameId = requireArguments().getString(KEY_GAME_ID),
                                gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                gameName = requireArguments().getString(KEY_GAME_NAME),
                                downloadPath = path,
                                quality = quality.key,
                                downloadChat = downloadChat,
                                downloadChatEmotes = downloadChatEmotes,
                                wifiOnly = requireContext().prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)
                            )
                        }
                        VIDEO -> {
                            val from = timeFrom.editText?.takeIf { !it.text.isEmpty() }?.let { editText ->
                                parseTime(editText.text).also {
                                    if (it == null) {
                                        editText.requestFocus()
                                        editText.error = getString(R.string.invalid_time)
                                        return@setOnClickListener
                                    }
                                }
                            } ?: currentPosition
                            val to = timeTo.editText?.takeIf { !it.text.isEmpty() }?.let { editText ->
                                parseTime(editText.text).also {
                                    if (it == null) {
                                        editText.requestFocus()
                                        editText.error = getString(R.string.invalid_time)
                                        return@setOnClickListener
                                    }
                                }
                            } ?: totalDuration
                            when {
                                to > totalDuration -> {
                                    timeTo.requestFocus()
                                    timeTo.editText?.error = getString(R.string.to_is_longer)
                                    return@setOnClickListener
                                }
                                from < to -> {
                                    (requireActivity() as? MainActivity)?.downloadVideo(
                                        filesDir = requireContext().filesDir.path,
                                        id = requireArguments().getString(KEY_VIDEO_ID),
                                        title = requireArguments().getString(KEY_TITLE),
                                        uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
                                        type = requireArguments().getString(KEY_VIDEO_TYPE),
                                        channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                                        channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
                                        thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                                        gameId = requireArguments().getString(KEY_GAME_ID),
                                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                        gameName = requireArguments().getString(KEY_GAME_NAME),
                                        url = quality.value.second,
                                        downloadPath = path,
                                        quality = quality.key,
                                        from = from,
                                        to = to,
                                        downloadChat = downloadChat,
                                        downloadChatEmotes = downloadChatEmotes,
                                        playlistToFile = requireContext().prefs().getBoolean(C.DOWNLOAD_PLAYLIST_TO_FILE, false),
                                        wifiOnly = requireContext().prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)
                                    )
                                }
                                from >= to -> {
                                    timeFrom.requestFocus()
                                    timeFrom.editText?.error = getString(R.string.from_is_greater)
                                    return@setOnClickListener
                                }
                                else -> {
                                    timeTo.requestFocus()
                                    timeTo.editText?.error = getString(R.string.to_is_lesser)
                                    return@setOnClickListener
                                }
                            }
                        }
                        CLIP -> {
                            (requireActivity() as? MainActivity)?.downloadClip(
                                filesDir = requireContext().filesDir.path,
                                clipId = requireArguments().getString(KEY_CLIP_ID),
                                title = requireArguments().getString(KEY_TITLE),
                                uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
                                duration = requireArguments().getDouble(KEY_DURATION),
                                videoId = requireArguments().getString(KEY_VIDEO_ID),
                                vodOffset = requireArguments().getInt(KEY_VOD_OFFSET),
                                channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                                channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
                                thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                                gameId = requireArguments().getString(KEY_GAME_ID),
                                gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                gameName = requireArguments().getString(KEY_GAME_NAME),
                                url = quality.value.second,
                                downloadPath = path,
                                quality = quality.key,
                                downloadChat = downloadChat,
                                downloadChatEmotes = downloadChatEmotes,
                                wifiOnly = requireContext().prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)
                            )
                        }
                    }
                    requireContext().prefs().edit {
                        putInt(C.DOWNLOAD_LOCATION, location)
                        when (location) {
                            0 -> putString(C.DOWNLOAD_SHARED_PATH, sharedPath)
                            1 -> putInt(C.DOWNLOAD_STORAGE,
                                if (storage.size > 1) {
                                    storageSelectionContainer.radioGroup.checkedRadioButtonId
                                } else {
                                    0
                                }
                            )
                        }
                        putBoolean(C.DOWNLOAD_CHAT, downloadChat)
                        putBoolean(C.DOWNLOAD_CHAT_EMOTES, downloadChatEmotes)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        !requireActivity().prefs().getBoolean(C.DOWNLOAD_NOTIFICATION_REQUESTED, false)) {
                        requireActivity().prefs().edit { putBoolean(C.DOWNLOAD_NOTIFICATION_REQUESTED, true) }
                        requireActivity().getAlertDialogBuilder()
                            .setMessage(R.string.notification_permission_message)
                            .setTitle(R.string.notification_permission_title)
                            .setPositiveButton(android.R.string.ok) { _, _ -> ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                dismiss()
            }
        }
    }

    private fun parseTime(text: CharSequence): Long? {
        val list = text.split(':', limit = 3).reversed()
        val seconds = list.getOrNull(0)?.let { string ->
            string.toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        } ?: 0
        val minutes = list.getOrNull(1)?.let { string ->
            string.toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        } ?: 0
        val hours = list.getOrNull(2)?.let { string ->
            string.toLongOrNull() ?: return null
        } ?: 0
        return ((hours * 3600) + (minutes * 60) + seconds) * 1000
    }

    private fun addTextChangeListener(textView: TextView?) {
        textView?.addTextChangedListener(object : TextWatcher {
            private var deleteNext = false

            override fun afterTextChanged(s: Editable) {}
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                textView.error = null
                val length = s.length
                val delete = deleteNext
                deleteNext = s.lastOrNull() == ':'
                s.reversed().let { text ->
                    if (text.getOrNull(0)?.isDigit() == true
                        && text.getOrNull(1)?.isDigit() == true
                        && text.getOrNull(2).let { it == null || it == ':' }
                    ) {
                        if (delete) {
                            if (!deleteNext) {
                                textView.editableText.delete(length - 1, length)
                            }
                        } else {
                            if (text.count { it == ':' } < 2) {
                                textView.append(":")
                            }
                        }
                    }
                }
                if (s.lastOrNull() == '.') {
                    textView.editableText.replace(length - 1, length, ":")
                }
            }
        })
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback == "refresh") {
            when (requireArguments().getString(KEY_TYPE)) {
                STREAM -> {
                    viewModel.setStream(
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), requireContext().prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                        qualities = requireArguments().getStringArray(KEY_QUALITY_KEYS)?.let { keys ->
                            requireArguments().getStringArray(KEY_QUALITY_NAMES)?.let { names ->
                                requireArguments().getStringArray(KEY_QUALITY_URLS)?.let { urls ->
                                    keys.zip(names.zip(urls)).toMap(mutableMapOf())
                                }
                            }
                        },
                        randomDeviceId = requireContext().prefs().getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                        xDeviceId = requireContext().prefs().getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                        playerType = requireContext().prefs().getString(C.TOKEN_PLAYERTYPE, "site"),
                        supportedCodecs = requireContext().prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
                VIDEO -> {
                    viewModel.setVideo(
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), requireContext().prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                        videoId = requireArguments().getString(KEY_VIDEO_ID),
                        animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
                        videoType = requireArguments().getString(KEY_VIDEO_TYPE),
                        qualities = requireArguments().getStringArray(KEY_QUALITY_KEYS)?.let { keys ->
                            requireArguments().getStringArray(KEY_QUALITY_NAMES)?.let { names ->
                                requireArguments().getStringArray(KEY_QUALITY_URLS)?.let { urls ->
                                    keys.zip(names.zip(urls)).toMap(mutableMapOf())
                                }
                            }
                        },
                        playerType = requireContext().prefs().getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                        supportedCodecs = requireContext().prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        skipAccessToken = requireContext().prefs().getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2,
                        enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
                CLIP -> {
                    viewModel.setClip(
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                        clipId = requireArguments().getString(KEY_CLIP_ID),
                        qualities = requireArguments().getStringArray(KEY_QUALITY_KEYS)?.let { keys ->
                            requireArguments().getStringArray(KEY_QUALITY_NAMES)?.let { names ->
                                requireArguments().getStringArray(KEY_QUALITY_URLS)?.let { urls ->
                                    keys.zip(names.zip(urls)).toMap(mutableMapOf())
                                }
                            }
                        },
                        enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
