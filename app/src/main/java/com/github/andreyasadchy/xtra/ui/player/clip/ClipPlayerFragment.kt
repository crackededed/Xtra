package com.github.andreyasadchy.xtra.ui.player.clip

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerClipBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatReplayPlayerFragment
import com.github.andreyasadchy.xtra.ui.download.ClipDownloadDialog
import com.github.andreyasadchy.xtra.ui.download.HasDownloadDialog
import com.github.andreyasadchy.xtra.ui.games.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.games.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.PlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerSettingsDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.FragmentUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.disable
import com.github.andreyasadchy.xtra.util.enable
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.visible
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class ClipPlayerFragment : BasePlayerFragment(), HasDownloadDialog, ChatReplayPlayerFragment {
//    override fun play(obj: Parcelable) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }

    private var _binding: FragmentPlayerClipBinding? = null
    private val binding get() = _binding!!
    override val viewModel: ClipPlayerViewModel by viewModels()
    private lateinit var clip: Clip

    override val controllerShowTimeoutMs: Int = 2500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clip = requireArguments().getParcelable(KEY_CLIP)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerClipBinding.inflate(inflater, container, false).also {
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
        if (prefs.getBoolean(C.PLAYER_MENU, true)) {
            requireView().findViewById<ImageButton>(R.id.playerMenu)?.apply {
                visible()
                setOnClickListener {
                    FragmentUtils.showPlayerSettingsDialog(
                        fragmentManager = childFragmentManager,
                        speedText = SPEED_LABELS.getOrNull(SPEEDS.indexOf(player?.playbackParameters?.speed))?.let { requireContext().getString(it) }
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
        if (!clip.videoId.isNullOrBlank()) {
            binding.watchVideo.visible()
            binding.watchVideo.setOnClickListener {
                (requireActivity() as MainActivity).startVideo(Video(
                    id = clip.videoId,
                    channelId = clip.channelId,
                    channelLogin = clip.channelLogin,
                    channelName = clip.channelName,
                    profileImageUrl = clip.profileImageUrl,
                    animatedPreviewURL = clip.videoAnimatedPreviewURL
                ), (if (clip.vodOffset != null) {
                    ((clip.vodOffset?.toDouble() ?: 0.0) * 1000.0) + (player?.currentPosition ?: 0)
                } else {
                    0.0
                }))
            }
        }
        if (prefs.getBoolean(C.PLAYER_CHANNEL, true)) {
            requireView().findViewById<TextView>(R.id.playerChannel)?.apply {
                visible()
                text = clip.channelName
                setOnClickListener {
                    findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = clip.channelId,
                        channelLogin = clip.channelLogin,
                        channelName = clip.channelName,
                        channelLogo = clip.channelLogo
                    ))
                    slidingLayout.minimize()
                }
            }
        }
        if (!clip.title.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
            requireView().findViewById<TextView>(R.id.playerTitle)?.apply {
                visible()
                text = clip.title
            }
        }
        if (!clip.gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
            requireView().findViewById<TextView>(R.id.playerCategory)?.apply {
                visible()
                text = clip.gameName
                setOnClickListener {
                    findNavController().navigate(
                        if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = clip.gameId,
                                gameName = clip.gameName
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = clip.gameId,
                                gameName = clip.gameName
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
        if (prefs.getBoolean(C.PLAYER_FOLLOW, true) && ((setting == 0 && account.id != clip.channelId || account.login != clip.channelLogin) || setting == 1)) {
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
                        requireContext().shortToast(requireContext().getString(if (following) R.string.now_following else R.string.unfollowed, clip.channelName))
                    }
                } else {
                    initialized = true
                }
                if (errorMessage.isNullOrBlank()) {
                    followButton?.setOnClickListener {
                        if (!following) {
                            viewModel.saveFollowChannel(requireContext(), clip.channelId, clip.channelLogin, clip.channelName, clip.channelLogo)
                        } else {
                            FragmentUtils.showUnfollowDialog(requireContext(), clip.channelName) {
                                viewModel.deleteFollowChannel(requireContext(), clip.channelId)
                            }
                        }
                    }
                    followButton?.setImageResource(if (following) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24)
                }
            }
        }
        if (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) == null) {
            childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, ChatFragment.newInstance(clip.channelId, clip.channelLogin, clip.videoId, clip.vodOffset?.toDouble())).commit()
        }
    }

    override fun initialize() {
        super.initialize()
        val activity = requireActivity() as MainActivity
        val account = Account.get(activity)
        val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
        if (prefs.getBoolean(C.PLAYER_FOLLOW, true) && ((setting == 0 && account.id != clip.channelId || account.login != clip.channelLogin) || setting == 1)) {
            viewModel.isFollowingChannel(requireContext(), clip.channelId, clip.channelLogin)
        }
    }

    override fun startPlayer() {
        super.startPlayer()
        clip.let { clip ->
            val skipAccessToken = prefs.getString(C.TOKEN_SKIP_CLIP_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
            if (skipAccessToken >= 2 || clip.thumbnailUrl.isNullOrBlank()) {
                viewModel.load(prefs.getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"), clip.id)
                viewModel.result.observe(viewLifecycleOwner) { map ->
                    val urls = map ?: if (skipAccessToken == 2 && !clip.thumbnailUrl.isNullOrBlank()) { TwitchApiHelper.getClipUrlMapFromPreview(clip.thumbnailUrl) } else mapOf()
                    player?.sendCustomCommand(SessionCommand(PlaybackService.START_CLIP, bundleOf(
                        PlaybackService.ITEM to clip,
                        PlaybackService.URLS_KEYS to urls.keys.toTypedArray(),
                        PlaybackService.URLS_VALUES to urls.values.toTypedArray()
                    )), Bundle.EMPTY)
                }
            } else {
                val urls = TwitchApiHelper.getClipUrlMapFromPreview(clip.thumbnailUrl)
                player?.sendCustomCommand(SessionCommand(PlaybackService.START_CLIP, bundleOf(
                    PlaybackService.ITEM to clip,
                    PlaybackService.URLS_KEYS to urls.keys.toTypedArray(),
                    PlaybackService.URLS_VALUES to urls.values.toTypedArray()
                )), Bundle.EMPTY)
            }
        }
    }

    override fun showDownloadDialog() {
        if (viewModel.loaded.value == true) {
            player?.sendCustomCommand(SessionCommand(PlaybackService.GET_URLS, Bundle.EMPTY), Bundle.EMPTY)?.let { result ->
                result.addListener({
                    if (result.get().resultCode == SessionResult.RESULT_SUCCESS) {
                        result.get().extras.getStringArray(PlaybackService.URLS_KEYS)?.let { keys ->
                            result.get().extras.getStringArray(PlaybackService.URLS_VALUES)?.let { values ->
                                val urls = keys.zip(values).toMap(mutableMapOf())
                                ClipDownloadDialog.newInstance(clip, urls).show(childFragmentManager, null)
                            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_CLIP = "clip"

        fun newInstance(clip: Clip): ClipPlayerFragment {
            return ClipPlayerFragment().apply { arguments = bundleOf(KEY_CLIP to clip) }
        }
    }
}
