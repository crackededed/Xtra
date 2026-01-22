package com.github.andreyasadchy.xtra.ui.player

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.os.bundleOf
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

        fun newInstance(videoType: String?, speedText: String?, vodGames: Boolean?): PlayerSettingsDialog {
            return PlayerSettingsDialog().apply {
                arguments = bundleOf(
                    TYPE to videoType,
                    SPEED to speedText,
                    VOD_GAMES to vodGames
                )
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
        val videoType = arguments.getString(TYPE)
        with(binding) {
            if (videoType != PlayerFragment.STREAM && requireContext().prefs().getBoolean(C.PLAYER_MENU_SPEED, false)) {
                menuSpeed.visibility = View.VISIBLE
                menuSpeed.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showSpeedDialog()
                    dismiss()
                }
                setSpeed(arguments.getString(SPEED))
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_QUALITY, false)) {
                menuQuality.visibility = View.VISIBLE
                menuQuality.setOnClickListener { dismiss() }
                (parentFragment as? PlayerFragment)?.setQualityText()
            }
            if (videoType == PlayerFragment.STREAM) {
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VIEWER_LIST, true)) {
                    menuViewerList.visibility = View.VISIBLE
                    menuViewerList.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.openViewerList()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_RESTART, false)) {
                    menuRestart.visibility = View.VISIBLE
                    menuRestart.setOnClickListener {
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
                            (parentFragment as? PlayerFragment)?.toggleChatBar()
                            dismiss()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_DISCONNECT, true)) {
                        menuChatDisconnect.visibility = View.VISIBLE
                        if ((parentFragment as? PlayerFragment)?.isActive() == true) {
                            menuChatDisconnect.text = getString(R.string.disconnect_chat)
                            menuChatDisconnect.setOnClickListener {
                                (parentFragment as? PlayerFragment)?.disconnect()
                                dismiss()
                            }
                        } else {
                            menuChatDisconnect.text = getString(R.string.connect_chat)
                            menuChatDisconnect.setOnClickListener {
                                (parentFragment as? PlayerFragment)?.reconnect()
                                dismiss()
                            }
                        }
                    }
                }
                if (requireContext().prefs().getBoolean(C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS, false)) {
                    menuMediaPlaylistTags.visibility = View.VISIBLE
                    menuMediaPlaylistTags.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(true)
                        dismiss()
                    }
                    menuMultivariantPlaylistTags.visibility = View.VISIBLE
                    menuMultivariantPlaylistTags.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(false)
                        dismiss()
                    }
                }
            }
            if (videoType == PlayerFragment.VIDEO) {
                if (arguments.getBoolean(VOD_GAMES)) {
                    setVodGames()
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                    (parentFragment as? PlayerFragment)?.checkBookmark()
                }
            }
            if (videoType != PlayerFragment.OFFLINE_VIDEO && requireContext().prefs().getBoolean(C.PLAYER_MENU_DOWNLOAD, true)) {
                menuDownload.visibility = View.VISIBLE
                menuDownload.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showDownloadDialog()
                    dismiss()
                }
            }
            if (videoType != PlayerFragment.CLIP && requireContext().prefs().getBoolean(C.PLAYER_MENU_SLEEP, true)) {
                menuTimer.visibility = View.VISIBLE
                menuTimer.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showSleepTimerDialog()
                    dismiss()
                }
            }
            if ((parentFragment as? PlayerFragment)?.getIsPortrait() == false) {
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_ASPECT, false)) {
                    menuRatio.visibility = View.VISIBLE
                    menuRatio.setOnClickListener {
                        (parentFragment as? PlayerFragment)?.setResizeMode()
                        dismiss()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_TOGGLE, false)) {
                    menuChatToggle.visibility = View.VISIBLE
                    if (requireContext().prefs().getBoolean(C.KEY_CHAT_OPENED, true)) {
                        menuChatToggle.text = getString(R.string.hide_chat)
                        menuChatToggle.setOnClickListener {
                            (parentFragment as? PlayerFragment)?.hideChat()
                            dismiss()
                        }
                    } else {
                        menuChatToggle.text = getString(R.string.show_chat)
                        menuChatToggle.setOnClickListener {
                            (parentFragment as? PlayerFragment)?.showChat()
                            dismiss()
                        }
                    }
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VOLUME, false)) {
                menuVolume.visibility = View.VISIBLE
                menuVolume.setOnClickListener {
                    (parentFragment as? PlayerFragment)?.showVolumeDialog()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                val translateAll = (parentFragment as? PlayerFragment)?.getTranslateAllMessages()
                if (translateAll != null) {
                    menuTranslateAll.visibility = View.VISIBLE
                    if (translateAll) {
                        menuTranslateAll.setOnClickListener {
                            (parentFragment as? PlayerFragment)?.deleteTranslateAllMessagesUser()
                            dismiss()
                        }
                    } else {
                        menuTranslateAll.setOnClickListener {
                            (parentFragment as? PlayerFragment)?.saveTranslateAllMessagesUser()
                            dismiss()
                        }
                    }
                }
            }
            (parentFragment as? PlayerFragment)?.setSubtitlesButton()
            if ((videoType == PlayerFragment.STREAM || videoType == PlayerFragment.VIDEO) &&
                !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) &&
                requireContext().prefs().getBoolean(C.PLAYER_MENU_RELOAD_EMOTES, true)
            ) {
                menuReloadEmotes.visibility = View.VISIBLE
                menuReloadEmotes.setOnClickListener {
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
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                        dismiss()
                    }
                } else {
                    menuSubtitles.text = getString(R.string.show_subtitles)
                    menuSubtitles.setOnClickListener {
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
