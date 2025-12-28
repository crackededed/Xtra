package com.github.andreyasadchy.xtra.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateUtils
import android.util.Base64
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MpvFragment : PlayerFragment() {

    private var playbackService: MpvService? = null
    private var serviceConnection: ServiceConnection? = null
    private var surfaceHolderCallback: SurfaceHolder.Callback? = null
    private var surfaceCreated = false
    private var playerListener: MPVLib.EventObserver? = null
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onStart() {
        super.onStart()
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (playbackService != null) {
                    MPVLib.setPropertyString("android-surface-size", "${width}x$height")
                }
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceCreated = true
                if (viewModel.started && binding.playerSurface.isVisible && playbackService != null) {
                    MPVLib.attachSurface(holder.surface)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceCreated = false
                if (playbackService != null) {
                    MPVLib.detachSurface()
                }
            }
        }
        binding.playerSurface.holder.addCallback(callback)
        surfaceHolderCallback = callback
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (view != null) {
                    val binder = service as MpvService.ServiceBinder
                    playbackService = binder.getService()
                    if (surfaceCreated && binding.playerSurface.isVisible) {
                        MPVLib.attachSurface(binding.playerSurface.holder.surface)
                    }
                    val listener = object : MPVLib.EventObserver {
                        override fun eventProperty(property: String) {}
                        override fun eventProperty(property: String, value: Long) {}

                        override fun eventProperty(property: String, value: Boolean) {
                            activity?.runOnUiThread {
                                if (playbackService != null) {
                                    when (property) {
                                        "pause" -> {
                                            if (value) {
                                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                                                binding.playerControls.playPause.visible()
                                            } else {
                                                binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                                                if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                                                    binding.playerControls.playPause.gone()
                                                }
                                            }
                                            setPipActions(!value)
                                            updateProgress()
                                            if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                                                requireView().keepScreenOn = !value
                                            }
                                            controllerAutoHide = !value
                                            if (videoType != STREAM && useController) {
                                                showController()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        override fun eventProperty(property: String, value: String) {}

                        override fun eventProperty(property: String, value: Double) {
                            activity?.runOnUiThread {
                                if (playbackService != null) {
                                    when (property) {
                                        "duration" -> {
                                            val duration = (value * 1000).toLong()
                                            binding.playerControls.progressBar.setDuration(duration)
                                            binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                                            updateProgress()
                                        }
                                        "speed" -> {
                                            chatFragment?.updateSpeed(value.toFloat())
                                        }
                                    }
                                }
                            }
                        }

                        override fun eventProperty(property: String, value: MPVNode) {}

                        override fun event(eventId: Int) {
                            activity?.runOnUiThread {
                                if (playbackService != null) {
                                    when (eventId) {
                                        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                                            chatFragment?.startReplayChatLoad()
                                        }
                                        MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                                            updateProgress()
                                            MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong()?.let { chatFragment?.updatePosition(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    MPVLib.addObserver(listener)
                    MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                    MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                    MPVLib.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                    playerListener = listener
                    if (viewModel.restoreQuality) {
                        viewModel.restoreQuality = false
                        changeQuality(viewModel.previousQuality)
                    }
                    val endTime = playbackService?.setSleepTimer(-1)
                    if (endTime != null && endTime > 0L) {
                        val duration = endTime - System.currentTimeMillis()
                        if (duration > 0L) {
                            (activity as? MainActivity)?.setSleepTimer(duration)
                        } else {
                            minimize()
                            close()
                            (activity as? MainActivity)?.closePlayer()
                        }
                    }
                    if (viewModel.resume) {
                        viewModel.resume = false
                        MPVLib.setPropertyBoolean("pause", false)
                    }
                    val paused = MPVLib.getPropertyBoolean("pause") == true
                    if (viewModel.loaded.value && MPVLib.getPropertyString("path") == null) {
                        viewModel.started = false
                    }
                    if (viewModel.started && MPVLib.getPropertyString("path") != null) {
                        chatFragment?.startReplayChatLoad()
                    }
                    if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                        requireView().keepScreenOn = !paused
                    }
                    updateProgress()
                    if (paused) {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                        binding.playerControls.playPause.visible()
                    } else {
                        binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                        if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                            binding.playerControls.playPause.gone()
                        }
                    }
                    if ((isInitialized || !enableNetworkCheck) && !viewModel.started) {
                        startPlayer()
                    }
                    setPipActions(!paused)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService = null
            }
        }
        serviceConnection = connection
        val intent = Intent(requireContext(), MpvService::class.java)
        requireContext().startService(intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun initialize() {
        if (playbackService != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    override fun startStream(url: String?) {
        if (playbackService != null && url != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                playbackService?.videoId = null
                playbackService?.offlineVideoId = null
                playbackService?.title = requireArguments().getString(KEY_TITLE)
                playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
                val response = viewModel.loadPlaylist(
                    url = url,
                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                    proxyMultivariantPlaylist = prefs.getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false),
                    proxyHost = prefs.getString(C.PROXY_HOST, null),
                    proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                    proxyUser = prefs.getString(C.PROXY_USER, null),
                    proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                )
                val playlist = response?.first
                val responseCode = response?.second
                if (responseCode != null && requireContext().isNetworkAvailable) {
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
                if (!playlist.isNullOrBlank()) {
                    val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList().ifEmpty {
                        Regex("NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                    }
                    val codecStrings = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                    val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                    val codecs = codecStrings.map { codec ->
                        codec.substringBefore('.').let {
                            when (it) {
                                "av01" -> "AV1"
                                "hev1" -> "H.265"
                                "avc1" -> "H.264"
                                else -> it
                            }
                        }
                    }.takeUnless { it.all { it == "H.264" || it == "mp4a" } }
                    if (names.isNotEmpty() && urls.isNotEmpty()) {
                        val map = mutableMapOf<String, Pair<String, String?>>()
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
                            .toMap()
                        setDefaultQuality()
                        viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                            quality.value.second?.let { MPVLib.command("loadfile", it) }
                        }
                        viewModel.loaded.value = true
                    }
                }
                MPVLib.setPropertyInt("volume", prefs.getInt(C.PLAYER_VOLUME, 100))
                MPVLib.setPropertyFloat("speed", 1f)
            }
        }
    }

    override fun startVideo(url: String?, playbackPosition: Long?, multivariantPlaylist: Boolean) {
        if (playbackService != null && url != null) {
            if (multivariantPlaylist) {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (surfaceCreated) {
                        MPVLib.attachSurface(binding.playerSurface.holder.surface)
                    }
                    binding.playerSurface.visible()
                    val newId = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                    val position = if (playbackService?.videoId == newId && MPVLib.getPropertyString("path") != null) {
                        MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
                    } else {
                        playbackPosition ?: 0
                    }
                    playbackService?.videoId = newId
                    playbackService?.offlineVideoId = null
                    playbackService?.title = requireArguments().getString(KEY_TITLE)
                    playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                    playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
                    val response = viewModel.loadPlaylist(url, prefs.getString(C.NETWORK_LIBRARY, "OkHttp"))
                    val playlist = response?.first
                    val responseCode = response?.second
                    if (responseCode != null && requireContext().isNetworkAvailable) {
                        val skipAccessToken = prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                        when {
                            skipAccessToken == 1 && viewModel.shouldRetry && responseCode != 0 -> {
                                viewModel.shouldRetry = false
                                playVideo(false, MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong())
                            }
                            skipAccessToken == 2 && viewModel.shouldRetry && responseCode != 0 -> {
                                viewModel.shouldRetry = false
                                playVideo(true, MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong())
                            }
                            responseCode == 403 -> {
                                requireContext().toast(R.string.video_subscribers_only)
                            }
                        }
                    }
                    if (!playlist.isNullOrBlank()) {
                        val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList().ifEmpty {
                            Regex("NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                        }
                        val codecStrings = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                        val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                        playlist.lines().filter { it.startsWith("#EXT-X-SESSION-DATA") }.let { list ->
                            if (list.isNotEmpty()) {
                                val url = urls.firstOrNull()?.takeIf { it.contains("/index-") }
                                val variantId = Regex("STABLE-VARIANT-ID=\"(.+?)\"").find(playlist)?.groups?.get(1)?.value
                                if (url != null && variantId != null) {
                                    list.forEach { line ->
                                        val id = Regex("DATA-ID=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                                        if (id == "com.amazon.ivs.unavailable-media") {
                                            val value = Regex("VALUE=\"(.+?)\"").find(line)?.groups?.get(1)?.value
                                            if (value != null) {
                                                val bytes = try {
                                                    Base64.decode(value, Base64.DEFAULT)
                                                } catch (e: IllegalArgumentException) {
                                                    null
                                                }
                                                if (bytes != null) {
                                                    val string = String(bytes)
                                                    val array = try {
                                                        JSONArray(string)
                                                    } catch (e: JSONException) {
                                                        null
                                                    }
                                                    if (array != null) {
                                                        for (i in 0 until array.length()) {
                                                            val obj = array.optJSONObject(i)
                                                            if (obj != null) {
                                                                var skip = false
                                                                val filterReasons = obj.optJSONArray("FILTER_REASONS")
                                                                if (filterReasons != null) {
                                                                    for (filterIndex in 0 until filterReasons.length()) {
                                                                        val filter = filterReasons.optString(filterIndex)
                                                                        if (filter == "FR_CODEC_NOT_REQUESTED") {
                                                                            skip = true
                                                                            break
                                                                        }
                                                                    }
                                                                }
                                                                if (!skip) {
                                                                    val name = obj.optString("IVS_NAME")
                                                                    val codec = obj.optString("CODECS")
                                                                    val newVariantId = obj.optString("STABLE-VARIANT-ID")
                                                                    if (!name.isNullOrBlank() && !newVariantId.isNullOrBlank()) {
                                                                        names.add(name)
                                                                        if (!codec.isNullOrBlank()) {
                                                                            codecStrings.add(codec)
                                                                        }
                                                                        urls.add(url.replace(
                                                                            "$variantId/index-",
                                                                            if (urls.find { it.contains("chunked/index-") } == null && newVariantId != "audio_only") {
                                                                                "chunked/index-"
                                                                            } else {
                                                                                "$newVariantId/index-"
                                                                            }
                                                                        ))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val codecs = codecStrings.map { codec ->
                            codec.substringBefore('.').let {
                                when (it) {
                                    "av01" -> "AV1"
                                    "hev1" -> "H.265"
                                    "avc1" -> "H.264"
                                    else -> it
                                }
                            }
                        }.takeUnless { it.all { it == "H.264" || it == "mp4a" } }
                        if (names.isNotEmpty() && urls.isNotEmpty()) {
                            val map = mutableMapOf<String, Pair<String, String?>>()
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
                                .toMap()
                            setDefaultQuality()
                            changePlayerMode()
                            viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                                quality.value.second?.let { MPVLib.command("loadfile", it) }
                            }
                            viewModel.loaded.value = true
                        }
                    }
                    MPVLib.setPropertyInt("volume", prefs.getInt(C.PLAYER_VOLUME, 100))
                    MPVLib.setPropertyFloat("speed", prefs.getFloat(C.PLAYER_SPEED, 1f))
                    playbackService?.seekPosition = position
                }
            } else {
                if (surfaceCreated) {
                    MPVLib.attachSurface(binding.playerSurface.holder.surface)
                }
                binding.playerSurface.visible()
                val newId = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                val position = if (playbackService?.videoId == newId && MPVLib.getPropertyString("path") != null) {
                    MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
                } else {
                    playbackPosition ?: 0
                }
                playbackService?.videoId = newId
                playbackService?.offlineVideoId = null
                playbackService?.title = requireArguments().getString(KEY_TITLE)
                playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
                MPVLib.command("loadfile", url)
                MPVLib.setPropertyInt("volume", prefs.getInt(C.PLAYER_VOLUME, 100))
                MPVLib.setPropertyFloat("speed", prefs.getFloat(C.PLAYER_SPEED, 1f))
                playbackService?.seekPosition = position
                viewModel.loaded.value = true
            }
        }
    }

    override fun startClip(url: String?) {
        if (playbackService != null && url != null) {
            val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
            if (quality?.key == AUDIO_ONLY_QUALITY) {
                MPVLib.detachSurface()
                binding.playerSurface.gone()
            } else {
                if (surfaceCreated) {
                    MPVLib.attachSurface(binding.playerSurface.holder.surface)
                }
                binding.playerSurface.visible()
            }
            playbackService?.videoId = null
            playbackService?.offlineVideoId = null
            playbackService?.title = requireArguments().getString(KEY_TITLE)
            playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
            playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
            MPVLib.command("loadfile", url)
            viewModel.loaded.value = true
            MPVLib.setPropertyInt("volume", prefs.getInt(C.PLAYER_VOLUME, 100))
            MPVLib.setPropertyFloat("speed", prefs.getFloat(C.PLAYER_SPEED, 1f))
        }
    }

    override fun startOfflineVideo(url: String?, position: Long) {
        if (playbackService != null && url != null) {
            val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
            if (quality?.key == AUDIO_ONLY_QUALITY) {
                MPVLib.detachSurface()
                binding.playerSurface.gone()
            } else {
                if (surfaceCreated) {
                    MPVLib.attachSurface(binding.playerSurface.holder.surface)
                }
                binding.playerSurface.visible()
            }
            val newId = requireArguments().getInt(KEY_OFFLINE_VIDEO_ID).takeIf { it != 0 }
            val position = if (playbackService?.offlineVideoId == newId && MPVLib.getPropertyString("path") != null) {
                MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
            } else {
                position
            }
            playbackService?.videoId = null
            playbackService?.offlineVideoId = newId
            playbackService?.title = requireArguments().getString(KEY_TITLE)
            playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
            playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
            MPVLib.command("loadfile", url)
            viewModel.loaded.value = true
            MPVLib.setPropertyInt("volume", prefs.getInt(C.PLAYER_VOLUME, 100))
            MPVLib.setPropertyFloat("speed", prefs.getFloat(C.PLAYER_SPEED, 1f))
            playbackService?.seekPosition = position
        }
    }

    override fun getCurrentPosition() = if (playbackService != null) {
        MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong()
    } else null

    override fun getCurrentSpeed() = if (playbackService != null) {
        MPVLib.getPropertyFloat("speed")
    } else null

    override fun getCurrentVolume() = if (playbackService != null) {
        MPVLib.getPropertyInt("volume")?.div(100f)
    } else null

    override fun playPause() {
        if (playbackService != null) {
            val paused = MPVLib.getPropertyBoolean("pause") == true
            MPVLib.setPropertyBoolean("pause", !paused)
            if (MPVLib.getPropertyBoolean("eof-reached") == true) {
                MPVLib.command("seek", "0", "absolute")
            }
        }
    }

    override fun rewind() {
        if (playbackService != null) {
            val rewindMs = prefs.getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
            MPVLib.command("seek", (-(rewindMs / 1000.0)).toString(), "relative+exact")
        }
    }

    override fun fastForward() {
        if (playbackService != null) {
            val fastForwardMs = prefs.getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
            MPVLib.command("seek", (fastForwardMs / 1000.0).toString(), "relative+exact")
        }
    }

    override fun seek(position: Long) {
        if (playbackService != null) {
            MPVLib.command("seek", (position / 1000.0).toString(), "absolute")
        }
    }

    override fun seekToLivePosition() {
        if (playbackService != null) {
            MPVLib.command("seek", "100", "absolute-percent+exact")
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (playbackService != null) {
            MPVLib.setPropertyFloat("speed", speed)
        }
    }

    override fun changeVolume(volume: Float) {
        if (playbackService != null) {
            MPVLib.setPropertyInt("volume", (volume * 100).toInt())
        }
    }

    override fun updateProgress() {
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed && playbackService != null) {
                val currentPosition = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong() ?: 0
                position.text = DateUtils.formatElapsedTime(currentPosition / 1000)
                progressBar.setPosition(currentPosition)
                root.removeCallbacks(updateProgressAction)
                if (MPVLib.getPropertyBoolean("pause") == false) {
                    val speed = MPVLib.getPropertyFloat("speed") ?: 1f
                    val delay = if (speed > 0f) {
                        (progressBar.preferredUpdateDelay / speed).toLong().coerceIn(200L..1000L)
                    } else {
                        1000
                    }
                    root.postDelayed(updateProgressAction, delay)
                }
            }
        }
    }

    override fun changeQuality(selectedQuality: String?) {
        viewModel.previousQuality = viewModel.quality
        viewModel.quality = selectedQuality
        viewModel.qualities.entries.find { it.key == selectedQuality }?.let { quality ->
            if (playbackService != null) {
                when (quality.key) {
                    AUDIO_ONLY_QUALITY -> {
                        MPVLib.detachSurface()
                        binding.playerSurface.gone()
                        quality.value.second?.let {
                            val position = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong()
                            MPVLib.command("loadfile", it)
                            playbackService?.seekPosition = position
                        }
                    }
                    CHAT_ONLY_QUALITY -> {
                        MPVLib.setPropertyBoolean("pause", true)
                    }
                    else -> {
                        if (MPVLib.getPropertyString("path") != quality.value.second) {
                            quality.value.second?.let {
                                val position = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong()
                                MPVLib.command("loadfile", it)
                                playbackService?.seekPosition = position
                            }
                        }
                        if (surfaceCreated) {
                            MPVLib.attachSurface(binding.playerSurface.holder.surface)
                        }
                        binding.playerSurface.visible()
                    }
                }
                val cellular = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                } else {
                    false
                }
                if ((!cellular && prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") || (cellular && prefs.getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                    prefs.edit { putString(C.PLAYER_QUALITY, quality.key) }
                }
            }
        }
    }

    override fun startAudioOnly() {
        if (playbackService != null) {
            savePosition()
            if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                viewModel.restoreQuality = true
                viewModel.previousQuality = viewModel.quality
                viewModel.quality = AUDIO_ONLY_QUALITY
                viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                    if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                        MPVLib.detachSurface()
                        binding.playerSurface.gone()
                    }
                    if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                        quality.value.second?.let {
                            val position = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong()
                            MPVLib.command("loadfile", it)
                            playbackService?.seekPosition = position
                        }
                    }
                }
            }
            playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
        }
        playerListener?.let {
            if (playbackService != null) {
                MPVLib.removeObserver(it)
            }
        }
        playerListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun downloadVideo() {
        val totalDuration = if (playbackService != null) {
            MPVLib.getPropertyDouble("duration/full")?.times(1000)?.toLong()
        } else null
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
            currentPosition = getCurrentPosition(),
            qualityKeys = qualities.keys.toTypedArray(),
            qualityNames = qualities.map { it.value.first }.toTypedArray(),
            qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
        ).show(childFragmentManager, null)
    }

    override fun close() {
        savePosition()
        playerListener?.let {
            if (playbackService != null) {
                MPVLib.removeObserver(it)
            }
        }
        playerListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    override fun onStop() {
        super.onStop()
        if (playbackService != null) {
            savePosition()
            val isInteractive = (requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            val paused = MPVLib.getPropertyBoolean("pause") == true
            if ((!isInPIPMode && isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO, true))
                || (!isInPIPMode && !isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, true))
                || (isInPIPMode && isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED, false))
                || (isInPIPMode && !isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED, true))) {
                if (!paused && viewModel.quality != AUDIO_ONLY_QUALITY) {
                    viewModel.restoreQuality = true
                    viewModel.previousQuality = viewModel.quality
                    viewModel.quality = AUDIO_ONLY_QUALITY
                    viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                        if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                            MPVLib.detachSurface()
                            binding.playerSurface.gone()
                        }
                        if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                            quality.value.second?.let {
                                val position = MPVLib.getPropertyDouble("time-pos/full")?.times(1000)?.toLong()
                                MPVLib.command("loadfile", it)
                                playbackService?.seekPosition = position
                            }
                        }
                    }
                }
            } else {
                viewModel.resume = !paused
                MPVLib.setPropertyBoolean("pause", true)
            }
            playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playerListener?.let {
            if (playbackService != null) {
                MPVLib.removeObserver(it)
            }
        }
        playerListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (videoType == STREAM) {
                restartPlayer()
            }
        }
    }

    companion object {
        fun newInstance(item: Stream): MpvFragment {
            return MpvFragment().apply {
                arguments = getStreamArguments(item)
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean): MpvFragment {
            return MpvFragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition)
            }
        }

        fun newInstance(item: Clip): MpvFragment {
            return MpvFragment().apply {
                arguments = getClipArguments(item)
            }
        }

        fun newInstance(item: OfflineVideo): MpvFragment {
            return MpvFragment().apply {
                arguments = getOfflineVideoArguments(item)
            }
        }
    }
}