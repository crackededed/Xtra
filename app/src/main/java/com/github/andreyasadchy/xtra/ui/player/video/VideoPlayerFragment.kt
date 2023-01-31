package com.github.andreyasadchy.xtra.ui.player.video

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatReplayPlayerFragment
import com.github.andreyasadchy.xtra.ui.download.HasDownloadDialog
import com.github.andreyasadchy.xtra.ui.download.VideoDownloadDialog
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlayerGamesDialog
import com.github.andreyasadchy.xtra.ui.player.PlayerMode
import com.github.andreyasadchy.xtra.ui.player.PlayerSettingsDialog
import com.github.andreyasadchy.xtra.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class VideoPlayerFragment : BasePlayerFragment(), HasDownloadDialog, ChatReplayPlayerFragment, PlayerGamesDialog.PlayerSeekListener {
//    override fun play(obj: Parcelable) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }

    override val viewModel: VideoPlayerViewModel by viewModels()
    private lateinit var video: Video
    override val channelId: String?
        get() = video.channelId
    override val channelLogin: String?
        get() = video.channelLogin
    override val channelName: String?
        get() = video.channelName
    override val channelImage: String?
        get() = video.channelLogo

    override val layoutId: Int
        get() = R.layout.fragment_player_video
    override val chatContainerId: Int
        get() = R.id.chatFragmentContainer

    override val shouldEnterPictureInPicture: Boolean
        get() = viewModel.playerMode.value == PlayerMode.NORMAL

    override val controllerShowTimeoutMs: Int = 5000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = requireArguments().getParcelable(KEY_VIDEO)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) == null) {
            childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, ChatFragment.newInstance(channelId, channelLogin, video.id, 0.0)).commit()
        }
    }

    override fun initialize() {
        viewModel.setVideo(video, requireArguments().getDouble(KEY_OFFSET))
        super.initialize()
        val settings = requireView().findViewById<ImageButton>(R.id.playerSettings)
        val playerMenu = requireView().findViewById<ImageButton>(R.id.playerMenu)
        val download = requireView().findViewById<ImageButton>(R.id.playerDownload)
        val gamesButton = requireView().findViewById<ImageButton>(R.id.playerGames)
        val mode = requireView().findViewById<ImageButton>(R.id.playerMode)
        viewModel.loaded.observe(viewLifecycleOwner) {
            if (it) {
                settings.enable()
                download.enable()
                mode.enable()
                (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setQuality(viewModel.qualities?.getOrNull(viewModel.qualityIndex))
            } else {
                settings.disable()
                download.disable()
                mode.disable()
            }
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
            viewModel.bookmarkItem.observe(viewLifecycleOwner) {
                (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setBookmarkText(it != null)
            }
        }
        if (prefs.getBoolean(C.PLAYER_SETTINGS, true)) {
            settings.visible()
            settings.setOnClickListener { showQualityDialog() }
        }
        if (prefs.getBoolean(C.PLAYER_MENU, true)) {
            playerMenu.visible()
            playerMenu.setOnClickListener {
                FragmentUtils.showPlayerSettingsDialog(
                    fragmentManager = childFragmentManager,
                    quality = if (viewModel.loaded.value == true) viewModel.qualities?.getOrNull(viewModel.qualityIndex) else null,
                    speed = SPEED_LABELS.getOrNull(SPEEDS.indexOf(viewModel.player?.playbackParameters?.speed))?.let { requireContext().getString(it) },
                    vodGames = !viewModel.gamesList.value.isNullOrEmpty()
                )
            }
        }
        if (prefs.getBoolean(C.PLAYER_DOWNLOAD, false)) {
            download.visible()
            download.setOnClickListener { showDownloadDialog() }
        }
        if (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false) && !video.id.isNullOrBlank()) {
            viewModel.loadGamesList(prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"), video.id)
            viewModel.gamesList.observe(viewLifecycleOwner) { list ->
                if (list.isNotEmpty()) {
                    if (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true)) {
                        gamesButton.visible()
                        gamesButton.setOnClickListener { showVodGames() }
                    }
                    (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setVodGames()
                }
            }
        }
        if (prefs.getBoolean(C.PLAYER_MODE, false)) {
            mode.visible()
            mode.setOnClickListener {
                if (viewModel.playerMode.value != PlayerMode.AUDIO_ONLY) {
                    viewModel.qualities?.lastIndex?.let { viewModel.changeQuality(it) }
                } else {
                    viewModel.changeQuality(viewModel.previousQuality)
                }
            }
        }
    }

    fun showVodGames() {
        viewModel.gamesList.value?.let { FragmentUtils.showPlayerGamesDialog(childFragmentManager, it) }
    }

    fun checkBookmark() {
        viewModel.checkBookmark()
    }

    fun saveBookmark() {
        viewModel.saveBookmark()
    }

    override fun seek(position: Long) {
        viewModel.player?.seekTo(position)
    }

    override fun showDownloadDialog() {
        if (DownloadUtils.hasStoragePermission(requireActivity())) {
            viewModel.videoInfo?.let { VideoDownloadDialog.newInstance(it).show(childFragmentManager, null) }
        }
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            viewModel.resumePlayer()
        }
    }

    override fun onNetworkLost() {
        if (isResumed) {
            viewModel.stopPlayer()
        }
    }

    override fun getCurrentPosition(): Double {
        return runBlocking(Dispatchers.Main) { (viewModel.player?.currentPosition ?: 0) / 1000.0 }
    }

    fun startAudioOnly() {
        viewModel.startAudioOnly()
    }

    companion object {
        private const val KEY_VIDEO = "video"
        private const val KEY_OFFSET = "offset"

        fun newInstance(video: Video, offset: Double? = null): VideoPlayerFragment {
            return VideoPlayerFragment().apply { arguments = bundleOf(KEY_VIDEO to video, KEY_OFFSET to offset) }
        }
    }
}
