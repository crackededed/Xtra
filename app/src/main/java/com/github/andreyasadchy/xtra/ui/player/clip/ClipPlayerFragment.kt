package com.github.andreyasadchy.xtra.ui.player.clip

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatReplayPlayerFragment
import com.github.andreyasadchy.xtra.ui.download.ClipDownloadDialog
import com.github.andreyasadchy.xtra.ui.download.HasDownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlayerSettingsDialog
import com.github.andreyasadchy.xtra.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.fragment_player_clip.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class ClipPlayerFragment : BasePlayerFragment(), HasDownloadDialog, ChatReplayPlayerFragment {
//    override fun play(obj: Parcelable) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }

    override val viewModel: ClipPlayerViewModel by viewModels()
    private lateinit var clip: Clip
    override val channelId: String?
        get() = clip.channelId
    override val channelLogin: String?
        get() = clip.channelLogin
    override val channelName: String?
        get() = clip.channelName
    override val channelImage: String?
        get() = clip.channelLogo

    override val layoutId: Int
        get() = R.layout.fragment_player_clip
    override val chatContainerId: Int
        get() = R.id.clipChatContainer

    override val shouldEnterPictureInPicture: Boolean
        get() = true

    override val controllerShowTimeoutMs: Int = 2500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clip = requireArguments().getParcelable(KEY_CLIP)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) == null) {
            childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, ChatFragment.newInstance(channelId, channelLogin, clip.videoId, clip.vodOffset?.toDouble())).commit()
        }
    }

    override fun initialize() {
        viewModel.setClip(clip)
        super.initialize()
        val settings = requireView().findViewById<ImageButton>(R.id.playerSettings)
        val playerMenu = requireView().findViewById<ImageButton>(R.id.playerMenu)
        val download = requireView().findViewById<ImageButton>(R.id.playerDownload)
        viewModel.loaded.observe(this) {
            settings.enable()
            download.enable()
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setQuality(viewModel.qualities?.getOrNull(viewModel.qualityIndex))
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
                    speed = SPEED_LABELS.getOrNull(SPEEDS.indexOf(viewModel.player?.playbackParameters?.speed))?.let { requireContext().getString(it) }
                )
            }
        }
        if (prefs.getBoolean(C.PLAYER_DOWNLOAD, false)) {
            download.visible()
            download.setOnClickListener { showDownloadDialog() }
        }
        if (!clip.videoId.isNullOrBlank()) {
            watchVideo.visible()
            watchVideo.setOnClickListener {
                (requireActivity() as MainActivity).startVideo(Video(
                    id = clip.videoId,
                    channelId = clip.channelId,
                    channelLogin = clip.channelLogin,
                    channelName = clip.channelName,
                    profileImageUrl = clip.profileImageUrl,
                    animatedPreviewURL = clip.videoAnimatedPreviewURL
                ), (if (clip.vodOffset != null) {
                    ((clip.vodOffset?.toDouble() ?: 0.0) * 1000.0) + (viewModel.player?.currentPosition ?: 0)
                } else {
                    0.0
                }))
            }
        }
    }

    override fun showDownloadDialog() {
        if (DownloadUtils.hasStoragePermission(requireActivity())) {
            ClipDownloadDialog.newInstance(clip, viewModel.qualityMap).show(childFragmentManager, null)
        }
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            viewModel.onResume()
        }
    }

    override fun onNetworkLost() {
        if (isResumed) {
            viewModel.onPause()
        }
    }

    override fun getCurrentPosition(): Double {
        return runBlocking(Dispatchers.Main) { (viewModel.player?.currentPosition ?: 0) / 1000.0 }
    }

    companion object {
        private const val KEY_CLIP = "clip"

        fun newInstance(clip: Clip): ClipPlayerFragment {
            return ClipPlayerFragment().apply { arguments = bundleOf(KEY_CLIP to clip) }
        }
    }
}
