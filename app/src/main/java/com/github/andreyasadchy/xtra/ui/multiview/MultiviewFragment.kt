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
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentMultiviewBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.player.lowlatency.CronetDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.HttpEngineDataSource
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
@OptIn(UnstableApi::class)
class MultiviewFragment : Fragment(R.layout.fragment_multiview) {

    private var _binding: FragmentMultiviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MultiviewViewModel by viewModels { MultiviewViewModel.MultiviewViewModelFactory }

    private lateinit var firstSlot: Slot
    private lateinit var secondSlot: Slot
    private var activeSlot = 0
    private var chatSlot: Int? = null
    private var previousNavBarVisibility = View.VISIBLE
    private var wasPlaying = booleanArrayOf(false, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMultiviewBinding.bind(view)

        val mainActivity = requireActivity() as? MainActivity
        mainActivity?.findViewById<View>(R.id.navBarContainer)?.let {
            previousNavBarVisibility = it.visibility
            it.visibility = View.GONE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.multiviewRoot) { root, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        firstSlot = createSlot(binding.streamOneContainer, 0)
        secondSlot = createSlot(binding.streamTwoContainer, 1)
        configureGrid()

        binding.addStreamButton.setOnClickListener { showAddStreamDialog() }
        binding.chatButton.setOnClickListener { toggleChat() }
        binding.closeButton.setOnClickListener { findNavController().navigateUp() }

        val firstStream = savedInstanceState?.parcelable<Stream>(KEY_FIRST_STREAM)
            ?: requireArguments().parcelable<Stream>(ARG_STREAM)
        val secondStream = savedInstanceState?.parcelable<Stream>(KEY_SECOND_STREAM)
        if (firstStream != null) {
            startSlot(firstSlot, firstStream)
        } else {
            showSlotMessage(firstSlot, getString(R.string.multiview_stream_not_found), error = true)
        }
        if (secondStream != null) {
            startSlot(secondSlot, secondStream)
        } else {
            resetSlot(secondSlot)
        }
        setActiveSlot(savedInstanceState?.getInt(KEY_ACTIVE_SLOT, 0) ?: 0)
        updateToolbar()
    }

    override fun onStart() {
        super.onStart()
        firstSlotOrNull()?.player?.let { player ->
            if (wasPlaying[0]) player.playWhenReady = true
        }
        secondSlotOrNull()?.player?.let { player ->
            if (wasPlaying[1]) player.playWhenReady = true
        }
    }

    override fun onStop() {
        wasPlaying[0] = firstSlotOrNull()?.player?.playWhenReady == true
        wasPlaying[1] = secondSlotOrNull()?.player?.playWhenReady == true
        firstSlotOrNull()?.player?.playWhenReady = false
        secondSlotOrNull()?.player?.playWhenReady = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        firstSlotOrNull()?.stream?.let { outState.putParcelable(KEY_FIRST_STREAM, it) }
        secondSlotOrNull()?.stream?.let { outState.putParcelable(KEY_SECOND_STREAM, it) }
        outState.putInt(KEY_ACTIVE_SLOT, activeSlot)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        firstSlotOrNull()?.release()
        secondSlotOrNull()?.release()
        childFragmentManager.findFragmentByTag(CHAT_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
        requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = previousNavBarVisibility
        _binding = null
        super.onDestroyView()
    }

    private fun configureGrid() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.videoGrid.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        firstSlotOrNull()?.container?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0
            weight = 1f
        }
        secondSlotOrNull()?.container?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0
            weight = 1f
        }
        binding.streamDivider.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 2 else ViewGroup.LayoutParams.MATCH_PARENT
            height = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 2
            weight = 0f
        }
    }

    private fun createSlot(container: FrameLayout, index: Int): Slot {
        val playerView = PlayerView(requireContext()).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        container.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        container.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        lateinit var slot: Slot
        val status = TextView(requireContext()).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setOnClickListener { slot.stream?.let { startSlot(slot, it) } }
        }
        container.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        slot = Slot(index, container, playerView, channel, status, audioButton, chatButton, removeButton)
        container.setOnClickListener { setActiveSlot(index) }
        playerView.setOnClickListener { setActiveSlot(index) }
        audioButton.setOnClickListener { setActiveSlot(index) }
        chatButton.setOnClickListener {
            setActiveSlot(index)
            showChatForSlot(index)
        }
        removeButton.setOnClickListener { removeSecondStream() }
        removeButton.isVisible = index == 1
        audioButton.isVisible = index == 0
        chatButton.isVisible = false
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
        slot.player?.release()
        slot.stream = stream
        slot.channel.text = displayName(stream)
        slot.audioButton.isVisible = true
        slot.chatButton.isVisible = !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        slot.removeButton.isVisible = slot.index == 1
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
                if (slot.stream?.channelLogin?.lowercase() != channelLogin) return@launch
                val mediaItem = MediaItem.Builder()
                    .setUri(playlistUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(requireContext().prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull() ?: 2000L)
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
                player.volume = if (slot.index == activeSlot) activeVolume() else 0f
                player.playWhenReady = true
                updateToolbar()
            } catch (_: Exception) {
                if (slot.stream?.channelLogin?.lowercase() == channelLogin) {
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

    private fun resetSlot(slot: Slot) {
        slot.loadJob?.cancel()
        slot.loadJob = null
        slot.playerView.player = null
        slot.player?.release()
        slot.player = null
        slot.stream = null
        slot.channel.text = null
        slot.audioButton.isVisible = false
        slot.chatButton.isVisible = false
        slot.removeButton.isVisible = false
        showSlotMessage(slot, getString(R.string.multiview_empty_slot), error = false)
    }

    private fun removeSecondStream() {
        if (secondSlot.stream == null) return
        if (chatSlot == 1) hideChat()
        resetSlot(secondSlot)
        setActiveSlot(0)
        updateToolbar()
    }

    private fun setActiveSlot(index: Int) {
        if (index !in 0..1 || (index == 1 && secondSlotOrNull()?.stream == null)) return
        activeSlot = index
        val volume = activeVolume()
        firstSlotOrNull()?.let { slot ->
            slot.player?.volume = if (slot.index == index) volume else 0f
            updateAudioButton(slot)
        }
        secondSlotOrNull()?.let { slot ->
            slot.player?.volume = if (slot.index == index) volume else 0f
            updateAudioButton(slot)
        }
        if (binding.chatContainer.isVisible) showChatForSlot(index)
        updateToolbar()
    }

    private fun updateAudioButton(slot: Slot) {
        val isActive = slot.index == activeSlot
        slot.audioButton.setImageResource(if (isActive) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
        slot.audioButton.contentDescription = getString(
            if (isActive) R.string.multiview_audio_active else R.string.multiview_audio_muted,
            displayName(slot.stream),
        )
    }

    private fun updateToolbar() {
        val active = if (activeSlot == 0) firstSlotOrNull()?.stream else secondSlotOrNull()?.stream
        binding.activeAudio.text = active?.let { getString(R.string.multiview_audio, displayName(it)) }
        binding.addStreamButton.isVisible = secondSlotOrNull()?.stream == null
        binding.chatButton.isVisible = active != null && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        binding.chatButton.text = getString(if (binding.chatContainer.isVisible) R.string.multiview_hide_chat else R.string.multiview_chat)
        firstSlotOrNull()?.let(::updateAudioButton)
        secondSlotOrNull()?.let(::updateAudioButton)
    }

    private fun toggleChat() {
        if (binding.chatContainer.isVisible) hideChat() else showChatForSlot(activeSlot)
    }

    private fun showChatForSlot(index: Int) {
        val stream = if (index == 0) firstSlotOrNull()?.stream else secondSlotOrNull()?.stream
        if (stream?.channelLogin.isNullOrBlank()) return
        chatSlot = index
        binding.chatContainer.isVisible = true
        childFragmentManager.beginTransaction()
            .replace(
                R.id.chatContainer,
                ChatFragment.newInstance(stream.channelId, stream.channelLogin, displayName(stream), stream.id),
                CHAT_TAG,
            )
            .commit()
        updateToolbar()
    }

    private fun hideChat() {
        binding.chatContainer.isVisible = false
        chatSlot = null
        childFragmentManager.findFragmentByTag(CHAT_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commit()
        }
        updateToolbar()
    }

    private fun showAddStreamDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = getString(R.string.multiview_channel_hint)
            setSingleLine(true)
        }
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.multiview_channel_hint)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_add_stream_title)
            .setMessage(R.string.multiview_add_stream_description)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.multiview_add_stream, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val login = input.text?.toString()?.trim()?.removePrefix("@")?.lowercase().orEmpty()
                if (login.isBlank()) {
                    inputLayout.error = getString(R.string.multiview_add_stream_empty)
                    return@setOnClickListener
                }
                if (login == firstSlotOrNull()?.stream?.channelLogin?.lowercase()) {
                    inputLayout.error = getString(R.string.multiview_stream_not_found)
                    return@setOnClickListener
                }
                inputLayout.error = null
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    val stream = viewModel.resolveLiveStream(login)
                    if (stream == null) {
                        inputLayout.error = getString(R.string.multiview_stream_not_found)
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    } else {
                        dialog.dismiss()
                        startSlot(secondSlot, stream)
                        setActiveSlot(activeSlot)
                        updateToolbar()
                    }
                }
            }
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.post {
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(input, 0)
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

    private fun firstSlotOrNull(): Slot? = if (::firstSlot.isInitialized) firstSlot else null

    private fun secondSlotOrNull(): Slot? = if (::secondSlot.isInitialized) secondSlot else null

    private class Slot(
        val index: Int,
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
        private const val KEY_FIRST_STREAM = "multiview_first_stream"
        private const val KEY_SECOND_STREAM = "multiview_second_stream"
        private const val KEY_ACTIVE_SLOT = "multiview_active_slot"
        private const val CHAT_TAG = "multiview_chat"

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
