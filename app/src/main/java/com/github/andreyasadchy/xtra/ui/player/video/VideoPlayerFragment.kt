package com.github.andreyasadchy.xtra.ui.player.video

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerVideoBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.VideoDownloadInfo
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatReplayPlayerFragment
import com.github.andreyasadchy.xtra.ui.download.HasDownloadDialog
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadDialog
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerGamesDialog
import com.github.andreyasadchy.xtra.ui.player.PlayerSettingsDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.FragmentUtils
import com.github.andreyasadchy.xtra.util.disable
import com.github.andreyasadchy.xtra.util.enable
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class VideoPlayerFragment : BasePlayerFragment(), HasDownloadDialog, ChatReplayPlayerFragment, PlayerGamesDialog.PlayerSeekListener {
//    override fun play(obj: Parcelable) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }

    private var _binding: FragmentPlayerVideoBinding? = null
    private val binding get() = _binding!!
    override val viewModel: VideoPlayerViewModel by viewModels()
    private lateinit var video: Video

    override val controllerShowTimeoutMs: Int = 5000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = requireArguments().getParcelable(KEY_VIDEO)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerVideoBinding.inflate(inflater, container, false).also {
            (it.slidingLayout as LinearLayout).orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = requireView().findViewById<ImageButton>(R.id.playerSettings)
        val download = requireView().findViewById<ImageButton>(R.id.playerDownload)
        val mode = requireView().findViewById<ImageButton>(R.id.playerMode)
        viewModel.loaded.observe(viewLifecycleOwner) {
            if (it) {
                settings?.enable()
                download?.enable()
                mode?.enable()
                (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.let { setQualityText() }
            } else {
                settings?.disable()
                download?.disable()
                mode?.disable()
            }
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
            viewModel.bookmarkItem.observe(viewLifecycleOwner) {
                (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setBookmarkText(it != null)
            }
        }
        if (prefs.getBoolean(C.PLAYER_MENU, true)) {
            requireView().findViewById<ImageButton>(R.id.playerMenu)?.apply {
                visible()
                setOnClickListener {
                    FragmentUtils.showPlayerSettingsDialog(
                        fragmentManager = childFragmentManager,
                        speedText = SPEED_LABELS.getOrNull(SPEEDS.indexOf(player?.playbackParameters?.speed))?.let { requireContext().getString(it) },
                        vodGames = !viewModel.gamesList.value.isNullOrEmpty()
                    )
                }
            }
        }
        if (prefs.getBoolean(C.PLAYER_DOWNLOAD, false)) {
            download?.apply {
                visible()
                setOnClickListener { showDownloadDialog() }
            }
        }
        if ((prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false)) && !video.id.isNullOrBlank()) {
            viewModel.gamesList.observe(viewLifecycleOwner) { list ->
                if (list.isNotEmpty()) {
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
        if (prefs.getBoolean(C.PLAYER_CHANNEL, true)) {
            requireView().findViewById<TextView>(R.id.playerChannel)?.apply {
                visible()
                text = video.channelName
                setOnClickListener {
                    findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = video.channelId,
                        channelLogin = video.channelLogin,
                        channelName = video.channelName,
                        channelLogo = video.channelLogo
                    ))
                    slidingLayout.minimize()
                }
            }
        }
        if (!video.title.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
            requireView().findViewById<TextView>(R.id.playerTitle)?.apply {
                visible()
                text = video.title
            }
        }
        if (!video.gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
            requireView().findViewById<TextView>(R.id.playerCategory)?.apply {
                visible()
                text = video.gameName
                setOnClickListener {
                    findNavController().navigate(
                        if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = video.gameId,
                                gameName = video.gameName
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = video.gameId,
                                gameName = video.gameName
                            )
                        }
                    )
                    slidingLayout.minimize()
                }
            }
        }
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
        if (prefs.getBoolean(C.PLAYER_FOLLOW, true) && ((setting == 0 && account.id != video.channelId || account.login != video.channelLogin) || setting == 1)) {
            val followButton = requireView().findViewById<ImageButton>(R.id.playerFollow)
            followButton?.visible()
            var initialized = false
            viewModel.follow.observe(viewLifecycleOwner) { pair ->
                val following = pair.first
                val errorMessage = pair.second
                if (initialized) {
                    if (!errorMessage.isNullOrBlank()) {
                        requireContext().shortToast(errorMessage)
                    } else {
                        requireContext().shortToast(requireContext().getString(if (following) R.string.now_following else R.string.unfollowed, video.channelName))
                    }
                } else {
                    initialized = true
                }
                if (errorMessage.isNullOrBlank()) {
                    followButton?.setOnClickListener {
                        if (!following) {
                            viewModel.saveFollowChannel(requireContext(), video.channelId, video.channelLogin, video.channelName, video.channelLogo)
                        } else {
                            FragmentUtils.showUnfollowDialog(requireContext(), video.channelName) {
                                viewModel.deleteFollowChannel(requireContext(), video.channelId)
                            }
                        }
                    }
                    followButton?.setImageResource(if (following) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24)
                }
            }
        }
        if (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) == null) {
            childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, ChatFragment.newInstance(video.channelId, video.channelLogin, video.id, 0.0)).commit()
        }
    }

    override fun initialize() {
        super.initialize()
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
        if (prefs.getBoolean(C.PLAYER_FOLLOW, true) && ((setting == 0 && account.id != video.channelId || account.login != video.channelLogin) || setting == 1)) {
            viewModel.isFollowingChannel(requireContext(), video.channelId, video.channelLogin)
        }
        if ((prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false)) && !video.id.isNullOrBlank()) {
            viewModel.loadGamesList(prefs.getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"), video.id)
        }
    }

    override fun startPlayer() {
        super.startPlayer()
        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, requireArguments().getDouble(KEY_OFFSET).toLong())
    }

    private fun playVideo(skipAccessToken: Boolean, playbackPosition: Long?) {
        if (skipAccessToken && !video.animatedPreviewURL.isNullOrBlank()) {
            player?.sendCustomCommand(SessionCommand(PlaybackService.START_VIDEO, bundleOf(
                PlaybackService.ITEM to video,
                PlaybackService.USING_PLAYLIST to false,
                PlaybackService.PLAYBACK_POSITION to playbackPosition
            )), Bundle.EMPTY)
        } else {
            viewModel.load(
                gqlClientId = prefs.getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"),
                gqlToken = if (prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)) Account.get(requireContext()).gqlToken else null,
                videoId = video.id,
                playerType = prefs.getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live")
            )
            viewModel.result.observe(viewLifecycleOwner) { url ->
                player?.sendCustomCommand(SessionCommand(PlaybackService.START_VIDEO, bundleOf(
                    PlaybackService.ITEM to video,
                    PlaybackService.URI to url.toString(),
                    PlaybackService.USING_PLAYLIST to true,
                    PlaybackService.PLAYBACK_POSITION to playbackPosition
                )), Bundle.EMPTY)
            }
        }
    }

    override fun onError(error: PlaybackException) {
        Log.e(tag, "Player error", error)
        player?.sendCustomCommand(SessionCommand(PlaybackService.GET_ERROR_CODE, Bundle.EMPTY), Bundle.EMPTY)?.let { result ->
            result.addListener({
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                    val responseCode = result.get().extras.getInt(PlaybackService.RESULT)
                    if (responseCode != 0) {
                        val skipAccessToken = prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                        when {
                            skipAccessToken == 1 && viewModel.shouldRetry -> {
                                viewModel.shouldRetry = false
                                playVideo(false, player?.currentPosition)
                            }
                            skipAccessToken == 2 && viewModel.shouldRetry -> {
                                viewModel.shouldRetry = false
                                playVideo(true, player?.currentPosition)
                            }
                            else -> {
                                if (responseCode == 403) {
                                    requireContext().toast(R.string.video_subscribers_only)
                                }
                            }
                        }
                    } else {
                        super.onError(error)
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    fun showVodGames() {
        viewModel.gamesList.value?.let { FragmentUtils.showPlayerGamesDialog(childFragmentManager, it) }
    }

    fun checkBookmark() {
        video.id?.let { viewModel.checkBookmark(it) }
    }

    fun saveBookmark() {
        viewModel.saveBookmark(requireContext(), video)
    }

    override fun seek(position: Long) {
        player?.seekTo(position)
    }

    override fun showDownloadDialog() {
        if (viewModel.loaded.value == true) {
            player?.sendCustomCommand(SessionCommand(PlaybackService.GET_VIDEO_DOWNLOAD_INFO, Bundle.EMPTY), Bundle.EMPTY)?.let { result ->
                result.addListener({
                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            result.get().extras.getParcelable(PlaybackService.RESULT, VideoDownloadInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION") result.get().extras.getParcelable(PlaybackService.RESULT) as? VideoDownloadInfo
                        }?.let {
                            VideoDownloadDialog.newInstance(it.copy(video = video)).show(childFragmentManager, null)
                        }
                    }
                }, MoreExecutors.directExecutor())
            }
        }
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            player?.prepare()
        }
    }

    override fun onNetworkLost() {
        if (isResumed) {
            player?.stop()
        }
    }

    override fun getCurrentPosition(): Double {
        return runBlocking(Dispatchers.Main) { (player?.currentPosition ?: 0) / 1000.0 }
    }

    override fun onClose() {
        if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
            video.id?.toLongOrNull()?.let { id ->
                player?.currentPosition?.let { position ->
                    viewModel.savePosition(id, position)
                }
            }
        }
        super.onClose()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_VIDEO = "video"
        private const val KEY_OFFSET = "offset"

        fun newInstance(video: Video, offset: Double? = null): VideoPlayerFragment {
            return VideoPlayerFragment().apply { arguments = bundleOf(KEY_VIDEO to video, KEY_OFFSET to offset) }
        }
    }
}
