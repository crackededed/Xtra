package com.github.andreyasadchy.xtra.ui.multiview

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentMultiviewBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.common.StreamsAdapter
import com.github.andreyasadchy.xtra.ui.common.StreamsCompactAdapter
import com.github.andreyasadchy.xtra.ui.following.streams.FollowedStreamsViewModel
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MultiviewFragment : Fragment(R.layout.fragment_multiview) {

    private var _binding: FragmentMultiviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MultiviewViewModel by viewModels { MultiviewViewModel.MultiviewViewModelFactory }
    private val followedStreamsViewModel: FollowedStreamsViewModel by viewModels {
        FollowedStreamsViewModel.FollowedStreamsViewModelFactory
    }

    private val slots = mutableListOf<Slot>()
    private var activeSlotIndex = 0
    private var chatSlot: Slot? = null
    private var combinedChat = false
    private var previousNavBarVisibility = View.VISIBLE
    private var wasPlaying = BooleanArray(MAX_STREAMS)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMultiviewBinding.bind(view)

        val mainActivity = requireActivity() as? MainActivity
        mainActivity?.findViewById<View>(R.id.navBarContainer)?.let {
            previousNavBarVisibility = it.visibility
            it.visibility = View.GONE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.multiviewRoot) { root, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }
        updateOrientationLayout()

        binding.addStreamButton.setOnClickListener { showAddStreamPicker() }
        binding.chatButton.setOnClickListener { toggleChat() }
        binding.combinedChatButton.setOnClickListener { toggleCombinedChat() }
        binding.closeButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val restoredStreams = savedInstanceState?.parcelableArrayList<Stream>(KEY_STREAMS)
        val initialStreams = restoredStreams
            ?: listOfNotNull(requireArguments().parcelable<Stream>(ARG_STREAM))
        initialStreams
            .distinctBy { it.channelLogin?.lowercase() }
            .take(MAX_STREAMS)
            .forEach { restoreStream(it) }

        activeSlotIndex = (savedInstanceState?.getInt(KEY_ACTIVE_SLOT, 0) ?: 0)
            .coerceIn(0, (slots.lastIndex).coerceAtLeast(0))
        if (slots.isNotEmpty()) {
            setActiveSlot(activeSlotIndex, refreshChat = false)
        } else {
            updateToolbar()
        }
        rebuildGrid()
    }

    override fun onStart() {
        super.onStart()
        slots.forEachIndexed { index, slot ->
            if (wasPlaying.getOrNull(index) == true) {
                slot.player?.playWhenReady = true
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            updateOrientationLayout()
            binding.multiviewRoot.post {
                rebuildGrid()
                updateToolbar()
            }
        }
    }

    override fun onStop() {
        slots.forEachIndexed { index, slot ->
            if (index < wasPlaying.size) {
                wasPlaying[index] = slot.player?.playWhenReady == true
            }
            slot.player?.playWhenReady = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList(KEY_STREAMS, ArrayList(slots.mapNotNull { it.stream }))
        outState.putInt(KEY_ACTIVE_SLOT, activeSlotIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        slots.forEach { it.release() }
        slots.clear()
        childFragmentManager.findFragmentByTag(CHAT_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
        combinedChat = false
        chatSlot = null
        requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = previousNavBarVisibility
        _binding = null
        super.onDestroyView()
    }

    private fun restoreStream(stream: Stream) {
        if (stream.channelLogin.isNullOrBlank()) return
        val slot = createSlot(slots.size)
        slots += slot
        rebuildGrid()
        startSlot(slot, stream)
    }

    private fun addStream(stream: Stream): Boolean {
        val login = stream.channelLogin?.trim()?.lowercase()
        if (login.isNullOrBlank() || slots.size >= MAX_STREAMS || isDuplicate(login)) return false

        val slot = createSlot(slots.size)
        slots += slot
        val wasCombinedChat = combinedChat && binding.chatContainer.isVisible
        rebuildGrid()
        startSlot(slot, stream)
        setActiveSlot(slots.lastIndex)
        if (wasCombinedChat) showCombinedChat()
        return true
    }

    private fun isDuplicate(channelLogin: String): Boolean {
        return slots.any { it.stream?.channelLogin?.equals(channelLogin, ignoreCase = true) == true }
    }

    private fun rebuildGrid() {
        if (_binding == null) return
        slots.forEach { slot ->
            (slot.container.parent as? ViewGroup)?.removeView(slot.container)
        }
        binding.videoGrid.removeAllViews()
        if (slots.isEmpty()) return

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = when (slots.size) {
            1 -> 1
            2 -> if (landscape) 2 else 1
            3 -> if (landscape) 3 else 1
            else -> 2
        }
        val rows = (slots.size + columns - 1) / columns
        val gap = dp(3)

        repeat(rows) { row ->
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            }
            repeat(columns) { column ->
                val index = row * columns + column
                if (index < slots.size) {
                    val slot = slots[index]
                    slot.index = index
                    val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    params.setMargins(
                        if (column > 0) gap else 0,
                        if (row > 0) gap else 0,
                        if (column < columns - 1) gap else 0,
                        if (row < rows - 1) gap else 0,
                    )
                    rowLayout.addView(slot.container, params)
                }
            }
            binding.videoGrid.addView(rowLayout)
        }
        slots.forEach { slot ->
            slot.removeButton.isVisible = slots.size > 1
            updateAudioButton(slot)
        }
    }

    private fun updateOrientationLayout() {
        if (_binding == null) return
        (binding.chatContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (combinedChat) LANDSCAPE_COMBINED_CHAT_WEIGHT else LANDSCAPE_CHAT_WEIGHT
            } else {
                PORTRAIT_CHAT_WEIGHT
            }
            binding.chatContainer.layoutParams = params
        }
    }

    private fun createSlot(index: Int): Slot {
        val container = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val playerView = PlayerView(requireContext()).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        container.addView(
            playerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val topBar = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(165, 0, 0, 0))
            setPadding(dp(8), dp(3), dp(3), dp(3))
        }
        val channel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        topBar.addView(channel)
        val audioButton = createOverlayButton(R.drawable.baseline_volume_off_black_24, R.string.multiview_audio_muted)
        val chatButton = createOverlayButton(R.drawable.baseline_speaker_notes_black_24, R.string.multiview_chat)
        val removeButton = createOverlayButton(R.drawable.baseline_close_black_36, R.string.multiview_remove_stream)
        topBar.addView(audioButton)
        topBar.addView(chatButton)
        topBar.addView(removeButton)
        container.addView(
            topBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )

        lateinit var slot: Slot
        val status = TextView(requireContext()).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setOnClickListener { slot.stream?.let { startSlot(slot, it) } }
        }
        container.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        slot = Slot(index, container, playerView, channel, status, audioButton, chatButton, removeButton)
        container.setOnClickListener { slots.indexOf(slot).takeIf { it >= 0 }?.let(::setActiveSlot) }
        playerView.setOnClickListener { slots.indexOf(slot).takeIf { it >= 0 }?.let(::setActiveSlot) }
        audioButton.setOnClickListener { slots.indexOf(slot).takeIf { it >= 0 }?.let(::setActiveSlot) }
        chatButton.setOnClickListener {
            slots.indexOf(slot).takeIf { it >= 0 }?.let {
                setActiveSlot(it)
                showChatForSlot(it)
            }
        }
        removeButton.setOnClickListener { removeSlot(slot) }
        audioButton.isVisible = false
        chatButton.isVisible = false
        removeButton.isVisible = false
        return slot
    }

    private fun createOverlayButton(icon: Int, description: Int): ImageButton {
        return ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = null
            contentDescription = getString(description)
        }
    }

    private fun startSlot(slot: Slot, stream: Stream) {
        val channelLogin = stream.channelLogin?.trim()?.lowercase()
        if (channelLogin.isNullOrBlank()) {
            showSlotMessage(slot, getString(R.string.multiview_stream_not_found), error = true)
            return
        }
        slot.loadJob?.cancel()
        slot.playerView.player = null
        slot.player?.release()
        slot.stream = stream
        slot.channel.text = displayName(stream)
        slot.audioButton.isVisible = true
        slot.chatButton.isVisible = !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        slot.removeButton.isVisible = slots.size > 1
        showSlotMessage(slot, getString(R.string.multiview_loading), error = false)

        val player = ExoPlayer.Builder(requireContext()).apply {
            setLoadControl(
                DefaultLoadControl.Builder().apply {
                    setBufferDurationsMs(
                        requireContext().prefs().getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                        requireContext().prefs().getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                        requireContext().prefs().getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull() ?: 2000,
                        requireContext().prefs().getString(C.PLAYER_BUFFER_REBUFFER, "2000")?.toIntOrNull() ?: 2000,
                    )
                }.build()
            )
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            setHandleAudioBecomingNoisy(true)
        }.build()
        slot.player = player
        slot.playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> showSlotMessage(slot, null, error = false)
                    Player.STATE_BUFFERING -> showSlotMessage(slot, getString(R.string.multiview_loading), error = false)
                    Player.STATE_IDLE, Player.STATE_ENDED -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showSlotMessage(slot, getString(R.string.multiview_playback_error), error = true)
            }
        })

        slot.loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val playlistUrl = viewModel.loadStreamPlaylist(channelLogin)
                if (!slots.contains(slot) || slot.stream?.channelLogin?.lowercase() != channelLogin) return@launch
                val mediaItem = MediaItem.Builder()
                    .setUri(playlistUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(
                                requireContext().prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull() ?: 2000L
                            )
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(stream.title)
                            .setArtist(displayName(stream))
                            .build()
                    )
                    .build()
                val source = HlsMediaSource.Factory(createDataSourceFactory())
                    .setPlaylistParserFactory(ExoPlayerService.CustomHlsPlaylistParserFactory())
                    .createMediaSource(mediaItem)
                player.setMediaSource(source)
                player.prepare()
                player.volume = if (slots.indexOf(slot) == activeSlotIndex) activeVolume() else 0f
                player.playWhenReady = true
                updateToolbar()
            } catch (_: Exception) {
                if (slots.contains(slot) && slot.stream?.channelLogin?.lowercase() == channelLogin) {
                    showSlotMessage(slot, getString(R.string.multiview_playback_error), error = true)
                }
            }
        }
        updateToolbar()
    }

    private fun showSlotMessage(slot: Slot, message: String?, error: Boolean) {
        slot.status.text = message
        slot.status.isVisible = message != null
        slot.status.isClickable = error
    }

    private fun removeSlot(slot: Slot) {
        if (slots.size <= 1 || !slots.contains(slot)) return
        val removedIndex = slots.indexOf(slot)
        val chatVisible = binding.chatContainer.isVisible
        val wasCombinedChat = combinedChat
        val keepChat = chatVisible && (wasCombinedChat || chatSlot !== slot)
        if (!wasCombinedChat && chatSlot === slot) hideChat()
        slot.release()
        slot.stream = null
        slots.remove(slot)
        slots.forEachIndexed { index, remaining -> remaining.index = index }
        if (removedIndex < activeSlotIndex) activeSlotIndex--
        activeSlotIndex = activeSlotIndex.coerceAtMost(slots.lastIndex)
        rebuildGrid()
        setActiveSlot(activeSlotIndex, refreshChat = keepChat && !wasCombinedChat)
        if (wasCombinedChat && chatVisible) {
            if (slots.size > 1) showCombinedChat() else showChatForSlot(activeSlotIndex)
        }
    }

    private fun setActiveSlot(index: Int, refreshChat: Boolean = true) {
        val activeIndex = index.takeIf { it in slots.indices } ?: return
        activeSlotIndex = activeIndex
        val volume = activeVolume()
        slots.forEach { slot ->
            slot.player?.volume = if (slots.indexOf(slot) == activeIndex) volume else 0f
            updateAudioButton(slot)
        }
        if (refreshChat && binding.chatContainer.isVisible && !combinedChat) showChatForSlot(activeIndex)
        updateToolbar()
    }

    private fun updateAudioButton(slot: Slot) {
        val isActive = slots.indexOf(slot) == activeSlotIndex
        slot.audioButton.setImageResource(
            if (isActive) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24
        )
        slot.audioButton.contentDescription = getString(
            if (isActive) R.string.multiview_audio_active else R.string.multiview_audio_muted,
            displayName(slot.stream),
        )
    }

    private fun updateToolbar() {
        val active = slots.getOrNull(activeSlotIndex)?.stream
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.activeAudio.isVisible = active != null && isLandscape
        binding.activeAudio.text = active?.let { getString(R.string.multiview_audio, displayName(it)) }
        binding.addStreamButton.isVisible = slots.size < MAX_STREAMS
        binding.addStreamButton.text = getString(R.string.multiview_add_stream_count, slots.size, MAX_STREAMS)
        val chatEnabled = active != null && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        binding.chatButton.isVisible = chatEnabled
        binding.combinedChatButton.isVisible = chatEnabled && binding.chatContainer.isVisible && slots.size > 1
        binding.chatTitle.text = if (combinedChat) {
            getString(R.string.multiview_all_chats)
        } else {
            chatSlot?.stream?.let(::displayName) ?: active?.let(::displayName) ?: getString(R.string.multiview_chat)
        }
        binding.chatButton.text = getString(
            if (binding.chatContainer.isVisible) R.string.multiview_hide_chat else R.string.multiview_chat
        )
        binding.combinedChatButton.text = getString(
            if (combinedChat) R.string.multiview_channel_chat else R.string.multiview_all_chats
        )
        slots.forEach { slot ->
            slot.removeButton.isVisible = slots.size > 1
            updateAudioButton(slot)
        }
    }

    private fun toggleChat() {
        if (binding.chatContainer.isVisible) hideChat() else showChatForSlot(activeSlotIndex)
    }

    private fun toggleCombinedChat() {
        if (combinedChat) showChatForSlot(activeSlotIndex) else showCombinedChat()
    }

    private fun showCombinedChat() {
        val streams = slots.mapNotNull { it.stream }
        if (streams.size < 2) return
        combinedChat = true
        chatSlot = null
        updateOrientationLayout()
        binding.chatContainer.isVisible = true
        childFragmentManager.beginTransaction()
            .replace(
                R.id.chatContent,
                CombinedChatFragment.newInstance(streams),
                CHAT_TAG,
            )
            .commit()
        updateToolbar()
    }

    private fun showChatForSlot(index: Int) {
        val slot = slots.getOrNull(index) ?: return
        val stream = slot.stream ?: return
        if (stream.channelLogin.isNullOrBlank()) return
        combinedChat = false
        chatSlot = slot
        updateOrientationLayout()
        binding.chatContainer.isVisible = true
        childFragmentManager.beginTransaction()
            .replace(
                R.id.chatContent,
                ChatFragment.newInstance(stream.channelId, stream.channelLogin, displayName(stream), stream.id),
                CHAT_TAG,
            )
            .commit()
        updateToolbar()
    }

    private fun hideChat() {
        binding.chatContainer.isVisible = false
        combinedChat = false
        chatSlot = null
        childFragmentManager.findFragmentByTag(CHAT_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commit()
        }
        updateToolbar()
    }

    private fun showAddStreamPicker() {
        if (slots.size >= MAX_STREAMS) {
            Toast.makeText(requireContext(), R.string.multiview_max_streams, Toast.LENGTH_SHORT).show()
            return
        }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val description = TextView(requireContext()).apply {
            text = getString(R.string.multiview_picker_description)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val content = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 110 else 320),
            )
        }
        val recycler = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(4))
        }
        val progress = ProgressBar(requireContext())
        val empty = TextView(requireContext()).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.multiview_no_live_channels)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isVisible = false
        }
        content.addView(recycler, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        content.addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        content.addView(empty, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val input = TextInputEditText(requireContext()).apply {
            setSingleLine(true)
            hint = getString(R.string.multiview_channel_hint)
        }
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.multiview_channel_hint)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(input)
        }
        val useButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.multiview_use_channel)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        val manualRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            addView(inputLayout)
            addView(useButton)
        }
        root.addView(description)
        root.addView(content)
        root.addView(manualRow)

        var pickerDialog: AlertDialog? = null
        val selectStream: (Stream) -> Unit = { stream ->
            val login = stream.channelLogin?.trim()?.lowercase()
            when {
                login.isNullOrBlank() -> Unit
                isDuplicate(login) -> Toast.makeText(requireContext(), R.string.multiview_duplicate_stream, Toast.LENGTH_SHORT).show()
                addStream(stream) -> pickerDialog?.dismiss()
            }
        }
        val pagingAdapter: PagingDataAdapter<Stream, out RecyclerView.ViewHolder> =
            if (requireContext().prefs().getString(C.COMPACT_STREAMS, "disabled") != "disabled") {
                StreamsCompactAdapter(this, {}, showGame = false, onStreamClick = selectStream)
            } else {
                StreamsAdapter(this, {}, showGame = false, onStreamClick = selectStream)
            }
        recycler.adapter = pagingAdapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_picker_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        pickerDialog = dialog
        var pagingJob: Job? = null
        var loadStateJob: Job? = null
        dialog.setOnDismissListener {
            pagingJob?.cancel()
            loadStateJob?.cancel()
        }
        useButton.setOnClickListener {
            val login = input.text?.toString()?.trim()?.removePrefix("@")?.lowercase().orEmpty()
            if (login.isBlank()) {
                inputLayout.error = getString(R.string.multiview_add_stream_empty)
                return@setOnClickListener
            }
            if (isDuplicate(login)) {
                inputLayout.error = getString(R.string.multiview_duplicate_stream)
                return@setOnClickListener
            }
            inputLayout.error = null
            useButton.isEnabled = false
            lifecycleScope.launch {
                val stream = runCatching { viewModel.resolveLiveStream(login) }.getOrNull()
                if (stream == null) {
                    inputLayout.error = getString(R.string.multiview_stream_not_found)
                    useButton.isEnabled = true
                } else if (addStream(stream)) {
                    dialog.dismiss()
                } else {
                    inputLayout.error = getString(R.string.multiview_duplicate_stream)
                    useButton.isEnabled = true
                }
            }
        }

        dialog.show()
        pagingJob = viewLifecycleOwner.lifecycleScope.launch {
            followedStreamsViewModel.flow.collectLatest { pagingData ->
                pagingAdapter.submitData(pagingData)
            }
        }
        loadStateJob = viewLifecycleOwner.lifecycleScope.launch {
            pagingAdapter.loadStateFlow.collectLatest { loadStates ->
                progress.isVisible = loadStates.refresh is LoadState.Loading
                empty.isVisible = loadStates.refresh is LoadState.NotLoading && pagingAdapter.itemCount == 0
            }
        }
    }

    private fun displayName(stream: Stream?): String {
        return stream?.channelName?.takeIf { it.isNotBlank() }
            ?: stream?.channelLogin
            ?: getString(R.string.multiview)
    }

    private fun activeVolume(): Float {
        return (requireContext().prefs().getInt(C.PLAYER_VOLUME, 100) / 100f).coerceIn(0f, 1f)
    }

    @Suppress("NewApi")
    private fun createDataSourceFactory(): DataSource.Factory {
        val application = requireContext().applicationContext as XtraApp
        val module = application.xtraModule
        val preferences = requireContext().prefs()
        val networkLibrary = preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val upstream = when {
            networkLibrary == C.HTTP_ENGINE && module.httpEngine.value != null -> {
                HttpEngineDataSource.Factory(
                    module.httpEngine.value,
                    module.cronetExecutor.value,
                    false,
                    false,
                    null,
                    null,
                    null,
                ) { false }
            }
            networkLibrary == C.CRONET && module.cronetEngine.value != null -> {
                CronetDataSource.Factory(
                    module.cronetEngine.value,
                    module.cronetExecutor.value,
                    false,
                    false,
                    null,
                    null,
                    null,
                ) { false }
            }
            else -> OkHttpDataSource.Factory(module.okHttpClient.value, null) { false }
        }
        return DefaultDataSource.Factory(requireContext(), upstream)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class Slot(
        var index: Int,
        val container: FrameLayout,
        val playerView: PlayerView,
        val channel: TextView,
        val status: TextView,
        val audioButton: ImageButton,
        val chatButton: ImageButton,
        val removeButton: ImageButton,
    ) {
        var stream: Stream? = null
        var player: ExoPlayer? = null
        var loadJob: Job? = null

        fun release() {
            loadJob?.cancel()
            loadJob = null
            playerView.player = null
            player?.release()
            player = null
        }
    }

    companion object {
        const val ARG_STREAM = "multiview_stream"
        private const val KEY_STREAMS = "multiview_streams"
        private const val KEY_ACTIVE_SLOT = "multiview_active_slot"
        private const val CHAT_TAG = "multiview_chat"
        private const val MAX_STREAMS = 4
        private const val LANDSCAPE_CHAT_WEIGHT = 0.7f
        private const val LANDSCAPE_COMBINED_CHAT_WEIGHT = 1f
        private const val PORTRAIT_CHAT_WEIGHT = 0.42f

        fun arguments(stream: Stream): Bundle = Bundle().apply {
            putParcelable(ARG_STREAM, stream)
        }
    }
}

private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}

private inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}
