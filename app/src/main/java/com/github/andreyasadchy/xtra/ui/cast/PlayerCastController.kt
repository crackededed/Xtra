package com.github.andreyasadchy.xtra.ui.cast

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.mediarouter.media.MediaRouteSelector
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.util.concurrent.atomic.AtomicBoolean

class PlayerCastController(private val fragment: PlayerFragment) {

    private var castSessionListener: SessionManagerListener<CastSession>? = null
    internal var castManager: CastManager? = null
    private var _castQuality: VideoQuality? = null
    private var _localVideoQuality: VideoQuality? = null
    private var _chatOnlyEnabled = false
    private var _chatOnlyEnabledByCast = false
    private var _chatOnlyUserOverride: Boolean? = null
    private var _castCurrentContentActive = false
    private val castPlayInProgress = AtomicBoolean(false)
    private var castPendingOnConnect = false
    private var castRetryCount = 0
    private val castRetryHandler = Handler(Looper.getMainLooper())
    private val castRetryAction = Runnable { retryPendingCast() }
    private val castPlayRequestAction: () -> Unit = { playCurrentStreamOnCastDevice() }
    private var castPendingType: String? = null
    private var castPendingChannelId: String? = null
    private var castPendingVideoId: String? = null
    private var castPendingClipId: String? = null

    val castQuality: VideoQuality?
        get() = _castQuality

    var localVideoQuality: VideoQuality?
        get() = _localVideoQuality
        set(value) {
            _localVideoQuality = value
        }

    val chatOnlyEnabled: Boolean
        get() = _chatOnlyEnabled

    companion object {
        private const val CAST_RETRY_DELAY = 500L
        private const val CAST_RETRY_LIMIT = 20
    }

