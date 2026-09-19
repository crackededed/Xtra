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

class PlayerCastController(private val fragment: PlayerFragment) {

    private var castSessionListener: SessionManagerListener<CastSession>? = null
    internal var castManager: CastManager? = null
    private var _castQuality: VideoQuality? = null
    private var _localVideoQuality: VideoQuality? = null
    private var _chatOnlyEnabled = false
    private var _chatOnlyEnabledByCast = false
    private var castStreamController: CastStreamController? = null

    val castQuality: VideoQuality?
        get() = _castQuality

    var localVideoQuality: VideoQuality?
        get() = _localVideoQuality
        set(value) {
            _localVideoQuality = value
        }

    val chatOnlyEnabled: Boolean
        get() = _chatOnlyEnabled
    private var castPlayInProgress = false
    private var castPendingTarget: CastTarget? = null
    private var castPendingOnConnect = false
    private var castRetryCount = 0
    private val castRetryHandler = Handler(Looper.getMainLooper())
    private val castRetryAction = Runnable { retryPendingCast() }
    private val castPlayRequestAction: () -> Unit = { playCurrentStreamOnCastDevice() }

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
            castStreamController = CastStreamController(manager)
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
        castStreamController?.changeQuality(
            url,
            CastStreamController.StreamMetadata(
                title = service.title,
                channelName = service.channelName,
                thumbnail = service.thumbnail,
            ),
            isLive = isLive,
            durationMs = durationMs,
        ) { success ->
            if (success && fragment.view != null) {
                _castQuality = resolved
                fragment.setQualityText()
            }
        }
    }

    fun cleanup() {
        _castQuality = null
        cancelPendingCast()
        val manager = castManager
        if (manager?.playCurrentRequest === castPlayRequestAction) {
            manager.playCurrentRequest = null
        }
        try {
            castSessionListener?.let { manager?.removeConnectionCallback(it) }
        } catch (_: Exception) {
        }
        castSessionListener = null
        castManager = null
        castStreamController?.setErrorListener(null)
        castStreamController = null
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
        val service = fragment.playbackService ?: return
        val qualityName = service.quality?.name
        if (qualityName == VideoQuality.CHAT_ONLY_QUALITY || qualityName == VideoQuality.AUDIO_ONLY_QUALITY) return
        if (!_chatOnlyEnabled) {
            _chatOnlyEnabled = true
            _chatOnlyEnabledByCast = true
        }
        setLocalChatOnly(true)
        fragment.pauseLocalPlayback()
    }

    private fun playCurrentStreamOnCastDevice() {
        if (castPlayInProgress) {
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
        val controller = castStreamController ?: return false
        val target = CastTarget(service.type, service.channelId, service.videoId, service.clipId)
        castPendingOnConnect = false
        val resolved = resolveCastableQuality()
        val url = resolved?.url
        if (resolved == null || url.isNullOrBlank()) {
            if (castPendingTarget != target) {
                castRetryCount = 0
            }
            castPendingTarget = target
            scheduleCastRetry()
            return false
        }
        castPendingTarget = null
        castRetryCount = 0
        if (castPlayInProgress) return true
        castPlayInProgress = true
        controller.play(
            url,
            CastStreamController.StreamMetadata(
                title = service.title,
                channelName = service.channelName,
                thumbnail = service.thumbnail,
            ),
            isLive = service.type != BasePlaybackService.STREAM,
            durationMs = fragment.playbackService?.durationSeconds?.toLong()?.times(1000),
        ) { success ->
            castPlayInProgress = false
            if (success) {
                _castQuality = resolved
                castManager?.setCastedContent(target.type, target.channelId, target.videoId, target.clipId)
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
            ?: fragment.playbackService?.qualities?.firstOrNull {
                it.name != VideoQuality.AUTO_QUALITY &&
                    it.name != VideoQuality.AUDIO_ONLY_QUALITY &&
                    it.name != VideoQuality.CHAT_ONLY_QUALITY &&
                    !it.url.isNullOrBlank()
            }
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
        if (castPlayInProgress) return
        if (!isCastConnected()) {
            cancelPendingCast()
            return
        }
        if (castPendingOnConnect) {
            if (castManager?.hasCastedContent() == true) {
                cancelPendingCast()
                return
            }
            if (fragment.playbackService == null || castStreamController == null) {
                scheduleCastRetry()
                return
            }
            requestCastCurrentStream()
            return
        }
        val target = castPendingTarget ?: return
        val service = fragment.playbackService ?: run {
            scheduleCastRetry()
            return
        }
        if (target != CastTarget(service.type, service.channelId, service.videoId, service.clipId)) {
            cancelPendingCast()
            return
        }
        requestCastCurrentStream()
    }

    private fun cancelPendingCast() {
        castRetryHandler.removeCallbacks(castRetryAction)
        castRetryCount = 0
        castPendingTarget = null
        castPendingOnConnect = false
    }

    private fun onCastConnected() {
        castManager?.setControlsEnabled(true)
        val service = fragment.playbackService
        if (castManager?.hasCastedContent() == true) {
            if (service != null && isCastingCurrentContent()) {
                onLocalContentReady()
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
            qualities?.firstOrNull {
                it.name != VideoQuality.AUTO_QUALITY &&
                    it.name != VideoQuality.AUDIO_ONLY_QUALITY &&
                    it.name != VideoQuality.CHAT_ONLY_QUALITY &&
                    !it.url.isNullOrBlank()
            }
        } else {
            selectedQuality?.takeIf { it.name != VideoQuality.CHAT_ONLY_QUALITY && !it.url.isNullOrBlank() }
        }
    }

    fun isCastConnected(): Boolean {
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
        val wasCastingCurrent = isCastingCurrentContent()
        cancelPendingCast()
        _castQuality = null
        castManager?.clearCastedContent()
        castManager?.setControlsEnabled(false)
        if (wasCastingCurrent) {
            if (_chatOnlyEnabledByCast) {
                _chatOnlyEnabled = false
                _chatOnlyEnabledByCast = false
                setLocalChatOnly(false)
            }
            fragment.resumeLocalPlayback()
        }
    }

    fun setLocalChatOnly(enabled: Boolean) {
        if (enabled) {
            if (fragment.playbackService?.quality?.name != VideoQuality.CHAT_ONLY_QUALITY) {
                _localVideoQuality = fragment.playbackService?.quality?.takeIf { it.name != VideoQuality.CHAT_ONLY_QUALITY }
                    ?: _localVideoQuality
                fragment.changeQuality(fragment.playbackService?.qualities?.find { it.name == VideoQuality.CHAT_ONLY_QUALITY })
                fragment.setQualityText()
            }
        } else {
            if (fragment.playbackService?.quality?.name == VideoQuality.CHAT_ONLY_QUALITY) {
                val restoreQuality = fragment.validLocalVideoQuality()
                if (restoreQuality != null) {
                    fragment.changeQuality(restoreQuality)
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

    private data class CastTarget(
        val type: String?,
        val channelId: String?,
        val videoId: String?,
        val clipId: String?,
    )
}