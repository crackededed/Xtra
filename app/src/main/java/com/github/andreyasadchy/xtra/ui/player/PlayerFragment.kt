package com.github.andreyasadchy.xtra.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Chronometer
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.trackPipAnimationHintView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlaybackService.CustomHlsPlaylistParserFactory
import com.github.andreyasadchy.xtra.ui.view.SlidingLayout
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.disable
import com.github.andreyasadchy.xtra.util.enable
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.hideKeyboard
import com.github.andreyasadchy.xtra.util.isInPortraitOrientation
import com.github.andreyasadchy.xtra.util.isKeyboardShown
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerFragment : BaseNetworkFragment(), SlidingLayout.Listener, PlayerGamesDialog.PlayerSeekListener, SleepTimerDialog.OnSleepTimerStartedListener, RadioButtonDialogFragment.OnSortOptionChanged, PlayerVolumeDialog.PlayerVolumeListener, IntegrityDialog.CallbackListener {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by viewModels()
    private var chatFragment: ChatFragment? = null

    private var playbackService: CustomPlaybackService? = null
    private var serviceConnection: ServiceConnection? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val player: Player?
        get() = playbackService?.player ?: controllerFuture?.let { if (it.isDone && !it.isCancelled) it.get() else null }

    private var videoType: String? = null
    private var isPortrait = false
    private var isKeyboardShown = false
    private var resizeMode = 0
    private var chatWidthLandscape = 0

    private lateinit var prefs: SharedPreferences

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            minimize()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        videoType = requireArguments().getString(KEY_TYPE)
        if (videoType == OFFLINE_VIDEO) {
            enableNetworkCheck = false
        }
        super.onCreate(savedInstanceState)
        val activity = requireActivity()
        prefs = activity.prefs()
        isPortrait = activity.isInPortraitOrientation
        activity.onBackPressedDispatcher.addCallback(this, backPressedCallback)
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false).also {
            it.slidingLayout.orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            slidingLayout.updateBackgroundColor(isPortrait)
            val ignoreCutouts = prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
            val cornerPadding = prefs.getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = if (!isPortrait && ignoreCutouts) {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                } else {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
                }
                if (isPortrait) {
                    view.updatePadding(left = 0, top = insets.top, right = 0)
                } else {
                    if (ignoreCutouts) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cornerPadding) {
                            val rootWindowInsets = view.rootView.rootWindowInsets
                            val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                            val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                            val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                            val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                            val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                            val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                            view.updatePadding(left = leftRadius, top = 0, right = rightRadius)
                        } else {
                            view.updatePadding(left = 0, top = 0, right = 0)
                        }
                    } else {
                        val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cornerPadding) {
                            val rootWindowInsets = view.rootView.rootWindowInsets
                            val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                            val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                            val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                            val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                            val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                            val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                            view.updatePadding(left = max(cutoutInsets.left, leftRadius), top = 0, right = max(cutoutInsets.right, rightRadius))
                        } else {
                            view.updatePadding(left = cutoutInsets.left, top = 0, right = cutoutInsets.right)
                        }
                    }
                }
                slidingLayout.apply {
                    val update = isMaximized && isPortrait && savedInsets != null
                    savedInsets = insets
                    if (update) {
                        init()
                    }
                }
                chatLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = insets.bottom
                }
                WindowInsetsCompat.CONSUMED
            }
            viewLifecycleOwner.lifecycleScope.launch {
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
            val activity = requireActivity() as MainActivity
            slidingLayout.addListener(activity)
            slidingLayout.addListener(this@PlayerFragment)
            slidingLayout.maximizedSecondViewVisibility = if (prefs.getBoolean(C.KEY_CHAT_OPENED, true)) View.VISIBLE else View.GONE //TODO
            if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        requireActivity().trackPipAnimationHintView(playerView)
                    }
                }
            }
            aspectRatioFrameLayout.setAspectRatio(16f / 9f)
            chatWidthLandscape = prefs.getInt(C.LANDSCAPE_CHAT_WIDTH, 0)
            if (prefs.getBoolean(C.PLAYER_FULLSCREEN, true)) {
                view.findViewById<ImageButton>(R.id.playerFullscreenToggle)?.apply {
                    visible()
                    setOnClickListener {
                        activity.apply {
                            if (isPortrait) {
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    }
                }
            }
            if (prefs.getBoolean(C.PLAYER_ASPECT, true)) {
                view.findViewById<ImageButton>(R.id.playerAspectRatio)?.apply {
                    setOnClickListener { setResizeMode() }
                }
            }
            initLayout()
            playerView.controllerAutoShow = videoType != STREAM
            if (prefs.getBoolean(C.PLAYER_DOUBLETAP, true) && !prefs.getBoolean(C.CHAT_DISABLE, false)) {
                playerView.setOnDoubleTapListener {
                    if (!isPortrait && slidingLayout.isMaximized) {
                        if (chatLayout.isVisible) {
                            hideChat()
                        } else {
                            showChat()
                        }
                    }
                }
            }
            if (prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false)) {
                view.keepScreenOn = true
            }
            changePlayerMode()
            val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
            val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
            val displayName = if (channelLogin != null && !channelLogin.equals(channelName, true)) {
                when (prefs.getString(C.UI_NAME_DISPLAY, "0")) {
                    "0" -> "${channelName}(${channelLogin})"
                    "1" -> channelName
                    else -> channelLogin
                }
            } else {
                channelName
            }
            if (prefs.getBoolean(C.PLAYER_CHANNEL, true)) {
                requireView().findViewById<TextView>(R.id.playerChannel)?.apply {
                    visible()
                    text = displayName
                    setOnClickListener {
                        findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                            )
                        )
                        slidingLayout.minimize()
                    }
                }
            }
            requireArguments().getString(KEY_TITLE).let { title ->
                if (!title.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
                    requireView().findViewById<TextView>(R.id.playerTitle)?.apply {
                        visible()
                        text = title
                    }
                }
            }
            requireArguments().getString(KEY_GAME_NAME).let { gameName ->
                if (!gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
                    requireView().findViewById<TextView>(R.id.playerCategory)?.apply {
                        visible()
                        text = gameName
                        setOnClickListener {
                            findNavController().navigate(
                                if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameId = requireArguments().getString(KEY_GAME_ID),
                                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                        gameName = gameName
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameId = requireArguments().getString(KEY_GAME_ID),
                                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                        gameName = gameName
                                    )
                                }
                            )
                            slidingLayout.minimize()
                        }
                    }
                }
            }
            if (prefs.getBoolean(C.PLAYER_MINIMIZE, true)) {
                view.findViewById<ImageButton>(R.id.playerMinimize)?.apply {
                    visible()
                    setOnClickListener { minimize() }
                }
            }
            if (prefs.getBoolean(C.PLAYER_VOLUMEBUTTON, true)) {
                view.findViewById<ImageButton>(R.id.playerVolume)?.apply {
                    visible()
                    setOnClickListener { showVolumeDialog() }
                }
            }
            if (prefs.getBoolean(C.PLAYER_SETTINGS, true)) {
                view.findViewById<ImageButton>(R.id.playerSettings)?.apply {
                    visible()
                    setOnClickListener { showQualityDialog() }
                }
            }
            if (prefs.getBoolean(C.PLAYER_MODE, false)) {
                view.findViewById<ImageButton>(R.id.playerMode)?.apply {
                    visible()
                    setOnClickListener {
                        if (viewModel.quality == AUDIO_ONLY_QUALITY) {
                            changeQuality(viewModel.previousQuality)
                        } else {
                            changeQuality(AUDIO_ONLY_QUALITY)
                        }
                        changePlayerMode()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(C.PLAYER_AUDIO_COMPRESSOR_BUTTON, true)) {
                view.findViewById<ImageButton>(R.id.playerAudioCompressor)?.apply {
                    visible()
                    if (prefs.getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                    setOnClickListener {
                        if (player is ExoPlayer) {
                            val enabled = playbackService?.toggleDynamicsProcessing()
                            if (enabled == true) {
                                setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                            } else {
                                setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                            }
                        } else {
                            (player as? MediaController)?.sendCustomCommand(
                                SessionCommand(
                                    PlaybackService.TOGGLE_DYNAMICS_PROCESSING,
                                    Bundle.EMPTY
                                ), Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val state = result.get().extras.getBoolean(PlaybackService.RESULT)
                                        if (state) {
                                            setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                                        } else {
                                            setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                                        }
                                    }
                                }, MoreExecutors.directExecutor())
                            }
                        }
                    }
                }
            }
            if (prefs.getBoolean(C.PLAYER_MENU, true)) {
                requireView().findViewById<ImageButton>(R.id.playerMenu)?.apply {
                    visible()
                    setOnClickListener {
                        PlayerSettingsDialog.newInstance(
                            videoType = videoType,
                            speedText = prefs.getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")
                                ?.split("\n")?.find { it == player?.playbackParameters?.speed.toString() },
                            vodGames = !viewModel.gamesList.value.isNullOrEmpty()
                        ).show(childFragmentManager, "closeOnPip")
                    }
                }
            }
            if (videoType == STREAM) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.streamResult.collectLatest {
                            if (it != null) {
                                if (player is ExoPlayer) {
                                    (player as? ExoPlayer)?.let { player ->
                                        playbackService?.videoId = null
                                        playbackService?.offlineVideoId = null
                                        playbackService?.proxyMediaPlaylist = false
                                        player.setMediaSource(
                                            HlsMediaSource.Factory(
                                                DefaultDataSource.Factory(
                                                    requireContext(),
                                                    viewModel.getDataSourceFactory(
                                                        networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                                        proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                                                        proxyMediaPlaylist = prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true),
                                                        proxyHost = prefs.getString(C.PROXY_HOST, null),
                                                        proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                                                        proxyUser = prefs.getString(C.PROXY_USER, null),
                                                        proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                                                        useProxy = { playbackService?.proxyMediaPlaylist == true }
                                                    ).apply {
                                                        prefs.getString(C.PLAYER_STREAM_HEADERS, null)?.let {
                                                            try {
                                                                val json = JSONObject(it)
                                                                hashMapOf<String, String>().apply {
                                                                    json.keys().forEach { key ->
                                                                        put(key, json.optString(key))
                                                                    }
                                                                }
                                                            } catch (e: Exception) {
                                                                null
                                                            }
                                                        }?.let {
                                                            setDefaultRequestProperties(it)
                                                        }
                                                    }
                                                )
                                            ).apply {
                                                setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                                setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                                            }.createMediaSource(
                                                MediaItem.Builder().apply {
                                                    setUri(it.toUri())
                                                    setMimeType(MimeTypes.APPLICATION_M3U8)
                                                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                                        prefs.getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                                        prefs.getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                                        prefs.getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                                    }.build())
                                                    setMediaMetadata(
                                                        MediaMetadata.Builder().apply {
                                                            setTitle(requireArguments().getString(KEY_TITLE))
                                                            setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                                            setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                                                        }.build()
                                                    )
                                                }.build()
                                            )
                                        )
                                        player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                        player.setPlaybackSpeed(1f)
                                        player.prepare()
                                        player.playWhenReady = true
                                    }
                                } else {
                                    (player as? MediaController)?.sendCustomCommand(
                                        SessionCommand(
                                            PlaybackService.START_STREAM, bundleOf(
                                                PlaybackService.URI to it,
                                                PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                                                PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                                                PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                                            )
                                        ), Bundle.EMPTY
                                    )
                                }
                                viewModel.streamResult.value = null
                            }
                        }
                    }
                }
                if (!requireContext().tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                    (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                            !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                ) {
                    if (prefs.getBoolean(C.PLAYER_CHATBARTOGGLE, false) && !prefs.getBoolean(C.CHAT_DISABLE, false)) {
                        view.findViewById<ImageButton>(R.id.playerChatBarToggle)?.apply {
                            visible()
                            setOnClickListener { toggleChatBar() }
                        }
                    }
                    slidingLayout.viewTreeObserver.addOnGlobalLayoutListener {
                        if (slidingLayout.isKeyboardShown) {
                            if (!isKeyboardShown) {
                                isKeyboardShown = true
                                if (!isPortrait) {
                                    chatLayout.updateLayoutParams { width = (slidingLayout.width / 1.8f).toInt() }
                                    showStatusBar()
                                }
                            }
                        } else {
                            if (isKeyboardShown) {
                                isKeyboardShown = false
                                chatLayout.clearFocus()
                                if (!isPortrait) {
                                    chatLayout.updateLayoutParams { width = chatWidthLandscape }
                                    if (slidingLayout.isMaximized) {
                                        hideStatusBar()
                                    }
                                }
                            }
                        }
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.stream.collectLatest { stream ->
                            if (stream != null) {
                                stream.id?.let { chatFragment?.updateStreamId(it) }
                                if (prefs.getBoolean(C.CHAT_DISABLE, false) ||
                                    !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                                    requireView().findViewById<TextView>(R.id.playerViewersText)?.text.isNullOrBlank()
                                ) {
                                    updateViewerCount(stream.viewerCount)
                                }
                                if (prefs.getBoolean(C.CHAT_DISABLE, false) ||
                                    !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                                    requireView().findViewById<TextView>(R.id.playerTitle)?.text.isNullOrBlank() ||
                                    requireView().findViewById<TextView>(R.id.playerCategory)?.text.isNullOrBlank()
                                ) {
                                    updateStreamInfo(stream.title, stream.gameId, stream.gameSlug, stream.gameName)
                                }
                                if (prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true) &&
                                    requireView().findViewById<LinearLayout>(R.id.playerUptime)?.isVisible == false
                                ) {
                                    stream.startedAt?.let { date ->
                                        TwitchApiHelper.parseIso8601DateUTC(date)?.let { startedAtMs ->
                                            updateUptime(startedAtMs)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (prefs.getBoolean(C.PLAYER_RESTART, true)) {
                    requireView().findViewById<ImageButton>(R.id.playerRestart)?.apply {
                        visible()
                        setOnClickListener { restartPlayer() }
                    }
                }
                if (prefs.getBoolean(C.PLAYER_SEEKLIVE, false)) {
                    requireView().findViewById<ImageButton>(R.id.playerSeekLive)?.apply {
                        visible()
                        setOnClickListener { player?.seekToDefaultPosition() }
                    }
                }
                if (prefs.getBoolean(C.PLAYER_VIEWERLIST, false)) {
                    requireView().findViewById<LinearLayout>(R.id.playerViewers)?.apply {
                        setOnClickListener { openViewerList() }
                    }
                }
                if (prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
                    requireArguments().getString(KEY_STARTED_AT)?.let {
                        TwitchApiHelper.parseIso8601DateUTC(it)?.let { startedAtMs ->
                            updateUptime(startedAtMs)
                        }
                    }
                }
                requireView().findViewById<Button>(androidx.media3.ui.R.id.exo_rew_with_amount)?.gone()
                requireView().findViewById<Button>(androidx.media3.ui.R.id.exo_ffwd_with_amount)?.gone()
                requireView().findViewById<TextView>(androidx.media3.ui.R.id.exo_position)?.gone()
                requireView().findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.gone()
                requireView().findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)?.gone()
                updateStreamInfo(
                    requireArguments().getString(KEY_TITLE),
                    requireArguments().getString(KEY_GAME_ID),
                    requireArguments().getString(KEY_GAME_SLUG),
                    requireArguments().getString(KEY_GAME_NAME)
                )
                updateViewerCount(requireArguments().getInt(KEY_VIEWER_COUNT).takeIf { it != -1 })
            } else {
                if (prefs.getBoolean(C.PLAYER_SPEEDBUTTON, true)) {
                    view.findViewById<ImageButton>(R.id.playerSpeed)?.apply {
                        visible()
                        setOnClickListener { showSpeedDialog() }
                    }
                }
            }
            if (videoType == VIDEO) {
                val videoId = requireArguments().getString(KEY_VIDEO_ID)
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.videoResult.collectLatest {
                            if (it != null) {
                                if (player is ExoPlayer) {
                                    (player as? ExoPlayer)?.let { player ->
                                        val newId = videoId?.toLongOrNull()
                                        val position = if (playbackService?.videoId == newId && player.currentMediaItem != null) {
                                            player.currentPosition
                                        } else {
                                            viewModel.playbackPosition ?: 0
                                        }
                                        playbackService?.videoId = newId
                                        playbackService?.offlineVideoId = null
                                        player.setMediaSource(
                                            HlsMediaSource.Factory(
                                                DefaultDataSource.Factory(
                                                    requireContext(),
                                                    viewModel.getDataSourceFactory(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"))
                                                )
                                            ).apply {
                                                setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                            }.createMediaSource(
                                                MediaItem.Builder().apply {
                                                    setUri(it.toUri())
                                                    setMediaMetadata(
                                                        MediaMetadata.Builder().apply {
                                                            setTitle(requireArguments().getString(KEY_TITLE))
                                                            setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                                            setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                                                        }.build()
                                                    )
                                                }.build()
                                            )
                                        )
                                        player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                        player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
                                        player.prepare()
                                        player.playWhenReady = true
                                        player.seekTo(position)
                                    }
                                } else {
                                    (player as? MediaController)?.sendCustomCommand(
                                        SessionCommand(
                                            PlaybackService.START_VIDEO, bundleOf(
                                                PlaybackService.URI to it,
                                                PlaybackService.PLAYBACK_POSITION to viewModel.playbackPosition,
                                                PlaybackService.VIDEO_ID to videoId?.toLongOrNull(),
                                                PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                                                PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                                                PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                                            )
                                        ), Bundle.EMPTY
                                    )
                                }
                                viewModel.videoResult.value = null
                            }
                        }
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.savedPosition.collectLatest {
                            if (it != null) {
                                playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, it)
                                viewModel.savedPosition.value = null
                            }
                        }
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.isBookmarked.collectLatest {
                                if (it != null) {
                                    (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setBookmarkText(it)
                                    viewModel.isBookmarked.value = null
                                }
                            }
                        }
                    }
                }
                if (!videoId.isNullOrBlank() && (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false))) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.gamesList.collectLatest { list ->
                                if (!list.isNullOrEmpty()) {
                                    if (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true)) {
                                        requireView().findViewById<ImageButton>(R.id.playerGames)?.apply {
                                            visible()
                                            setOnClickListener { showVodGames() }
                                        }
                                    }
                                    (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setVodGames()
                                }
                            }
                        }
                    }
                }
            }
            if (videoType == CLIP) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.clipUrls.collectLatest { map ->
                            if (map != null) {
                                val supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")?.split(',') ?: emptyList()
                                val filtered = map.filterNot {
                                    it.key.second?.substringBefore('.').let { codec ->
                                        (codec == "av01" && !supportedCodecs.contains("av1")) || ((codec == "hev1" || codec == "hvc1") && !supportedCodecs.contains("h265"))
                                    }
                                }
                                val hideCodecs = filtered.all {
                                    it.key.second?.substringBefore('.').let { codec ->
                                        codec == "avc1" || codec == "mp4a" || codec.isNullOrBlank()
                                    }
                                }
                                val map = mutableMapOf<String, Pair<String, String?>>()
                                filtered.forEach {
                                    val quality = it.key.first.let { quality ->
                                        val quality = if (quality == "source") {
                                            requireContext().getString(R.string.source)
                                        } else {
                                            quality
                                        }
                                        if (hideCodecs) {
                                            quality
                                        } else {
                                            val codec = it.key.second?.substringBefore('.').let { codec ->
                                                when {
                                                    codec == "av01" -> "AV1"
                                                    codec == "hev1" || codec == "hvc1" -> "H.265"
                                                    codec == "avc1" || codec.isNullOrBlank() -> "H.264"
                                                    else -> it
                                                }
                                            }
                                            "$quality $codec"
                                        }
                                    }
                                    map[it.key.first] = Pair(quality, it.value)
                                }
                                map.put(AUDIO_ONLY_QUALITY, Pair(requireContext().getString(R.string.audio_only), null))
                                viewModel.qualities = map.toList()
                                    .sortedByDescending {
                                        it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                    }
                                    .sortedByDescending {
                                        it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                    }
                                    .sortedByDescending {
                                        it.first == "source"
                                    }
                                    .toMap()
                                setDefaultQuality()
                                player?.let { player ->
                                    val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
                                    if (quality?.key == AUDIO_ONLY_QUALITY) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                        }.build()
                                    } else {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                        }.build()
                                    }
                                    (quality?.value?.second ?: viewModel.qualities.values.firstOrNull()?.second)?.let { url ->
                                        if (player is ExoPlayer) {
                                            playbackService?.videoId = null
                                            playbackService?.offlineVideoId = null
                                            player.setMediaSource(
                                                ProgressiveMediaSource.Factory(
                                                    DefaultDataSource.Factory(
                                                        requireContext(),
                                                        viewModel.getDataSourceFactory(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"))
                                                    )
                                                ).createMediaSource(
                                                    MediaItem.Builder().apply {
                                                        setUri(url.toUri())
                                                        setMediaMetadata(
                                                            MediaMetadata.Builder().apply {
                                                                setTitle(requireArguments().getString(KEY_TITLE))
                                                                setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                                                setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                                                            }.build()
                                                        )
                                                    }.build()
                                                )
                                            )
                                            player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                            player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
                                            player.prepare()
                                            player.playWhenReady = true
                                        } else {
                                            (player as? MediaController)?.sendCustomCommand(
                                                SessionCommand(
                                                    PlaybackService.START_CLIP, bundleOf(
                                                        PlaybackService.URI to url,
                                                        PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                                                        PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                                                        PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                                                    )
                                                ), Bundle.EMPTY
                                            )
                                        }
                                    }
                                }
                                viewModel.clipUrls.value = null
                            }
                        }
                    }
                }
                val videoId = requireArguments().getString(KEY_VIDEO_ID)
                if (!videoId.isNullOrBlank()) {
                    watchVideo.visible()
                    watchVideo.setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val offset = requireArguments().getInt(KEY_VOD_OFFSET).takeIf { it != -1 }?.let {
                                (it * 1000) + (player?.currentPosition ?: 0)
                            } ?: 0
                            if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                                videoId.toLongOrNull()?.let { id ->
                                    viewModel.savePosition(id, offset)
                                }
                            }
                            (requireActivity() as MainActivity).startVideo(
                                Video(
                                    id = videoId,
                                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                    channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                                    profileImageUrl = requireArguments().getString(KEY_PROFILE_IMAGE_URL),
                                    animatedPreviewURL = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW)
                                ),
                                offset,
                                true
                            )
                        }
                    }
                }
            } else {
                if (prefs.getBoolean(C.PLAYER_SLEEP, false)) {
                    view.findViewById<ImageButton>(R.id.playerSleepTimer)?.apply {
                        visible()
                        setOnClickListener { showSleepTimerDialog() }
                    }
                }
            }
            if (videoType == OFFLINE_VIDEO) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.savedOfflineVideoPosition.collectLatest {
                            if (it != null) {
                                val url = requireArguments().getString(KEY_URL)
                                viewModel.qualities = mapOf(
                                    "source" to Pair(requireContext().getString(R.string.source), url),
                                    AUDIO_ONLY_QUALITY to Pair(requireContext().getString(R.string.audio_only), null)
                                )
                                setDefaultQuality()
                                player?.let { player ->
                                    val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
                                    if (quality?.key == AUDIO_ONLY_QUALITY) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                        }.build()
                                    } else {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                        }.build()
                                    }
                                    if (player is ExoPlayer) {
                                        val newId = requireArguments().getInt(KEY_OFFLINE_VIDEO_ID).takeIf { it != 0 }
                                        val position = if (playbackService?.offlineVideoId == newId && player.currentMediaItem != null) {
                                            player.currentPosition
                                        } else {
                                            it
                                        }
                                        playbackService?.videoId = null
                                        playbackService?.offlineVideoId = newId
                                        player.setMediaItem(
                                            MediaItem.Builder().apply {
                                                setUri(url)
                                                setMediaMetadata(
                                                    MediaMetadata.Builder().apply {
                                                        setTitle(requireArguments().getString(KEY_TITLE))
                                                        setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                                        setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                                                    }.build()
                                                )
                                            }.build()
                                        )
                                        player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                        player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
                                        player.prepare()
                                        player.playWhenReady = true
                                        player.seekTo(position)
                                    } else {
                                        (player as? MediaController)?.sendCustomCommand(
                                            SessionCommand(
                                                PlaybackService.START_OFFLINE_VIDEO, bundleOf(
                                                    PlaybackService.URI to url,
                                                    PlaybackService.VIDEO_ID to requireArguments().getInt(KEY_OFFLINE_VIDEO_ID),
                                                    PlaybackService.PLAYBACK_POSITION to it,
                                                    PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                                                    PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                                                    PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                                                )
                                            ), Bundle.EMPTY
                                        )
                                    }
                                }
                                viewModel.savedOfflineVideoPosition.value = null
                            }
                        }
                    }
                }
            } else {
                val settings = requireView().findViewById<ImageButton>(R.id.playerSettings)
                val download = requireView().findViewById<ImageButton>(R.id.playerDownload)
                val mode = requireView().findViewById<ImageButton>(R.id.playerMode)
                settings?.disable()
                download?.disable()
                mode?.disable()
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.loaded.collectLatest {
                            if (it) {
                                settings?.enable()
                                download?.enable()
                                mode?.enable()
                                setQualityText()
                            }
                        }
                    }
                }
                if (prefs.getBoolean(C.PLAYER_DOWNLOAD, false)) {
                    download?.apply {
                        visible()
                        setOnClickListener { showDownloadDialog() }
                    }
                }
                val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
                if (prefs.getBoolean(C.PLAYER_FOLLOW, false) && (setting == 0 || setting == 1)) {
                    val followButton = requireView().findViewById<ImageButton>(R.id.playerFollow)
                    followButton?.visible()
                    followButton?.setOnClickListener {
                        viewModel.isFollowing.value?.let {
                            if (it) {
                                requireContext().getAlertDialogBuilder()
                                    .setMessage(requireContext().getString(R.string.unfollow_channel, displayName))
                                    .setNegativeButton(getString(R.string.no), null)
                                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                        viewModel.deleteFollowChannel(
                                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                                            requireArguments().getString(KEY_CHANNEL_ID),
                                            setting,
                                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                        )
                                    }
                                    .show()
                            } else {
                                viewModel.saveFollowChannel(
                                    requireContext().tokenPrefs().getString(C.USER_ID, null),
                                    requireArguments().getString(KEY_CHANNEL_ID),
                                    requireArguments().getString(KEY_CHANNEL_LOGIN),
                                    requireArguments().getString(KEY_CHANNEL_NAME),
                                    setting,
                                    requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                    requireArguments().getString(KEY_STARTED_AT),
                                    requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                    requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.isFollowing.collectLatest {
                                if (it != null) {
                                    followButton?.apply {
                                        if (it) {
                                            setImageResource(R.drawable.baseline_favorite_black_24)
                                        } else {
                                            setImageResource(R.drawable.baseline_favorite_border_black_24)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.follow.collectLatest { pair ->
                                if (pair != null) {
                                    val following = pair.first
                                    val errorMessage = pair.second
                                    if (!errorMessage.isNullOrBlank()) {
                                        requireContext().shortToast(errorMessage)
                                    } else {
                                        if (following) {
                                            requireContext().shortToast(requireContext().getString(R.string.now_following, displayName))
                                        } else {
                                            requireContext().shortToast(requireContext().getString(R.string.unfollowed, displayName))
                                        }
                                    }
                                    viewModel.follow.value = null
                                }
                            }
                        }
                    }
                }
            }
            chatFragment = (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) as? ChatFragment)
                ?: when (videoType) {
                    STREAM -> ChatFragment.newInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_CHANNEL_NAME),
                        requireArguments().getString(KEY_STREAM_ID)
                    )
                    VIDEO -> ChatFragment.newInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_VIDEO_ID),
                        0
                    )
                    CLIP -> ChatFragment.newInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_VIDEO_ID),
                        requireArguments().getInt(KEY_VOD_OFFSET).takeIf { it != -1 }
                    )
                    OFFLINE_VIDEO -> ChatFragment.newLocalInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_CHAT_URL)
                    )
                    else -> null
                }?.also { fragment -> childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, fragment).commit() }
        }
    }

    private fun initLayout() {
        with(binding) {
            if (isPortrait) {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener(null)
                aspectRatioFrameLayout.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                    weight = 0f
                }
                chatLayout.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    height = 0
                    weight = 1f
                }
                chatLayout.visible()
                requireView().findViewById<ImageButton>(R.id.playerFullscreenToggle)?.let {
                    if (it.isVisible) {
                        it.setImageResource(R.drawable.baseline_fullscreen_black_24)
                    }
                }
                requireView().findViewById<ImageButton>(R.id.playerAspectRatio)?.gone()
                requireView().findViewById<ImageButton>(R.id.playerChatToggle)?.gone()
                showStatusBar()
                aspectRatioFrameLayout.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener {
                    if (!isKeyboardShown && slidingLayout.isMaximized && activity != null) {
                        hideStatusBar()
                    }
                }
                aspectRatioFrameLayout.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = 0
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                    weight = 1f
                }
                chatLayout.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = chatWidthLandscape
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                    weight = 0f
                }
                if (prefs.getBoolean(C.CHAT_DISABLE, false)) {
                    chatLayout.gone()
                    slidingLayout.maximizedSecondViewVisibility = View.GONE
                } else {
                    if (prefs.getBoolean(C.KEY_CHAT_OPENED, true)) {
                        showChat()
                    } else {
                        hideChat()
                    }
                }
                if (chatLayout.isVisible && requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
                    requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                        recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
                    }
                }
                requireView().findViewById<ImageButton>(R.id.playerFullscreenToggle)?.let {
                    if (it.isVisible) {
                        it.setImageResource(R.drawable.baseline_fullscreen_exit_black_24)
                    }
                }
                requireView().findViewById<ImageButton>(R.id.playerAspectRatio)?.let {
                    if (it.hasOnClickListeners()) {
                        it.visible()
                    }
                }
                slidingLayout.post {
                    if (slidingLayout.isMaximized) {
                        hideStatusBar()
                    } else {
                        showStatusBar()
                    }
                }
                aspectRatioFrameLayout.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                resizeMode = prefs.getInt(C.ASPECT_RATIO_LANDSCAPE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
            }
            playerView.resizeMode = resizeMode
        }
    }

    fun setResizeMode() {
        resizeMode = (resizeMode + 1).let { if (it < 5) it else 0 }
        binding.playerView.resizeMode = resizeMode
        prefs.edit { putInt(C.ASPECT_RATIO_LANDSCAPE, resizeMode) }
    }

    fun showSleepTimerDialog() {
        if (requireContext().prefs().getBoolean(C.SLEEP_TIMER_USE_TIME_PICKER, false)) {
            if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
                requireContext().getAlertDialogBuilder()
                    .setMessage(getString(R.string.stop_sleep_timer_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        onSleepTimerChanged(-1L, 0, 0, requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false))
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
            } else {
                val savedValue = requireContext().prefs().getInt(C.SLEEP_TIMER_TIME, 15)
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                    .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                    .setHour(savedValue / 60)
                    .setMinute(savedValue % 60)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    val minutes = TwitchApiHelper.getMinutesLeft(picker.hour, picker.minute)
                    onSleepTimerChanged(minutes * 60_000L, minutes / 60, minutes % 60, requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false))
                    requireContext().prefs().edit {
                        putInt(C.SLEEP_TIMER_TIME, picker.hour * 60 + picker.minute)
                    }
                }
                picker.show(childFragmentManager, null)
            }
        } else {
            SleepTimerDialog.show(childFragmentManager, (activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
        }
    }

    fun showQualityDialog() {
        if (viewModel.qualities.isNotEmpty()) {
            RadioButtonDialogFragment.newInstance(REQUEST_CODE_QUALITY, viewModel.qualities.values.map { it.first }, null, viewModel.qualities.keys.indexOf(viewModel.quality)).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showSpeedDialog() {
        player?.playbackParameters?.speed?.let {
            prefs.getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")?.let { speeds ->
                RadioButtonDialogFragment.newInstance(REQUEST_CODE_SPEED, speeds, null, speeds.indexOf(it.toString())).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    fun showVolumeDialog() {
        PlayerVolumeDialog.newInstance(player?.volume).show(childFragmentManager, "closeOnPip")
    }

    fun getTranslateAllMessages(): Boolean? {
        return if (!requireArguments().getString(KEY_CHANNEL_ID).isNullOrBlank()) {
            chatFragment?.getTranslateAllMessages()
        } else null
    }

    fun saveTranslateAllMessagesUser() {
        requireArguments().getString(KEY_CHANNEL_ID)?.let {
            chatFragment?.toggleTranslateAllMessages(true)
            viewModel.saveTranslateAllMessagesUser(it)
        }
    }

    fun deleteTranslateAllMessagesUser() {
        requireArguments().getString(KEY_CHANNEL_ID)?.let {
            chatFragment?.toggleTranslateAllMessages(false)
            viewModel.deleteTranslateAllMessagesUser(it)
        }
    }

    fun toggleChatBar() {
        with(binding) {
            requireView().findViewById<LinearLayout>(R.id.messageView)?.let {
                if (it.isVisible) {
                    chatLayout.hideKeyboard()
                    chatLayout.clearFocus()
                    if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleEmoteMenu(false)
                    }
                    it.gone()
                    prefs.edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, false) }
                } else {
                    it.visible()
                    prefs.edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, true) }
                }
            }
        }
    }

    fun hideChat() {
        with(binding) {
            if (prefs.getBoolean(C.PLAYER_CHATTOGGLE, true)) {
                requireView().findViewById<ImageButton>(R.id.playerChatToggle)?.apply {
                    visible()
                    setImageResource(R.drawable.baseline_speaker_notes_black_24)
                    setOnClickListener { showChat() }
                }
            }
            chatLayout.hideKeyboard()
            chatLayout.clearFocus()
            chatLayout.gone()
            prefs.edit { putBoolean(C.KEY_CHAT_OPENED, false) }
            slidingLayout.maximizedSecondViewVisibility = View.GONE
        }
    }

    fun showChat() {
        with(binding) {
            if (prefs.getBoolean(C.PLAYER_CHATTOGGLE, true)) {
                requireView().findViewById<ImageButton>(R.id.playerChatToggle)?.apply {
                    visible()
                    setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                    setOnClickListener { hideChat() }
                }
            }
            chatLayout.visible()
            prefs.edit { putBoolean(C.KEY_CHAT_OPENED, true) }
            slidingLayout.maximizedSecondViewVisibility = View.VISIBLE
            if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
                requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                    recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
                }
            }
        }
    }

    fun setSubtitles() {
        val subtitles = player?.currentTracks?.groups?.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        requireView().findViewById<ImageButton>(R.id.playerSubtitleToggle)?.apply {
            if (subtitles != null && prefs.getBoolean(C.PLAYER_SUBTITLES, false)) {
                visible()
                if (subtitles.isSelected) {
                    setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_on)
                    setOnClickListener {
                        toggleSubtitles(false)
                        prefs.edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                    }
                } else {
                    setImageResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_off)
                    setOnClickListener {
                        toggleSubtitles(true)
                        prefs.edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                    }
                }
            } else {
                gone()
            }
        }
        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSubtitles(subtitles)
    }

    fun toggleSubtitles(enabled: Boolean) {
        player?.let { player ->
            if (enabled) {
                player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }?.let {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                        .build()
                }
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
    }

    fun minimize() {
        binding.slidingLayout.minimize()
    }

    fun maximize() {
        binding.slidingLayout.maximize()
    }

    fun setQualityText() {
        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setQuality(
            viewModel.qualities[viewModel.quality]?.first
        )
    }

    fun updateViewerCount(viewerCount: Int?) {
        val viewers = requireView().findViewById<TextView>(R.id.playerViewersText)
        val viewerIcon = requireView().findViewById<ImageView>(R.id.playerViewersIcon)
        if (viewerCount != null) {
            viewers?.text = TwitchApiHelper.formatCount(viewerCount, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
            if (prefs.getBoolean(C.PLAYER_VIEWERICON, true)) {
                viewerIcon?.visible()
            }
        } else {
            viewers?.text = null
            viewerIcon?.gone()
        }
    }

    fun updateLiveStatus(live: Boolean, serverTime: Long?, channelLogin: String?) {
        if (channelLogin == requireArguments().getString(KEY_CHANNEL_LOGIN)) {
            if (live) {
                restartPlayer()
            }
            updateUptime(serverTime?.times(1000))
        }
    }

    private fun updateUptime(uptimeMs: Long?) {
        val layout = requireView().findViewById<LinearLayout>(R.id.playerUptime)
        val uptime = requireView().findViewById<Chronometer>(R.id.playerUptimeText)
        uptime?.stop()
        if (uptimeMs != null && prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
            layout?.visible()
            uptime?.apply {
                base = SystemClock.elapsedRealtime() + uptimeMs - System.currentTimeMillis()
                start()
            }
            requireView().findViewById<ImageView>(R.id.playerUptimeIcon)?.apply {
                if (prefs.getBoolean(C.PLAYER_VIEWERICON, true)) {
                    visible()
                } else {
                    gone()
                }
            }
        } else {
            layout?.gone()
        }
    }

    fun updateStreamInfo(title: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        requireView().findViewById<TextView>(R.id.playerTitle)?.apply {
            if (!title.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
                text = title.trim()
                visible()
            } else {
                text = null
                gone()
            }
        }
        requireView().findViewById<TextView>(R.id.playerCategory)?.apply {
            if (!gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
                text = gameName
                visible()
                setOnClickListener {
                    findNavController().navigate(
                        if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = gameId,
                                gameSlug = gameSlug,
                                gameName = gameName
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = gameId,
                                gameSlug = gameSlug,
                                gameName = gameName
                            )
                        }
                    )
                    binding.slidingLayout.minimize()
                }
            } else {
                text = null
                gone()
            }
        }
    }

    fun restartPlayer() {
        if (viewModel.quality != CHAT_ONLY_QUALITY) {
            loadStream()
        }
    }

    fun openViewerList() {
        requireArguments().getString(KEY_CHANNEL_LOGIN)?.let { login ->
            PlayerViewerListDialog.newInstance(login).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showPlaylistTags(mediaPlaylist: Boolean) {
        fun callback(tags: String?) {
            if (!tags.isNullOrBlank()) {
                requireContext().getAlertDialogBuilder().apply {
                    setView(NestedScrollView(context).apply {
                        addView(HorizontalScrollView(context).apply {
                            addView(TextView(context).apply {
                                text = tags
                                textSize = 12F
                                setTextIsSelectable(true)
                            })
                        })
                    })
                    setNegativeButton(R.string.copy_clip) { _, _ ->
                        val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("label", tags))
                    }
                    setPositiveButton(android.R.string.ok, null)
                }.show()
            }
        }
        if (player is ExoPlayer) {
            val tags = if (mediaPlaylist) {
                (player?.currentManifest as? HlsManifest)?.mediaPlaylist?.tags?.toTypedArray()
            } else {
                (player?.currentManifest as? HlsManifest)?.multivariantPlaylist?.tags?.toTypedArray()
            }?.joinToString("\n")
            callback(tags)
        } else {
            (player as? MediaController)?.sendCustomCommand(
                SessionCommand(
                    if (mediaPlaylist) {
                        PlaybackService.GET_MEDIA_PLAYLIST
                    } else {
                        PlaybackService.GET_MULTIVARIANT_PLAYLIST
                    },
                    Bundle.EMPTY
                ), Bundle.EMPTY
            )?.let { result ->
                result.addListener({
                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                        val tags = result.get().extras.getStringArray(PlaybackService.RESULT)?.joinToString("\n")
                        callback(tags)
                    }
                }, MoreExecutors.directExecutor())
            }
        }
    }

    fun showVodGames() {
        viewModel.gamesList.value?.let {
            PlayerGamesDialog.newInstance(it).show(childFragmentManager, "closeOnPip")
        }
    }

    fun checkBookmark() {
        requireArguments().getString(KEY_VIDEO_ID)?.let { viewModel.checkBookmark(it) }
    }

    fun saveBookmark() {
        viewModel.saveBookmark(
            filesDir = requireContext().filesDir.path,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            videoId = requireArguments().getString(KEY_VIDEO_ID),
            title = requireArguments().getString(KEY_TITLE),
            uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
            duration = requireArguments().getString(KEY_DURATION),
            type = requireArguments().getString(KEY_VIDEO_TYPE),
            animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
            channelId = requireArguments().getString(KEY_CHANNEL_ID),
            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
            channelName = requireArguments().getString(KEY_CHANNEL_NAME),
            channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
            thumbnail = requireArguments().getString(KEY_THUMBNAIL),
            gameId = requireArguments().getString(KEY_GAME_ID),
            gameSlug = requireArguments().getString(KEY_GAME_SLUG),
            gameName = requireArguments().getString(KEY_GAME_NAME),
        )
    }

    private fun setDefaultQuality() {
        val defaultQuality = prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved")?.substringBefore(" ")
        viewModel.quality = when (defaultQuality) {
            "saved" -> {
                val savedQuality = prefs.getString(C.PLAYER_QUALITY, "720p60")?.substringBefore(" ")
                when (savedQuality) {
                    AUTO_QUALITY -> viewModel.qualities.entries.find { it.key == AUTO_QUALITY }?.key
                    AUDIO_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == AUDIO_ONLY_QUALITY }?.key
                    CHAT_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == CHAT_ONLY_QUALITY }?.key
                    else -> findQuality(savedQuality)
                }
            }
            AUTO_QUALITY -> viewModel.qualities.entries.find { it.key == AUTO_QUALITY }?.key
            "Source" -> viewModel.qualities.entries.find { it.key != AUTO_QUALITY }?.key
            AUDIO_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == AUDIO_ONLY_QUALITY }?.key
            CHAT_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == CHAT_ONLY_QUALITY }?.key
            else -> findQuality(defaultQuality)
        } ?: viewModel.qualities.entries.firstOrNull()?.key
    }

    private fun findQuality(targetQualityString: String?): String? {
        val targetQuality = targetQualityString?.split("p")
        return targetQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
            val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
            val last = viewModel.qualities.keys.last { it != AUDIO_ONLY_QUALITY && it != CHAT_ONLY_QUALITY }
            viewModel.qualities.keys.find { qualityString ->
                val quality = qualityString.split("p")
                val resolution = quality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                val fps = quality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || qualityString == last)
            }
        }
    }

    private fun changeQuality(selectedQuality: String?) {
        viewModel.previousQuality = viewModel.quality
        viewModel.quality = selectedQuality
        viewModel.qualities.entries.find { it.key == selectedQuality }?.let { quality ->
            player?.let { player ->
                player.currentMediaItem?.let { mediaItem ->
                    when (quality.key) {
                        AUTO_QUALITY -> {
                            viewModel.playlistUrl?.let { uri ->
                                if (mediaItem.localConfiguration?.uri != uri) {
                                    val position = player.currentPosition
                                    player.setMediaItem(mediaItem.buildUpon().setUri(uri).build())
                                    player.prepare()
                                    player.seekTo(position)
                                }
                                viewModel.playlistUrl = null
                            } ?: player.prepare()
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                            }.build()
                        }
                        AUDIO_ONLY_QUALITY -> {
                            if (viewModel.usingProxy) {
                                if (player is ExoPlayer) {
                                    playbackService?.proxyMediaPlaylist = false
                                } else {
                                    (player as? MediaController)?.sendCustomCommand(
                                        SessionCommand(
                                            PlaybackService.TOGGLE_PROXY, bundleOf(
                                                PlaybackService.USING_PROXY to false
                                            )
                                        ), Bundle.EMPTY
                                    )
                                }
                                viewModel.usingProxy = false
                            }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                            }.build()
                            quality.value.second?.let {
                                val position = player.currentPosition
                                if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                    viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                }
                                player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                player.prepare()
                                player.seekTo(position)
                            }
                        }
                        CHAT_ONLY_QUALITY -> {
                            if (viewModel.usingProxy) {
                                if (player is ExoPlayer) {
                                    playbackService?.proxyMediaPlaylist = false
                                } else {
                                    (player as? MediaController)?.sendCustomCommand(
                                        SessionCommand(
                                            PlaybackService.TOGGLE_PROXY, bundleOf(
                                                PlaybackService.USING_PROXY to false
                                            )
                                        ), Bundle.EMPTY
                                    )
                                }
                                viewModel.usingProxy = false
                            }
                            player.stop()
                        }
                        else -> {
                            if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                viewModel.playlistUrl?.let { uri ->
                                    player.currentMediaItem?.let {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(uri).build())
                                        player.prepare()
                                        player.seekTo(position)
                                        viewModel.playlistUrl = null
                                    }
                                } ?: player.prepare()
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                    if (!player.currentTracks.isEmpty) {
                                        player.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let {
                                            val selectedQuality = quality.key.split("p")
                                            val targetResolution = selectedQuality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            val targetFps = selectedQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                            if (it.mediaTrackGroup.length > 0) {
                                                if (targetResolution != null) {
                                                    val formats = mutableListOf<Triple<Int, Int, Float>>()
                                                    for (i in 0 until it.mediaTrackGroup.length) {
                                                        val format = it.mediaTrackGroup.getFormat(i)
                                                        formats.add(Triple(i, format.height, format.frameRate))
                                                    }
                                                    val list = formats.sortedWith(
                                                        compareByDescending<Triple<Int, Int, Float>> { it.third }.thenByDescending { it.second }
                                                    )
                                                    list.find {
                                                        (targetResolution == it.second && targetFps >= floor(it.third)) || targetResolution > it.second || it == list.last()
                                                    }?.first?.let { index ->
                                                        setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, index))
                                                    }
                                                } else {
                                                    setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
                                                }
                                            }
                                        }
                                    }
                                }.build()
                            } else {
                                player.currentMediaItem?.let {
                                    if (it.localConfiguration?.uri?.toString() != quality.value.second) {
                                        val position = player.currentPosition
                                        player.setMediaItem(it.buildUpon().setUri(quality.value.second).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                }.build()
                            }
                        }
                    }
                    if (prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") {
                        prefs.edit { putString(C.PLAYER_QUALITY, quality.key) }
                    }
                }
            }
        }
    }

    private fun changePlayerMode() {
        with(binding) {
            if (enterPictureInPicture()) {
                playerView.controllerHideOnTouch = true
                playerView.controllerShowTimeoutMs = 3000
                if (requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
                }
            } else {
                playerView.controllerHideOnTouch = false
                playerView.controllerShowTimeoutMs = -1
                playerView.showController()
                requireView().keepScreenOn = true
                if (requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
                }
            }
        }
    }

    private fun showStatusBar() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).hide(WindowInsetsCompat.Type.systemBars())
    }

    fun getCurrentSpeed() = player?.playbackParameters?.speed

    fun getCurrentPosition() = player?.currentPosition

    fun getIsPortrait() = isPortrait

    fun reloadEmotes() = chatFragment?.reloadEmotes()

    fun isActive() = chatFragment?.isActive()

    fun disconnect() = chatFragment?.disconnect()

    fun reconnect() = chatFragment?.reconnect()

    fun secondViewIsHidden() = binding.slidingLayout.secondView?.isVisible == false

    fun enterPictureInPicture(): Boolean {
        val quality = if (viewModel.restoreQuality) {
            viewModel.previousQuality
        } else {
            viewModel.quality
        }
        return quality != AUDIO_ONLY_QUALITY && quality != CHAT_ONLY_QUALITY
    }

    override fun onStart() {
        super.onStart()
        fun callback(newPlayer: Player?) {
            binding.playerView.player = newPlayer
            newPlayer?.addListener(object : Player.Listener {

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        chatFragment?.updatePosition(newPosition.positionMs)
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    chatFragment?.updateSpeed(playbackParameters.speed)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && enterPictureInPicture()) {
                        requireView().keepScreenOn = isPlaying
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (!tracks.isEmpty && !viewModel.loaded.value) {
                        viewModel.loaded.value = true
                        toggleSubtitles(prefs.getBoolean(C.PLAYER_SUBTITLES_ENABLED, false))
                    }
                    setSubtitles()
                    if (!tracks.isEmpty) {
                        if (viewModel.qualities.containsKey(AUTO_QUALITY)
                            && viewModel.quality != AUDIO_ONLY_QUALITY
                            && !viewModel.hidden) {
                            changeQuality(viewModel.quality)
                        }
                        chatFragment?.startReplayChatLoad()
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && viewModel.qualities.containsKey(AUTO_QUALITY)) {
                        viewModel.updateQualities = viewModel.quality != AUDIO_ONLY_QUALITY
                    }
                    if (viewModel.qualities.isEmpty() || viewModel.updateQualities) {
                        fun callback(names: Array<String>?, codecs: Array<String?>?, urls: Array<String>?) {
                            val codecs = codecs?.map { codec ->
                                codec?.substringBefore('.').let {
                                    when (it) {
                                        "av01" -> "AV1"
                                        "hev1" -> "H.265"
                                        "avc1" -> "H.264"
                                        else -> it
                                    }
                                }
                            }?.takeUnless { it.all { it == "H.264" || it == "mp4a" } }
                            if (!names.isNullOrEmpty() && !urls.isNullOrEmpty()) {
                                val map = mutableMapOf<String, Pair<String, String?>>()
                                map[AUTO_QUALITY] = Pair(requireContext().getString(R.string.auto), null)
                                names.forEachIndexed { index, quality ->
                                    urls.getOrNull(index)?.let { url ->
                                        when {
                                            quality.equals("source", true) -> {
                                                val quality = requireContext().getString(R.string.source)
                                                map["source"] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                            }
                                            quality.startsWith("audio", true) -> {
                                                map[AUDIO_ONLY_QUALITY] = Pair(requireContext().getString(R.string.audio_only), url)
                                            }
                                            else -> {
                                                map[quality] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                            }
                                        }
                                    }
                                }
                                if (!map.containsKey(AUDIO_ONLY_QUALITY)) {
                                    map[AUDIO_ONLY_QUALITY] = Pair(requireContext().getString(R.string.audio_only), null)
                                }
                                if (videoType == STREAM) {
                                    map[CHAT_ONLY_QUALITY] = Pair(requireContext().getString(R.string.chat_only), null)
                                }
                                viewModel.qualities = map.toList()
                                    .sortedByDescending {
                                        it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                    }
                                    .sortedByDescending {
                                        it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                    }
                                    .sortedByDescending {
                                        it.first == "source"
                                    }
                                    .sortedByDescending {
                                        it.first == "auto"
                                    }
                                    .toMap()
                                setDefaultQuality()
                                if (viewModel.quality == AUDIO_ONLY_QUALITY) {
                                    changeQuality(viewModel.quality)
                                }
                            }
                            if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                                viewModel.updateQualities = false
                            }
                        }
                        if (player is ExoPlayer) {
                            val playlist = (player?.currentManifest as? HlsManifest)?.multivariantPlaylist
                            val variants = playlist?.variants?.mapNotNull { variant ->
                                playlist.videos.find { it.groupId == variant.videoGroupId }?.name?.let { variant to it }
                            }
                            val names = variants?.map { it.second }?.toTypedArray()
                            val codecs = variants?.map { it.first.format.codecs }?.toTypedArray()
                            val urls = variants?.map { it.first.url.toString() }?.toTypedArray()
                            callback(names, codecs, urls)
                        } else {
                            (player as? MediaController)?.sendCustomCommand(
                                SessionCommand(PlaybackService.GET_QUALITIES, Bundle.EMPTY),
                                Bundle.EMPTY
                            )?.let { result ->
                                result.addListener({
                                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                        val names = result.get().extras.getStringArray(PlaybackService.NAMES)
                                        val codecs = result.get().extras.getStringArray(PlaybackService.CODECS)
                                        val urls = result.get().extras.getStringArray(PlaybackService.URLS)
                                        callback(names, codecs, urls)
                                    }
                                }, MoreExecutors.directExecutor())
                            }
                        }
                    }
                    if (videoType == STREAM) {
                        val hideAds = prefs.getBoolean(C.PLAYER_HIDE_ADS, false)
                        val useProxy = prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true)
                                && !prefs.getString(C.PROXY_HOST, null).isNullOrBlank()
                                && prefs.getString(C.PROXY_PORT, null)?.toIntOrNull() != null
                        if (hideAds || useProxy) {
                            fun callback(playingAds: Boolean) {
                                val oldValue = viewModel.playingAds
                                viewModel.playingAds = playingAds
                                if (playingAds) {
                                    if (viewModel.usingProxy) {
                                        if (!viewModel.stopProxy) {
                                            if (player is ExoPlayer) {
                                                playbackService?.proxyMediaPlaylist = false
                                            } else {
                                                (player as? MediaController)?.sendCustomCommand(
                                                    SessionCommand(
                                                        PlaybackService.TOGGLE_PROXY, bundleOf(
                                                            PlaybackService.USING_PROXY to false
                                                        )
                                                    ), Bundle.EMPTY
                                                )
                                            }
                                            viewModel.usingProxy = false
                                            viewModel.stopProxy = true
                                        }
                                    } else {
                                        if (!oldValue) {
                                            val playlist = viewModel.qualities[viewModel.quality]?.second
                                            if (!viewModel.stopProxy && !playlist.isNullOrBlank() && useProxy) {
                                                if (player is ExoPlayer) {
                                                    playbackService?.proxyMediaPlaylist = false
                                                } else {
                                                    (player as? MediaController)?.sendCustomCommand(
                                                        SessionCommand(
                                                            PlaybackService.TOGGLE_PROXY, bundleOf(
                                                                PlaybackService.USING_PROXY to false
                                                            )
                                                        ), Bundle.EMPTY
                                                    )
                                                }
                                                viewModel.usingProxy = true
                                                viewLifecycleOwner.lifecycleScope.launch {
                                                    for (i in 0 until 10) {
                                                        delay(10000)
                                                        if (!viewModel.checkPlaylist(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), playlist)) {
                                                            break
                                                        }
                                                    }
                                                    if (player is ExoPlayer) {
                                                        playbackService?.proxyMediaPlaylist = false
                                                    } else {
                                                        (player as? MediaController)?.sendCustomCommand(
                                                            SessionCommand(
                                                                PlaybackService.TOGGLE_PROXY, bundleOf(
                                                                    PlaybackService.USING_PROXY to false
                                                                )
                                                            ), Bundle.EMPTY
                                                        )
                                                    }
                                                    viewModel.usingProxy = false
                                                }
                                            } else {
                                                if (hideAds) {
                                                    viewModel.hidden = true
                                                    player?.let { player ->
                                                        if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                                setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                                            }.build()
                                                        }
                                                        player.volume = 0f
                                                    }
                                                    requireContext().toast(R.string.waiting_ads)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    if (hideAds && viewModel.hidden) {
                                        viewModel.hidden = false
                                        player?.let { player ->
                                            if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                                                }.build()
                                            }
                                            player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                        }
                                    }
                                }
                            }
                            if (player is ExoPlayer) {
                                val playlist = (player?.currentManifest as? HlsManifest)?.mediaPlaylist
                                val adSegment = playlist?.segments?.lastOrNull()?.let { segment ->
                                    val segmentStartTime = playlist.startTimeUs + segment.relativeStartTimeUs
                                    listOf("Amazon", "Adform", "DCM").any { segment.title.contains(it) } ||
                                            playlist.interstitials.find {
                                                val startTime = it.startDateUnixUs
                                                val endTime = it.endDateUnixUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                                                    ?: it.durationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                                                    ?: it.plannedDurationUs.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { startTime + it }
                                                endTime != null && (it.id.startsWith("stitched-ad-") ||
                                                        it.clientDefinedAttributes.find { it.name == "CLASS" }?.textValue == "twitch-stitched-ad" ||
                                                        it.clientDefinedAttributes.find { it.name.startsWith("X-TV-TWITCH-AD-") } != null)
                                                        && (startTime <= segmentStartTime && segmentStartTime < endTime)
                                            } != null
                                }
                                callback(adSegment == true)
                            } else {
                                (player as? MediaController)?.sendCustomCommand(
                                    SessionCommand(PlaybackService.CHECK_ADS, Bundle.EMPTY),
                                    Bundle.EMPTY
                                )?.let { result ->
                                    result.addListener({
                                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                            val playingAds = result.get().extras.getBoolean(PlaybackService.RESULT)
                                            callback(playingAds)
                                        }
                                    }, MoreExecutors.directExecutor())
                                }
                            }
                        }
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                        if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                            requireView().findViewById<ImageButton>(androidx.media3.ui.R.id.exo_play_pause)?.apply {
                                if (player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady) {
                                    gone()
                                } else {
                                    visible()
                                }
                            }
                        }
                        setPipActions(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(tag, "Player error", error)
                    when (videoType) {
                        STREAM -> {
                            fun callback(responseCode: Int) {
                                if (requireContext().isNetworkAvailable) {
                                    when {
                                        responseCode == 404 -> {
                                            requireContext().toast(R.string.stream_ended)
                                        }
                                        viewModel.useCustomProxy && responseCode >= 400 -> {
                                            requireContext().toast(R.string.proxy_error)
                                            viewModel.useCustomProxy = false
                                            viewLifecycleOwner.lifecycleScope.launch {
                                                delay(1500L)
                                                try {
                                                    restartPlayer()
                                                } catch (e: Exception) {
                                                }
                                            }
                                        }
                                        else -> {
                                            requireContext().shortToast(R.string.player_error)
                                            viewLifecycleOwner.lifecycleScope.launch {
                                                delay(1500L)
                                                try {
                                                    restartPlayer()
                                                } catch (e: Exception) {
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (player is ExoPlayer) {
                                val responseCode = (player?.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                                callback(responseCode ?: 0)
                            } else {
                                (player as? MediaController)?.sendCustomCommand(
                                    SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                    Bundle.EMPTY
                                )?.let { result ->
                                    result.addListener({
                                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                            val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                                            callback(responseCode)
                                        }
                                    }, MoreExecutors.directExecutor())
                                }
                            }
                        }
                        VIDEO -> {
                            fun callback(responseCode: Int) {
                                if (requireContext().isNetworkAvailable) {
                                    val skipAccessToken = prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                                    when {
                                        skipAccessToken == 1 && viewModel.shouldRetry && responseCode != 0 -> {
                                            viewModel.shouldRetry = false
                                            playVideo(false, player?.currentPosition)
                                        }
                                        skipAccessToken == 2 && viewModel.shouldRetry && responseCode != 0 -> {
                                            viewModel.shouldRetry = false
                                            playVideo(true, player?.currentPosition)
                                        }
                                        responseCode == 403 -> {
                                            requireContext().toast(R.string.video_subscribers_only)
                                        }
                                        else -> {
                                            requireContext().shortToast(R.string.player_error)
                                            viewLifecycleOwner.lifecycleScope.launch {
                                                delay(1500L)
                                                try {
                                                    player?.prepare()
                                                } catch (e: Exception) {
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (player is ExoPlayer) {
                                val responseCode = (player?.playerError?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                                callback(responseCode ?: 0)
                            } else {
                                (player as? MediaController)?.sendCustomCommand(
                                    SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY),
                                    Bundle.EMPTY
                                )?.let { result ->
                                    result.addListener({
                                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                            val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                                            callback(responseCode)
                                        }
                                    }, MoreExecutors.directExecutor())
                                }
                            }
                        }
                    }
                }
            })
            if (viewModel.restoreQuality) {
                viewModel.restoreQuality = false
                changeQuality(viewModel.previousQuality)
            }
            if (player is ExoPlayer) {
                val endTime = playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                if (endTime != null && endTime > 0L) {
                    val duration = endTime - System.currentTimeMillis()
                    if (duration > 0L) {
                        (activity as? MainActivity)?.setSleepTimer(duration)
                    } else {
                        minimize()
                        onClose()
                        (activity as? MainActivity)?.closePlayer()
                    }
                }
            } else {
                (player as? MediaController)?.sendCustomCommand(
                    SessionCommand(
                        PlaybackService.SET_SLEEP_TIMER, bundleOf(
                            PlaybackService.DURATION to -1L
                        )
                    ), Bundle.EMPTY
                )?.let { result ->
                    result.addListener({
                        if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                            val endTime = result.get().extras.getLong(PlaybackService.RESULT)
                            if (endTime > 0L) {
                                val duration = endTime - System.currentTimeMillis()
                                if (duration > 0L) {
                                    (activity as? MainActivity)?.setSleepTimer(duration)
                                } else {
                                    minimize()
                                    onClose()
                                    (activity as? MainActivity)?.closePlayer()
                                }
                            }
                        }
                    }, MoreExecutors.directExecutor())
                }
            }
            if (viewModel.resume) {
                viewModel.resume = false
                player?.playWhenReady = true
                player?.prepare()
            }
            player?.let { player ->
                if (viewModel.loaded.value && player.currentMediaItem == null) {
                    viewModel.started = false
                }
                if (viewModel.started && player.currentMediaItem != null) {
                    chatFragment?.startReplayChatLoad()
                }
                if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && enterPictureInPicture()) {
                    requireView().keepScreenOn = player.isPlaying
                }
            }
            if ((isInitialized || !enableNetworkCheck) && !viewModel.started) {
                startPlayer()
            }
            player?.let { player ->
                setPipActions(player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE && player.playWhenReady)
            }
        }
        if (prefs.getBoolean(C.DEBUG_USE_CUSTOM_PLAYBACK_SERVICE, false)) {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as CustomPlaybackService.ServiceBinder
                    playbackService = binder.getService()
                    callback(playbackService?.player)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    playbackService = null
                }
            }
            serviceConnection = connection
            requireContext().bindService(Intent(requireContext(), CustomPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        } else {
            controllerFuture = MediaController.Builder(
                requireContext(),
                SessionToken(
                    requireContext(),
                    ComponentName(requireContext(), PlaybackService::class.java)
                )
            ).buildAsync()
            controllerFuture?.addListener({
                val controller = controllerFuture?.get()
                callback(controller)
            }, MoreExecutors.directExecutor())
        }
    }

    private fun setPipActions(playing: Boolean) {
        if (requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            requireActivity().setPictureInPictureParams(
                PictureInPictureParams.Builder().apply {
                    setActions(listOf(
                        RemoteAction(
                            Icon.createWithResource(requireContext(), R.drawable.baseline_audiotrack_black_24),
                            requireContext().getString(R.string.audio_only),
                            requireContext().getString(R.string.audio_only),
                            PendingIntent.getBroadcast(
                                requireContext(),
                                REQUEST_CODE_AUDIO_ONLY,
                                Intent(MainActivity.INTENT_START_AUDIO_ONLY).setPackage(requireContext().packageName),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ),
                        if (playing) {
                            RemoteAction(
                                Icon.createWithResource(requireContext(), R.drawable.baseline_pause_black_48),
                                requireContext().getString(R.string.pause),
                                requireContext().getString(R.string.pause),
                                PendingIntent.getBroadcast(
                                    requireContext(),
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        } else {
                            RemoteAction(
                                Icon.createWithResource(requireContext(), R.drawable.baseline_play_arrow_black_48),
                                requireContext().getString(R.string.resume),
                                requireContext().getString(R.string.resume),
                                PendingIntent.getBroadcast(
                                    requireContext(),
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        }
                    ))
                }.build()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireActivity().isInPictureInPictureMode) {
            binding.playerView.useController = false
        }
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        if (requireArguments().getString(KEY_TYPE) != OFFLINE_VIDEO) {
            viewModel.isFollowingChannel(
                requireContext().tokenPrefs().getString(C.USER_ID, null),
                requireArguments().getString(KEY_CHANNEL_ID),
                requireArguments().getString(KEY_CHANNEL_LOGIN),
                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
            if (videoType == VIDEO) {
                val videoId = requireArguments().getString(KEY_VIDEO_ID)
                if (!videoId.isNullOrBlank() && (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false))) {
                    viewModel.loadGamesList(
                        videoId,
                        prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                        TwitchApiHelper.getGQLHeaders(requireContext()),
                        prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
            }
        }
    }

    private fun startPlayer() {
        viewModel.started = true
        when (videoType) {
            STREAM -> {
                viewModel.useCustomProxy = prefs.getBoolean(C.PLAYER_STREAM_PROXY, false)
                loadStream()
                viewModel.loadStream(
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    viewerCount = requireArguments().getInt(KEY_VIEWER_COUNT).takeIf { it != -1 },
                    loop = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) ||
                            !requireContext().prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                            (requireContext().prefs().getBoolean(C.CHAT_POINTS_COLLECT, true) &&
                                    !requireContext().tokenPrefs().getString(C.USER_ID, null).isNullOrBlank() &&
                                    !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()),
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    val id = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                    if (id != null) {
                        viewModel.getVideoPosition(id)
                    } else {
                        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, 0)
                    }
                } else {
                    if (requireArguments().getBoolean(KEY_IGNORE_SAVED_POSITION)) {
                        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, requireArguments().getLong(KEY_OFFSET).takeIf { it != -1L } ?: 0)
                        requireArguments().putBoolean(KEY_IGNORE_SAVED_POSITION, false)
                        requireArguments().putLong(KEY_OFFSET, -1)
                    } else {
                        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, 0)
                    }
                }
            }
            CLIP -> {
                viewModel.loadClip(
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    id = requireArguments().getString(KEY_CLIP_ID),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            OFFLINE_VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    viewModel.getOfflineVideoPosition(requireArguments().getInt(KEY_OFFLINE_VIDEO_ID))
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.savedOfflineVideoPosition.value = 0
                    }
                }
            }
        }
    }

    private fun loadStream() {
        requireArguments().getString(KEY_CHANNEL_LOGIN)?.let { channelLogin ->
            val proxyUrl = prefs.getString(C.PLAYER_PROXY_URL, "")
            if (viewModel.useCustomProxy && !proxyUrl.isNullOrBlank()) {
                if (player is ExoPlayer) {
                    (player as? ExoPlayer)?.let { player ->
                        playbackService?.videoId = null
                        playbackService?.offlineVideoId = null
                        playbackService?.proxyMediaPlaylist = false
                        player.setMediaSource(
                            HlsMediaSource.Factory(
                                DefaultDataSource.Factory(
                                    requireContext(),
                                    viewModel.getDataSourceFactory(
                                        networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                        proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                                        proxyMediaPlaylist = prefs.getBoolean(C.PROXY_MEDIA_PLAYLIST, true),
                                        proxyHost = prefs.getString(C.PROXY_HOST, null),
                                        proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                                        proxyUser = prefs.getString(C.PROXY_USER, null),
                                        proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                                        useProxy = { playbackService?.proxyMediaPlaylist == true }
                                    ).apply {
                                        prefs.getString(C.PLAYER_STREAM_HEADERS, null)?.let {
                                            try {
                                                val json = JSONObject(it)
                                                hashMapOf<String, String>().apply {
                                                    json.keys().forEach { key ->
                                                        put(key, json.optString(key))
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                null
                                            }
                                        }?.let {
                                            setDefaultRequestProperties(it)
                                        }
                                    }
                                )
                            ).apply {
                                setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                                setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                            }.createMediaSource(
                                MediaItem.Builder().apply {
                                    setUri(proxyUrl.replace("\$channel", channelLogin).toUri())
                                    setMimeType(MimeTypes.APPLICATION_M3U8)
                                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                                        prefs.getString(C.PLAYER_LIVE_MIN_SPEED, "")?.toFloatOrNull()?.let { setMinPlaybackSpeed(it) }
                                        prefs.getString(C.PLAYER_LIVE_MAX_SPEED, "")?.toFloatOrNull()?.let { setMaxPlaybackSpeed(it) }
                                        prefs.getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                                    }.build())
                                    setMediaMetadata(
                                        MediaMetadata.Builder().apply {
                                            setTitle(requireArguments().getString(KEY_TITLE))
                                            setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                            setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                                        }.build()
                                    )
                                }.build()
                            )
                        )
                        player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                        player.setPlaybackSpeed(1f)
                        player.prepare()
                        player.playWhenReady = true
                    }
                } else {
                    (player as? MediaController)?.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.START_STREAM, bundleOf(
                                PlaybackService.URI to proxyUrl.replace("\$channel", channelLogin),
                                PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                                PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                                PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                            )
                        ), Bundle.EMPTY
                    )
                }
            } else {
                if (viewModel.useCustomProxy) {
                    viewModel.useCustomProxy = false
                }
                viewModel.loadStreamResult(
                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = channelLogin,
                    randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                    xDeviceId = prefs.getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                    playerType = prefs.getString(C.TOKEN_PLAYERTYPE, "site"),
                    supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                    proxyHost = prefs.getString(C.PROXY_HOST, null),
                    proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                    proxyUser = prefs.getString(C.PROXY_USER, null),
                    proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                    enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
                )
            }
        }
    }

    private fun playVideo(skipAccessToken: Boolean, playbackPosition: Long?) {
        if (skipAccessToken && !requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW).isNullOrBlank()) {
            requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW)?.let { preview ->
                val qualityMap = TwitchApiHelper.getVideoUrlMapFromPreview(preview, requireArguments().getString(KEY_VIDEO_TYPE), viewModel.backupQualities)
                val map = mutableMapOf<String, Pair<String, String?>>()
                qualityMap.forEach {
                    when (it.key) {
                        "source" -> map[it.key] = Pair(requireContext().getString(R.string.source), it.value)
                        "audio_only" -> map[it.key] = Pair(requireContext().getString(R.string.audio_only), it.value)
                        else -> map[it.key] = Pair(it.key, it.value)
                    }
                }
                map.put(AUDIO_ONLY_QUALITY, map.remove(AUDIO_ONLY_QUALITY) //move audio option to bottom
                    ?: Pair(requireContext().getString(R.string.audio_only), null))
                val qualities = map.toList()
                    .sortedByDescending {
                        it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .sortedByDescending {
                        it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .sortedByDescending {
                        it.first == "source"
                    }
                    .toMap()
                viewModel.qualities = qualities
                viewModel.quality = qualities.keys.firstOrNull()
                qualities.values.firstOrNull()?.second
            }?.let { url ->
                player?.let { player ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                    }.build()
                    if (player is ExoPlayer) {
                        val newId = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                        val position = if (playbackService?.videoId == newId && player.currentMediaItem != null) {
                            player.currentPosition
                        } else {
                            playbackPosition ?: 0
                        }
                        playbackService?.videoId = newId
                        playbackService?.offlineVideoId = null
                        player.setMediaSource(
                            HlsMediaSource.Factory(
                                DefaultDataSource.Factory(
                                    requireContext(),
                                    viewModel.getDataSourceFactory(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"))
                                )
                            ).apply {
                                setPlaylistParserFactory(CustomHlsPlaylistParserFactory())
                            }.createMediaSource(
                                MediaItem.Builder().apply {
                                    setUri(url.toUri())
                                    setMediaMetadata(
                                        MediaMetadata.Builder().apply {
                                            setTitle(requireArguments().getString(KEY_TITLE))
                                            setArtist(requireArguments().getString(KEY_CHANNEL_NAME))
                                            setArtworkUri(requireArguments().getString(KEY_CHANNEL_LOGO)?.toUri())
                                        }.build()
                                    )
                                }.build()
                            )
                        )
                        player.volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                        player.setPlaybackSpeed(prefs.getFloat(C.PLAYER_SPEED, 1f))
                        player.prepare()
                        player.playWhenReady = true
                        player.seekTo(position)
                    } else {
                        (player as? MediaController)?.sendCustomCommand(
                            SessionCommand(
                                PlaybackService.START_VIDEO, bundleOf(
                                    PlaybackService.URI to url,
                                    PlaybackService.PLAYBACK_POSITION to playbackPosition,
                                    PlaybackService.VIDEO_ID to requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull(),
                                    PlaybackService.TITLE to requireArguments().getString(KEY_TITLE),
                                    PlaybackService.CHANNEL_NAME to requireArguments().getString(KEY_CHANNEL_NAME),
                                    PlaybackService.CHANNEL_LOGO to requireArguments().getString(KEY_CHANNEL_LOGO),
                                )
                            ), Bundle.EMPTY
                        )
                    }
                }
            }
        } else {
            viewModel.playbackPosition = playbackPosition
            viewModel.loadVideo(
                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                videoId = requireArguments().getString(KEY_VIDEO_ID),
                playerType = prefs.getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        with(binding) {
            isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
            slidingLayout.updateBackgroundColor(isPortrait)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().isInPictureInPictureMode) {
                chatLayout.hideKeyboard()
                chatLayout.clearFocus()
                initLayout()
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.dismiss()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        with(binding) {
            if (isInPictureInPictureMode) {
                if (!slidingLayout.isMaximized) {
                    slidingLayout.maximize()
                }
                playerView.useController = false
                chatLayout.gone()
                // player dialog
                (childFragmentManager.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment)?.dismiss()
                // player chat message dialog
                (chatFragment?.childFragmentManager?.findFragmentByTag("messageDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("replyDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("imageDialog") as? BottomSheetDialogFragment)?.dismiss()
            } else {
                playerView.useController = true
            }
        }
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (videoType == STREAM) {
                restartPlayer()
            } else {
                player?.prepare()
            }
        }
    }

    override fun onNetworkLost() {
        if (videoType != STREAM && isResumed) {
            player?.stop()
        }
    }

    fun handlePlayPauseAction() {
        Util.handlePlayPauseButtonAction(player)
    }

    fun startAudioOnly() {
        player?.let { player ->
            if (playbackService != null || (player as? MediaController)?.isConnected == true) {
                savePosition()
                if (viewModel.usingProxy) {
                    if (player is ExoPlayer) {
                        playbackService?.proxyMediaPlaylist = false
                    } else {
                        (player as? MediaController)?.sendCustomCommand(
                            SessionCommand(
                                PlaybackService.TOGGLE_PROXY, bundleOf(
                                    PlaybackService.USING_PROXY to false
                                )
                            ), Bundle.EMPTY
                        )
                    }
                    viewModel.usingProxy = false
                }
                if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                    viewModel.restoreQuality = true
                    viewModel.previousQuality = viewModel.quality
                    viewModel.quality = AUDIO_ONLY_QUALITY
                    viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                        player.currentMediaItem?.let { mediaItem ->
                            if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                    setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                }.build()
                            }
                            if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                quality.value.second?.let {
                                    val position = player.currentPosition
                                    if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                        viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                    }
                                    player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                    player.prepare()
                                    player.seekTo(position)
                                }
                            }
                        }
                    }
                }
                if (player is ExoPlayer) {
                    playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                } else {
                    (player as? MediaController)?.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.SET_SLEEP_TIMER, bundleOf(
                                PlaybackService.DURATION to ((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                            )
                        ), Bundle.EMPTY
                    )
                }
            }
        }
        releaseController()
    }

    override fun onStop() {
        super.onStop()
        player?.let { player ->
            if (playbackService != null || (player as? MediaController)?.isConnected == true) {
                savePosition()
                if (viewModel.usingProxy) {
                    if (player is ExoPlayer) {
                        playbackService?.proxyMediaPlaylist = false
                    } else {
                        (player as? MediaController)?.sendCustomCommand(
                            SessionCommand(
                                PlaybackService.TOGGLE_PROXY, bundleOf(
                                    PlaybackService.USING_PROXY to false
                                )
                            ), Bundle.EMPTY
                        )
                    }
                    viewModel.usingProxy = false
                }
                if (prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO, true)) {
                    if (player.playWhenReady && viewModel.quality != AUDIO_ONLY_QUALITY) {
                        viewModel.restoreQuality = true
                        viewModel.previousQuality = viewModel.quality
                        viewModel.quality = AUDIO_ONLY_QUALITY
                        viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                            player.currentMediaItem?.let { mediaItem ->
                                if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                                        setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                                    }.build()
                                }
                                if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                    quality.value.second?.let {
                                        val position = player.currentPosition
                                        if (viewModel.qualities.containsKey(AUTO_QUALITY)) {
                                            viewModel.playlistUrl = mediaItem.localConfiguration?.uri
                                        }
                                        player.setMediaItem(mediaItem.buildUpon().setUri(it).build())
                                        player.prepare()
                                        player.seekTo(position)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    viewModel.resume = player.playWhenReady
                    player.pause()
                }
                if (player is ExoPlayer) {
                    playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                } else {
                    (player as? MediaController)?.sendCustomCommand(
                        SessionCommand(
                            PlaybackService.SET_SLEEP_TIMER, bundleOf(
                                PlaybackService.DURATION to ((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                            )
                        ), Bundle.EMPTY
                    )
                }
            }
        }
        releaseController()
    }

    private fun savePosition() {
        when (videoType) {
            VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()?.let { id ->
                        player?.currentPosition?.let { position ->
                            viewModel.saveVideoPosition(id, position)
                        }
                    }
                }
            }
            OFFLINE_VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    player?.currentPosition?.let { position ->
                        viewModel.saveOfflineVideoPosition(requireArguments().getInt(KEY_OFFLINE_VIDEO_ID), position)
                    }
                }
            }
        }
    }

    private fun releaseController() {
        _binding?.playerView?.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    override fun onMinimize() {
        with(binding) {
            chatLayout.hideKeyboard()
            chatLayout.clearFocus()
            if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(false)
            }
            backPressedCallback.remove()
            playerView.useController = false
            if (!isPortrait) {
                showStatusBar()
                val activity = requireActivity()
                activity.lifecycleScope.launch {
                    delay(500L)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    override fun onMaximize() {
        with(binding) {
            requireActivity().onBackPressedDispatcher.addCallback(this@PlayerFragment, backPressedCallback)
            if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(true)
            }
            playerView.useController = true
            if (!playerView.controllerHideOnTouch) { //TODO
                playerView.showController()
            }
            if (!isPortrait) {
                hideStatusBar()
            }
        }
    }

    override fun onClose() {
        savePosition()
        player?.pause()
        player?.stop()
        player?.removeMediaItem(0)
        releaseController()
        serviceConnection?.let { requireContext().unbindService(it) }
        playbackService?.stopSelf()
        playbackService = null
    }

    fun showDownloadDialog() {
        if (viewModel.loaded.value) {
            when (videoType) {
                STREAM -> {
                    val qualities = viewModel.qualities.filter { !it.value.second.isNullOrBlank() }
                    DownloadDialog.newInstance(
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
                        qualityKeys = qualities.keys.toTypedArray(),
                        qualityNames = qualities.map { it.value.first }.toTypedArray(),
                        qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
                VIDEO -> {
                    fun callback(totalDuration: Long?) {
                        val qualities = viewModel.qualities.filter { !it.value.second.isNullOrBlank() }
                        DownloadDialog.newInstance(
                            id = requireArguments().getString(KEY_VIDEO_ID),
                            title = requireArguments().getString(KEY_TITLE),
                            uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
                            duration = requireArguments().getString(KEY_DURATION),
                            videoType = requireArguments().getString(KEY_VIDEO_TYPE),
                            animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
                            channelId = requireArguments().getString(KEY_CHANNEL_ID),
                            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                            channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                            channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
                            thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                            gameId = requireArguments().getString(KEY_GAME_ID),
                            gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                            gameName = requireArguments().getString(KEY_GAME_NAME),
                            totalDuration = totalDuration,
                            currentPosition = player?.currentPosition,
                            qualityKeys = qualities.keys.toTypedArray(),
                            qualityNames = qualities.map { it.value.first }.toTypedArray(),
                            qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
                        ).show(childFragmentManager, null)
                    }
                    if (player is ExoPlayer) {
                        val totalDuration = (player?.currentManifest as? HlsManifest)?.mediaPlaylist?.durationUs?.div(1000)
                        callback(totalDuration)
                    } else {
                        (player as? MediaController)?.sendCustomCommand(
                            SessionCommand(PlaybackService.GET_DURATION, Bundle.EMPTY),
                            Bundle.EMPTY
                        )?.let { result ->
                            result.addListener({
                                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                                    val totalDuration = result.get().extras.getLong(PlaybackService.RESULT)
                                    callback(totalDuration)
                                }
                            }, MoreExecutors.directExecutor())
                        }
                    }
                }
                CLIP -> {
                    val qualities = viewModel.qualities.filter { !it.value.second.isNullOrBlank() }
                    DownloadDialog.newInstance(
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
                        qualityKeys = qualities.keys.toTypedArray(),
                        qualityNames = qualities.map { it.value.first }.toTypedArray(),
                        qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
            }
        }
    }

    override fun seek(position: Long) {
        player?.seekTo(position)
    }

    override fun onSleepTimerChanged(durationMs: Long, hours: Int, minutes: Int, lockScreen: Boolean) {
        if (durationMs > 0L) {
            requireContext().toast(
                when {
                    hours == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                    minutes == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.hours, hours, hours)
                    )
                    else -> getString(
                        R.string.playback_will_stop_hours_minutes,
                        resources.getQuantityString(R.plurals.hours, hours, hours),
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                }
            )
        } else if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
            requireContext().toast(R.string.timer_canceled)
        }
        if (lockScreen != prefs.getBoolean(C.SLEEP_TIMER_LOCK, false)) {
            prefs.edit { putBoolean(C.SLEEP_TIMER_LOCK, lockScreen) }
        }
        (activity as? MainActivity)?.setSleepTimer(durationMs)
    }

    override fun onChange(requestCode: Int, index: Int, text: CharSequence, tag: Int?) {
        when (requestCode) {
            REQUEST_CODE_QUALITY -> {
                changeQuality(viewModel.qualities.keys.elementAtOrNull(index))
                changePlayerMode()
                setQualityText()
            }
            REQUEST_CODE_SPEED -> {
                prefs.getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")?.let { speeds ->
                    speeds.getOrNull(index)?.toFloatOrNull()?.let { speed ->
                        player?.setPlaybackSpeed(speed)
                        prefs.edit { putFloat(C.PLAYER_SPEED, speed) }
                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSpeed(
                            speeds.find { it == player?.playbackParameters?.speed.toString() }
                        )
                    }
                }
            }
        }
    }

    override fun changeVolume(volume: Float) {
        player?.volume = volume
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    when (callback) {
                        "refreshStream" -> {
                            requireArguments().getString(KEY_CHANNEL_LOGIN)?.let { channelLogin ->
                                viewModel.loadStreamResult(
                                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                                    channelLogin = channelLogin,
                                    randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                                    xDeviceId = prefs.getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                                    playerType = prefs.getString(C.TOKEN_PLAYERTYPE, "site"),
                                    supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                                    proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                                    proxyHost = prefs.getString(C.PROXY_HOST, null),
                                    proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                                    proxyUser = prefs.getString(C.PROXY_USER, null),
                                    proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                                    enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
                                )
                            }
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                requireArguments().getString(KEY_CHANNEL_ID),
                                requireArguments().getString(KEY_CHANNEL_LOGIN),
                                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                        }
                        "refreshVideo" -> {
                            val videoId = requireArguments().getString(KEY_VIDEO_ID)
                            viewModel.loadVideo(
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                                videoId = videoId,
                                playerType = prefs.getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                                supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                requireArguments().getString(KEY_CHANNEL_ID),
                                requireArguments().getString(KEY_CHANNEL_LOGIN),
                                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                            if (!videoId.isNullOrBlank() && (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false))) {
                                viewModel.loadGamesList(
                                    videoId,
                                    prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    TwitchApiHelper.getGQLHeaders(requireContext()),
                                    prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        "refreshClip" -> {
                            viewModel.loadClip(
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                id = requireArguments().getString(KEY_CLIP_ID),
                                enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                requireArguments().getString(KEY_CHANNEL_ID),
                                requireArguments().getString(KEY_CHANNEL_LOGIN),
                                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                        }
                        "follow" -> viewModel.saveFollowChannel(
                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                            requireArguments().getString(KEY_CHANNEL_ID),
                            requireArguments().getString(KEY_CHANNEL_LOGIN),
                            requireArguments().getString(KEY_CHANNEL_NAME),
                            prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                            requireArguments().getString(KEY_STARTED_AT),
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                        "unfollow" -> viewModel.deleteFollowChannel(
                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                            requireArguments().getString(KEY_CHANNEL_ID),
                            prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val AUTO_QUALITY = "auto"
        private const val AUDIO_ONLY_QUALITY = "audio_only"
        private const val CHAT_ONLY_QUALITY = "chat_only"

        private const val REQUEST_CODE_QUALITY = 0
        private const val REQUEST_CODE_SPEED = 1
        private const val REQUEST_CODE_AUDIO_ONLY = 2
        private const val REQUEST_CODE_PLAY_PAUSE = 3

        internal const val STREAM = "stream"
        internal const val VIDEO = "video"
        internal const val CLIP = "clip"
        internal const val OFFLINE_VIDEO = "offlineVideo"

        private const val KEY_TYPE = "type"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CLIP_ID = "clipId"
        private const val KEY_OFFLINE_VIDEO_ID = "offlineVideoId"
        private const val KEY_TITLE = "title"
        private const val KEY_VIEWER_COUNT = "viewerCount"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_UPLOAD_DATE = "uploadDate"
        private const val KEY_DURATION = "duration"
        private const val KEY_OFFSET = "offset"
        private const val KEY_IGNORE_SAVED_POSITION = "ignoreSavedPosition"
        private const val KEY_VIDEO_TYPE = "videoType"
        private const val KEY_VIDEO_ANIMATED_PREVIEW = "videoAnimatedPreview"
        private const val KEY_VOD_OFFSET = "vodOffset"
        private const val KEY_URL = "url"
        private const val KEY_CHAT_URL = "chatUrl"
        private const val KEY_CHANNEL_ID = "channelId"
        private const val KEY_CHANNEL_LOGIN = "channelLogin"
        private const val KEY_CHANNEL_NAME = "channelName"
        private const val KEY_PROFILE_IMAGE_URL = "profileImageUrl"
        private const val KEY_CHANNEL_LOGO = "channelLogo"
        private const val KEY_THUMBNAIL = "thumbnail"
        private const val KEY_GAME_ID = "gameId"
        private const val KEY_GAME_SLUG = "gameSlug"
        private const val KEY_GAME_NAME = "gameName"

        fun newInstance(item: Stream): PlayerFragment {
            return PlayerFragment().apply {
                arguments = bundleOf(
                    KEY_TYPE to STREAM,
                    KEY_STREAM_ID to item.id,
                    KEY_TITLE to item.title,
                    KEY_VIEWER_COUNT to (item.viewerCount ?: -1),
                    KEY_STARTED_AT to item.startedAt,
                    KEY_CHANNEL_ID to item.channelId,
                    KEY_CHANNEL_LOGIN to item.channelLogin,
                    KEY_CHANNEL_NAME to item.channelName,
                    KEY_CHANNEL_LOGO to item.channelLogo,
                    KEY_THUMBNAIL to item.thumbnail,
                    KEY_GAME_ID to item.gameId,
                    KEY_GAME_SLUG to item.gameSlug,
                    KEY_GAME_NAME to item.gameName,
                )
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean): PlayerFragment {
            return PlayerFragment().apply {
                arguments = bundleOf(
                    KEY_TYPE to VIDEO,
                    KEY_VIDEO_ID to item.id,
                    KEY_TITLE to item.title,
                    KEY_UPLOAD_DATE to item.uploadDate,
                    KEY_DURATION to item.duration,
                    KEY_OFFSET to (offset ?: -1L),
                    KEY_IGNORE_SAVED_POSITION to ignoreSavedPosition,
                    KEY_VIDEO_TYPE to item.type,
                    KEY_VIDEO_ANIMATED_PREVIEW to item.animatedPreviewURL,
                    KEY_CHANNEL_ID to item.channelId,
                    KEY_CHANNEL_LOGIN to item.channelLogin,
                    KEY_CHANNEL_NAME to item.channelName,
                    KEY_CHANNEL_LOGO to item.channelLogo,
                    KEY_THUMBNAIL to item.thumbnail,
                    KEY_GAME_ID to item.gameId,
                    KEY_GAME_SLUG to item.gameSlug,
                    KEY_GAME_NAME to item.gameName,
                )
            }
        }

        fun newInstance(item: Clip): PlayerFragment {
            return PlayerFragment().apply {
                arguments = bundleOf(
                    KEY_TYPE to CLIP,
                    KEY_CLIP_ID to item.id,
                    KEY_TITLE to item.title,
                    KEY_UPLOAD_DATE to item.uploadDate,
                    KEY_DURATION to item.duration,
                    KEY_VIDEO_ID to item.videoId,
                    KEY_VIDEO_ANIMATED_PREVIEW to item.videoAnimatedPreviewURL,
                    KEY_VOD_OFFSET to (item.vodOffset ?: -1),
                    KEY_CHANNEL_ID to item.channelId,
                    KEY_CHANNEL_LOGIN to item.channelLogin,
                    KEY_CHANNEL_NAME to item.channelName,
                    KEY_PROFILE_IMAGE_URL to item.profileImageUrl,
                    KEY_CHANNEL_LOGO to item.channelLogo,
                    KEY_THUMBNAIL to item.thumbnail,
                    KEY_GAME_ID to item.gameId,
                    KEY_GAME_SLUG to item.gameSlug,
                    KEY_GAME_NAME to item.gameName,
                )
            }
        }

        fun newInstance(item: OfflineVideo): PlayerFragment {
            return PlayerFragment().apply {
                arguments = bundleOf(
                    KEY_TYPE to OFFLINE_VIDEO,
                    KEY_OFFLINE_VIDEO_ID to item.id,
                    KEY_TITLE to item.name,
                    KEY_URL to item.url,
                    KEY_CHAT_URL to item.chatUrl,
                    KEY_CHANNEL_ID to item.channelId,
                    KEY_CHANNEL_LOGIN to item.channelLogin,
                    KEY_CHANNEL_NAME to item.channelName,
                    KEY_CHANNEL_LOGO to item.channelLogo,
                    KEY_GAME_ID to item.gameId,
                    KEY_GAME_SLUG to item.gameSlug,
                    KEY_GAME_NAME to item.gameName,
                )
            }
        }
    }
}