    fun setup() {
        try {
            val manager = (fragment.requireActivity().application as? com.github.andreyasadchy.xtra.XtraApp)?.xtraModule?.castManager ?: return
            castManager = manager
            val routeSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            fragment.view?.let { view ->
                val castButton = view.findViewById<androidx.mediarouter.app.MediaRouteButton>(com.github.andreyasadchy.xtra.R.id.castButton)
                castButton.routeSelector = routeSelector
                castButton.visibility = if (fragment.requireContext().prefs().getBoolean(C.PLAYER_SHOW_CAST_BUTTON, true)) android.view.View.VISIBLE else android.view.View.GONE
            }
            fragment.view?.let { view ->
                val castButton = view.findViewById<androidx.mediarouter.app.MediaRouteButton>(com.github.andreyasadchy.xtra.R.id.castButton)
                castButton.dialogFactory = CastControllerDialogFactory()
            }
            manager.playCurrentRequest = castPlayRequestAction

            if (manager.isConnected && !manager.hasCastedContent()) {
                val service = fragment.playbackService
                if (service != null) {
                    manager.setCastedContent(
                        service.type,
                        service.channelId,
                        service.videoId,
                        service.clipId,
                    )
                }
            }

            val callback = CastManager.ConnectionCallback { connected ->
                if (connected) {
                    onCastConnected()
                } else {
                    onCastDisconnected()
                }
            }
            castSessionListener = manager.addConnectionCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun changeQuality(selectedQuality: VideoQuality?) {
        val service = fragment.playbackService ?: return
        val resolved = resolveCastQuality(selectedQuality) ?: return
        val url = resolved.url ?: return
        val isLive = service.type == BasePlaybackService.STREAM
        val durationMs = if (isLive) null else fragment.playbackService?.durationSeconds?.toLong()?.times(1000)
        val currentTime = if (!isLive) {
            try {
                castManager?.remoteMediaClient?.approximateStreamPosition?.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        castManager?.loadStream(
            url,
            service.title,
            service.channelName,
            service.thumbnail,
            currentTimeMs = currentTime,
            isLive = isLive,
            durationMs = durationMs,
            onResult = { success ->
                if (success && fragment.view != null) {
                    _castQuality = resolved
                    fragment.setQualityText()
                }
            }
        )
    }

    fun updateButtonVisibility() {
        val isLiveStream = fragment.playbackService?.type == BasePlaybackService.STREAM
        val showCastForVod = fragment.requireContext().prefs().getBoolean(com.github.andreyasadchy.xtra.util.C.CAST_BUTTON_VOD, false)
        val showCastButton = fragment.requireContext().prefs().getBoolean(com.github.andreyasadchy.xtra.util.C.PLAYER_SHOW_CAST_BUTTON, true)
        fragment.view?.let { view ->
            val castButton = view.findViewById<androidx.mediarouter.app.MediaRouteButton>(com.github.andreyasadchy.xtra.R.id.castButton)
            castButton.visibility = if (showCastButton && (isLiveStream || showCastForVod)) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    fun onLocalContentReady() {
        retryPendingCast()
        if (!isCastingCurrentContent()) return
        _castCurrentContentActive = true
        val service = fragment.playbackService ?: return
        val qualityName = service.quality?.name
        if (qualityName == VideoQuality.CHAT_ONLY_QUALITY || qualityName == VideoQuality.AUDIO_ONLY_QUALITY) return
        if (_chatOnlyUserOverride == false) return
        setLocalChatOnly(true, userOverride = _chatOnlyUserOverride)
        fragment.pauseLocalPlayback()
    }

    private fun playCurrentStreamOnCastDevice() {
        if (castPlayInProgress.get()) {
            showCastToast(com.github.andreyasadchy.xtra.R.string.cast_play_stream_in_progress)
            return
        }
        if (!isCastConnected()) {
            showCastToast(com.github.andreyasadchy.xtra.R.string.cast_not_connected)
            return
        }
        if (!requestCastCurrentStream()) {
            showCastToast(com.github.andreyasadchy.xtra.R.string.cast_play_stream_not_ready)
        }
    }

    private fun requestCastCurrentStream(): Boolean {
        val service = fragment.playbackService ?: return false
        val targetType = service.type
        val targetChannelId = service.channelId
        val targetVideoId = service.videoId
        val targetClipId = service.clipId
        castPendingOnConnect = false
        val resolved = resolveCastableQuality()
        val url = resolved?.url
        if (resolved == null || url.isNullOrBlank()) {
            if (castPendingType != targetType || castPendingChannelId != targetChannelId ||
                castPendingVideoId != targetVideoId || castPendingClipId != targetClipId) {
                castRetryCount = 0
            }
            castPendingType = targetType
            castPendingChannelId = targetChannelId
            castPendingVideoId = targetVideoId
            castPendingClipId = targetClipId
            scheduleCastRetry()
            return false
        }
        castPendingType = null
        castPendingChannelId = null
        castPendingVideoId = null
        castPendingClipId = null
        if (castPlayInProgress.get()) return true
        castPlayInProgress.set(true)
        castManager?.loadStream(
            url,
            service.title,
            service.channelName,
            service.thumbnail,
            currentTimeMs = null,
            isLive = service.type == BasePlaybackService.STREAM,
            durationMs = fragment.playbackService?.durationSeconds?.toLong()?.times(1000),
        ) { success ->
            castPlayInProgress.set(false)
            if (success) {
                _castQuality = resolved
                castManager?.setCastedContent(targetType, targetChannelId, targetVideoId, targetClipId)
                _castCurrentContentActive = true
                onLocalContentReady()
                fragment.setQualityText()
            } else {
                showCastToast(com.github.andreyasadchy.xtra.R.string.cast_play_stream_failed)
            }
        }
        return true
    }

    private fun resolveCastableQuality(): VideoQuality? {
        return resolveCastQuality(fragment.validLocalVideoQuality())
            ?: fragment.playbackService?.qualities?.firstOrNull(::validCastQuality)
    }

    private fun scheduleCastRetry() {
        castRetryHandler.removeCallbacks(castRetryAction)
        if (castRetryCount >= CAST_RETRY_LIMIT) {
            cancelPendingCast()
            return
        }
        castRetryCount++
        castRetryHandler.postDelayed(castRetryAction, CAST_RETRY_DELAY)
    }

    private fun retryPendingCast() {
        if (castPlayInProgress.get()) return
        if (!isCastConnected()) {
            cancelPendingCast()
            return
        }
        if (castPendingOnConnect) {
            if (castManager?.hasCastedContent() == true) {
                cancelPendingCast()
                return
            }
            if (fragment.playbackService == null) {
                scheduleCastRetry()
                return
            }
            requestCastCurrentStream()
            return
        }
        if (castPendingType == null) return
        val service = fragment.playbackService ?: run {
            scheduleCastRetry()
            return
        }
        if (castPendingType != service.type || castPendingChannelId != service.channelId ||
            castPendingVideoId != service.videoId || castPendingClipId != service.clipId) {
            cancelPendingCast()
            return
        }
        requestCastCurrentStream()
    }

    private fun cancelPendingCast() {
        castRetryHandler.removeCallbacks(castRetryAction)
        castRetryCount = 0
        castPendingType = null
        castPendingChannelId = null
        castPendingVideoId = null
        castPendingClipId = null
        castPendingOnConnect = false
    }

    private fun onCastConnected() {
        castManager?.setControlsEnabled(true)
        val service = fragment.playbackService
        if (castManager?.hasCastedContent() == true) {
            if (service != null && isCastingCurrentContent()) {
                onLocalContentReady()
            } else if (service != null) {
                _localVideoQuality = service.quality?.takeIf { it.name != VideoQuality.CHAT_ONLY_QUALITY }
                    ?: fragment.validLocalVideoQuality()
            }
            return
        }
        if (service == null) {
            castPendingOnConnect = true
            scheduleCastRetry()
            return
        }
        _localVideoQuality = service.quality?.takeIf { it.name != VideoQuality.CHAT_ONLY_QUALITY }
            ?: fragment.validLocalVideoQuality()
        requestCastCurrentStream()
    }

    private fun resolveCastQuality(selectedQuality: VideoQuality?): VideoQuality? {
        val qualities = fragment.playbackService?.qualities
        return if (selectedQuality?.name == VideoQuality.AUTO_QUALITY) {
            qualities?.firstOrNull(::validCastQuality)
        } else {
            selectedQuality?.takeIf { it.name != VideoQuality.CHAT_ONLY_QUALITY && !it.url.isNullOrBlank() }
        }
    }

    private fun validCastQuality(q: VideoQuality): Boolean =
        q.name != VideoQuality.AUTO_QUALITY &&
            q.name != VideoQuality.AUDIO_ONLY_QUALITY &&
            q.name != VideoQuality.CHAT_ONLY_QUALITY &&
            !q.url.isNullOrBlank()

    private fun isCastConnected(): Boolean {
        return try {
            castManager?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    fun isCastingCurrentContent(): Boolean {
        return try {
            castManager?.isCastingContent(
                fragment.playbackService?.type,
                fragment.playbackService?.channelId,
                fragment.playbackService?.videoId,
                fragment.playbackService?.clipId,
            ) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun onCastDisconnected() {
        val wasCastingCurrent = _castCurrentContentActive || isCastingCurrentContent()
        val shouldRestoreChatOnly = _chatOnlyEnabledByCast && _chatOnlyUserOverride != false
        _castCurrentContentActive = false
        cancelPendingCast()
        _castQuality = null
        castManager?.clearCastedContent()
        castManager?.setControlsEnabled(false)
        if (wasCastingCurrent) {
            if (shouldRestoreChatOnly) {
                _chatOnlyEnabled = false
                _chatOnlyEnabledByCast = false
                setLocalChatOnly(false, userOverride = null)
            }
            fragment.resumeLocalPlayback()
        }
    }

    fun setLocalChatOnly(enabled: Boolean) {
        setLocalChatOnly(enabled, userOverride = if (isCastingCurrentContent()) enabled else null)
    }

    private fun setLocalChatOnly(enabled: Boolean, userOverride: Boolean?) {
        _chatOnlyEnabled = enabled
        _chatOnlyEnabledByCast = enabled && userOverride == null
        _chatOnlyUserOverride = userOverride
        if (enabled) {
            if (fragment.playbackService?.quality?.name != VideoQuality.CHAT_ONLY_QUALITY) {
                _localVideoQuality = fragment.playbackService?.quality?.takeIf { it.name != VideoQuality.CHAT_ONLY_QUALITY }
                    ?: _localVideoQuality
                fragment.changeQuality(fragment.playbackService?.qualities?.find { it.name == VideoQuality.CHAT_ONLY_QUALITY })
                fragment.changePlayerMode()
                fragment.setQualityText()
            }
        } else {
            if (fragment.playbackService?.quality?.name == VideoQuality.CHAT_ONLY_QUALITY) {
                val restoreQuality = fragment.validLocalVideoQuality()
                if (restoreQuality != null) {
                    fragment.changeQuality(restoreQuality)
                    fragment.changePlayerMode()
                    fragment.setQualityText()
                    if (!isCastConnected()) {
                        fragment.resumeLocalPlayback()
                    }
                }
            }
        }
    }

    private fun showCastToast(resId: Int) {
        if (fragment.view != null) {
            Toast.makeText(fragment.requireContext(), resId, Toast.LENGTH_SHORT).show()
        }
    }
}
