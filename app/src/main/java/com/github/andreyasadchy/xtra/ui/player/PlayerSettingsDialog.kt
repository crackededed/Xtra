package com.github.andreyasadchy.xtra.ui.player

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.media3.common.Tracks
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.PlayerSettingsBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlayerSettingsDialog : BottomSheetDialogFragment() {

    companion object {

        private const val TYPE = "type"
        private const val SPEED = "speed"
        private const val VOD_GAMES = "vod_games"

        fun newInstance(type: String?, speedText: String?, vodGames: Boolean): PlayerSettingsDialog {
            return PlayerSettingsDialog().apply {
                arguments = Bundle().apply {
                    putString(TYPE, type)
                    putString(SPEED, speedText)
                    putBoolean(VOD_GAMES, vodGames)
                }
            }
        }
    }

    private var _binding: PlayerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = PlayerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val arguments = requireArguments()
        val type = arguments.getString(TYPE)
        with(binding) {
            if (type != BasePlaybackService.STREAM && requireContext().prefs().getBoolean(C.PLAYER_MENU_SPEED, false)) {
                menuSpeed.visibility = View.VISIBLE
                menuSpeed.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showSpeedDialog() ?:
                    (parentFragment as? PlayerFragment)?.showSpeedDialog()
                    dismiss()
                }
                setSpeed(arguments.getString(SPEED))
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_QUALITY, false)) {
                menuQuality.visibility = View.VISIBLE
                menuQuality.setOnClickListener { dismiss() }
                (parentFragment as? Media3PlayerFragment)?.setQualityText() ?:
                (parentFragment as? PlayerFragment)?.setQualityText()
            }
            if (type == BasePlaybackService.STREAM) {
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VIEWER_LIST, true)) {
                    menuViewerList.visibility = View.VISIBLE
                    menuViewerList.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.openViewerList() ?:
                        (parentFragment as? PlayerFragment)?.openViewerList()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_RESTART, false)) {
                    menuRestart.visibility = View.VISIBLE
                    menuRestart.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.restartPlayer() ?:
                        (parentFragment as? PlayerFragment)?.restartPlayer()
                        dismiss()
                    }
                }
                if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
                    val isLoggedIn = !requireContext().tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                            (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                    if (isLoggedIn && requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_BAR, true)) {
                        menuChatBar.visibility = View.VISIBLE
                        if (requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                            menuChatBar.text = getString(R.string.hide_chat_bar)
                        } else {
                            menuChatBar.text = getString(R.string.show_chat_bar)
                        }
                        menuChatBar.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.toggleChatBar() ?:
                            (parentFragment as? PlayerFragment)?.toggleChatBar()
                            dismiss()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_DISCONNECT, true)) {
                        menuChatDisconnect.visibility = View.VISIBLE
                        if (((parentFragment as? Media3PlayerFragment)?.isActive() ?: (parentFragment as? PlayerFragment)?.isActive()) == true) {
                            menuChatDisconnect.text = getString(R.string.disconnect_chat)
                            menuChatDisconnect.setOnClickListener {
                                (parentFragment as? Media3PlayerFragment)?.disconnect() ?:
                                (parentFragment as? PlayerFragment)?.disconnect()
                                dismiss()
                            }
                        } else {
                            menuChatDisconnect.text = getString(R.string.connect_chat)
                            menuChatDisconnect.setOnClickListener {
                                (parentFragment as? Media3PlayerFragment)?.reconnect() ?:
                                (parentFragment as? PlayerFragment)?.reconnect()
                                dismiss()
                            }
                        }
                    }
                }
                if (requireContext().prefs().getBoolean(C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS, false)) {
                    menuMediaPlaylistTags.visibility = View.VISIBLE
                    menuMediaPlaylistTags.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.showPlaylistTags(true) ?:
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(true)
                        dismiss()
                    }
                    menuMultivariantPlaylistTags.visibility = View.VISIBLE
                    menuMultivariantPlaylistTags.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.showPlaylistTags(false) ?:
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(false)
                        dismiss()
                    }
                }
            }
            if (type == BasePlaybackService.VIDEO) {
                if (arguments.getBoolean(VOD_GAMES)) {
                    setVodGames()
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                    (parentFragment as? Media3PlayerFragment)?.checkBookmark() ?:
                    (parentFragment as? PlayerFragment)?.checkBookmark()
                }
            }
            if (type != BasePlaybackService.OFFLINE_VIDEO && requireContext().prefs().getBoolean(C.PLAYER_MENU_DOWNLOAD, true)) {
                menuDownload.visibility = View.VISIBLE
                menuDownload.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showDownloadDialog() ?:
                    (parentFragment as? PlayerFragment)?.showDownloadDialog()
                    dismiss()
                }
            }
            if (type != BasePlaybackService.CLIP && requireContext().prefs().getBoolean(C.PLAYER_MENU_SLEEP, true)) {
                menuTimer.visibility = View.VISIBLE
                menuTimer.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showSleepTimerDialog() ?:
                    (parentFragment as? PlayerFragment)?.showSleepTimerDialog()
                    dismiss()
                }
            }
            if (((parentFragment as? Media3PlayerFragment)?.getIsPortrait() ?: (parentFragment as? PlayerFragment)?.getIsPortrait()) == false) {
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_ASPECT, false)) {
                    menuRatio.visibility = View.VISIBLE
                    menuRatio.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.setResizeMode() ?:
                        (parentFragment as? PlayerFragment)?.setResizeMode()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_TOGGLE, false)) {
                    menuChatToggle.visibility = View.VISIBLE
                    if (requireContext().prefs().getBoolean(C.KEY_CHAT_OPENED, true)) {
                        menuChatToggle.text = getString(R.string.hide_chat)
                        menuChatToggle.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.hideChat() ?:
                            (parentFragment as? PlayerFragment)?.hideChat()
                            dismiss()
                        }
                    } else {
                        menuChatToggle.text = getString(R.string.show_chat)
                        menuChatToggle.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.showChat() ?:
                            (parentFragment as? PlayerFragment)?.showChat()
                            dismiss()
                        }
                    }
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VOLUME, false)) {
                menuVolume.visibility = View.VISIBLE
                menuVolume.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showVolumeDialog() ?:
                    (parentFragment as? PlayerFragment)?.showVolumeDialog()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                val translateAll = (parentFragment as? Media3PlayerFragment)?.getTranslateAllMessages() ?: (parentFragment as? PlayerFragment)?.getTranslateAllMessages()
                if (translateAll != null) {
                    menuTranslateAll.visibility = View.VISIBLE
                    if (translateAll) {
                        menuTranslateAll.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.deleteTranslateAllMessagesUser() ?:
                            (parentFragment as? PlayerFragment)?.deleteTranslateAllMessagesUser()
                            dismiss()
                        }
                    } else {
                        menuTranslateAll.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.saveTranslateAllMessagesUser() ?:
                            (parentFragment as? PlayerFragment)?.saveTranslateAllMessagesUser()
                            dismiss()
                        }
                    }
                }
            }
            (parentFragment as? Media3PlayerFragment)?.setSubtitlesButton() ?:
            (parentFragment as? PlayerFragment)?.setSubtitlesButton()
            if ((type == BasePlaybackService.STREAM || type == BasePlaybackService.VIDEO) &&
                !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) &&
                requireContext().prefs().getBoolean(C.PLAYER_MENU_RELOAD_EMOTES, true)
            ) {
                menuReloadEmotes.visibility = View.VISIBLE
                menuReloadEmotes.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.reloadEmotes() ?:
                    (parentFragment as? PlayerFragment)?.reloadEmotes()
                    dismiss()
                }
            }
        }
    }

    fun setQuality(text: String?) {
        with(binding) {
            if (!text.isNullOrBlank() && menuQuality.isVisible) {
                qualityValue.visibility = View.VISIBLE
                qualityValue.text = text
                menuQuality.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showQualityDialog() ?:
                    (parentFragment as? PlayerFragment)?.showQualityDialog()
                    dismiss()
                }
            }
        }
    }

    fun setSpeed(text: String?) {
        with(binding) {
            if (!text.isNullOrBlank() && menuSpeed.isVisible) {
                speedValue.visibility = View.VISIBLE
                speedValue.text = text
            }
        }
    }

    fun setVodGames() {
        with(binding) {
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false)) {
                menuVodGames.visibility = View.VISIBLE
                menuVodGames.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showVodGames() ?:
                    (parentFragment as? PlayerFragment)?.showVodGames()
                    dismiss()
                }
            }
        }
    }

    fun setBookmarkText(isBookmarked: Boolean) {
        with(binding) {
            menuBookmark.visibility = View.VISIBLE
            menuBookmark.text = getString(if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark)
            menuBookmark.setOnClickListener {
                (parentFragment as? Media3PlayerFragment)?.saveBookmark() ?:
                (parentFragment as? PlayerFragment)?.saveBookmark()
                dismiss()
            }
        }
    }

    fun setSubtitles(subtitles: Tracks.Group? = null) {
        with(binding) {
            if (subtitles != null && requireContext().prefs().getBoolean(C.PLAYER_MENU_SUBTITLES, true)) {
                menuSubtitles.visibility = View.VISIBLE
                if (subtitles.isSelected) {
                    menuSubtitles.text = getString(R.string.hide_subtitles)
                    menuSubtitles.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.toggleSubtitles(false) ?:
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                        dismiss()
                    }
                } else {
                    menuSubtitles.text = getString(R.string.show_subtitles)
                    menuSubtitles.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.toggleSubtitles(true) ?:
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(true)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                        dismiss()
                    }
                }
            } else {
                menuSubtitles.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
