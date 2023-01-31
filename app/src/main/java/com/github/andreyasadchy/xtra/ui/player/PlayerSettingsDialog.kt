package com.github.andreyasadchy.xtra.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.common.ExpandingBottomSheetDialogFragment
import com.github.andreyasadchy.xtra.ui.download.HasDownloadDialog
import com.github.andreyasadchy.xtra.ui.player.clip.ClipPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.video.VideoPlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import kotlinx.android.synthetic.main.player_settings.*

class PlayerSettingsDialog : ExpandingBottomSheetDialogFragment() {

    companion object {

        private const val QUALITY = "quality"
        private const val SPEED = "speed"
        private const val VOD_GAMES = "vod_games"

        fun newInstance(quality: String?, speed: String?, vodGames: Boolean): PlayerSettingsDialog {
            return PlayerSettingsDialog().apply {
                arguments = bundleOf(QUALITY to quality, SPEED to speed, VOD_GAMES to vodGames)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.player_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val arguments = requireArguments()
        if (parentFragment !is StreamPlayerFragment && requireContext().prefs().getBoolean(C.PLAYER_MENU_SPEED, true)) {
            menuSpeed.visible()
            menuSpeed.setOnClickListener {
                (parentFragment as? BasePlayerFragment)?.showSpeedDialog()
                dismiss()
            }
            setSpeed(arguments.getString(SPEED))
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_MENU_QUALITY, false)) {
            menuQuality.visible()
            menuQuality.setOnClickListener { dismiss() }
            setQuality(arguments.getString(QUALITY))
        }
        if (parentFragment is StreamPlayerFragment) {
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VIEWER_LIST, true)) {
                menuViewerList.visible()
                menuViewerList.setOnClickListener {
                    (parentFragment as? StreamPlayerFragment)?.openViewerList()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_RESTART, false)) {
                menuRestart.visible()
                menuRestart.setOnClickListener {
                    (parentFragment as? StreamPlayerFragment)?.restartPlayer()
                    dismiss()
                }
            }
            if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
                val isLoggedIn = !Account.get(requireContext()).login.isNullOrBlank() && (!Account.get(requireContext()).gqlToken.isNullOrBlank() || !Account.get(requireContext()).helixToken.isNullOrBlank())
                if (isLoggedIn && requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_BAR, true)) {
                    menuChatBar.visible()
                    if (requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                        menuChatBar.text = requireContext().getString(R.string.hide_chat_bar)
                    } else {
                        menuChatBar.text = requireContext().getString(R.string.show_chat_bar)
                    }
                    menuChatBar.setOnClickListener {
                        (parentFragment as? BasePlayerFragment)?.toggleChatBar()
                        dismiss()
                    }
                }
                if ((parentFragment as? BasePlayerFragment)?.isPortrait == false && requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_TOGGLE, false)) {
                    menuChatToggle.visible()
                    if (requireContext().prefs().getBoolean(C.KEY_CHAT_OPENED, true)) {
                        menuChatToggle.text = requireContext().getString(R.string.hide_chat)
                        menuChatToggle.setOnClickListener {
                            (parentFragment as? BasePlayerFragment)?.hideChat()
                            dismiss()
                        }
                    } else {
                        menuChatToggle.text = requireContext().getString(R.string.show_chat)
                        menuChatToggle.setOnClickListener {
                            (parentFragment as? BasePlayerFragment)?.showChat()
                            dismiss()
                        }
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_DISCONNECT, true)) {
                    menuChatDisconnect.visible()
                    if ((parentFragment as? StreamPlayerFragment)?.chatFragment?.isActive() == false) {
                        menuChatDisconnect.text = requireContext().getString(R.string.connect_chat)
                        menuChatDisconnect.setOnClickListener {
                            (parentFragment as? StreamPlayerFragment)?.chatFragment?.reconnect()
                            dismiss()
                        }
                    } else {
                        menuChatDisconnect.text = requireContext().getString(R.string.disconnect_chat)
                        menuChatDisconnect.setOnClickListener {
                            (parentFragment as? StreamPlayerFragment)?.chatFragment?.disconnect()
                            dismiss()
                        }
                    }
                }
            }
        }
        if (parentFragment is VideoPlayerFragment) {
            if (arguments.getBoolean(VOD_GAMES)) {
                setVodGames()
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                (parentFragment as? VideoPlayerFragment)?.checkBookmark()
            }
        }
        if (parentFragment is HasDownloadDialog && requireContext().prefs().getBoolean(C.PLAYER_MENU_DOWNLOAD, true)) {
            menuDownload.visible()
            menuDownload.setOnClickListener {
                (parentFragment as? HasDownloadDialog)?.showDownloadDialog()
                dismiss()
            }
        }
        if (parentFragment !is ClipPlayerFragment && requireContext().prefs().getBoolean(C.PLAYER_MENU_SLEEP, true)) {
            menuTimer.visible()
            menuTimer.setOnClickListener {
                (parentFragment as? BasePlayerFragment)?.showSleepTimerDialog()
                dismiss()
            }
        }
        if ((parentFragment as? BasePlayerFragment)?.isPortrait == false && requireContext().prefs().getBoolean(C.PLAYER_MENU_ASPECT, false)) {
            menuRatio.visible()
            menuRatio.setOnClickListener {
                (parentFragment as? BasePlayerFragment)?.setResizeMode()
                dismiss()
            }
        }
        if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VOLUME, false)) {
            menuVolume.visible()
            menuVolume.setOnClickListener {
                (parentFragment as? BasePlayerFragment)?.showVolumeDialog()
                dismiss()
            }
        }
        (parentFragment as? BasePlayerFragment)?.setSubtitles()
        if ((parentFragment is StreamPlayerFragment || parentFragment is VideoPlayerFragment) && !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && requireContext().prefs().getBoolean(C.PLAYER_MENU_RELOAD_EMOTES, true)) {
            menuReloadEmotes.visible()
            menuReloadEmotes.setOnClickListener {
                (parentFragment as? StreamPlayerFragment)?.chatFragment?.reloadEmotes() ?:
                ((parentFragment as? VideoPlayerFragment)?.childFragmentManager?.findFragmentById(R.id.chatFragmentContainer) as? ChatFragment)?.reloadEmotes()
                dismiss()
            }
        }
    }

    fun setQuality(text: String?) {
        if (!text.isNullOrBlank() && menuQuality.isVisible) {
            qualityValue.visible()
            qualityValue.text = text
            menuQuality.setOnClickListener {
                (parentFragment as? BasePlayerFragment)?.showQualityDialog()
                dismiss()
            }
        }
    }

    fun setSpeed(text: String?) {
        if (!text.isNullOrBlank() && menuSpeed.isVisible) {
            speedValue.visible()
            speedValue.text = text
        }
    }

    fun setVodGames() {
        if (requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false)) {
            menuVodGames.visible()
            menuVodGames.setOnClickListener {
                (parentFragment as? VideoPlayerFragment)?.showVodGames()
                dismiss()
            }
        }
    }

    fun setBookmarkText(isBookmarked: Boolean) {
        menuBookmark.visible()
        menuBookmark.text = requireContext().getString(if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark)
        menuBookmark.setOnClickListener {
            (parentFragment as? VideoPlayerFragment)?.saveBookmark()
            dismiss()
        }
    }

    fun setSubtitles(available: Boolean, enabled: Boolean) {
        if (available && requireContext().prefs().getBoolean(C.PLAYER_MENU_SUBTITLES, false)) {
            menuSubtitles.visible()
            if (enabled) {
                menuSubtitles.text = requireContext().getString(R.string.hide_subtitles)
                menuSubtitles.setOnClickListener {
                    (parentFragment as? BasePlayerFragment)?.toggleSubtitles(false)
                    dismiss()
                }
            } else {
                menuSubtitles.text = requireContext().getString(R.string.show_subtitles)
                menuSubtitles.setOnClickListener {
                    (parentFragment as? BasePlayerFragment)?.toggleSubtitles(true)
                    dismiss()
                }
            }
        } else {
            menuSubtitles.gone()
        }
    }
}
