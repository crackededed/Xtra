package com.github.andreyasadchy.xtra.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateUtils
import android.util.Base64
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MediaPlayerFragment : PlayerFragment() {

    private var playbackService: MediaPlayerService? = null
    private var serviceConnection: ServiceConnection? = null
    private val player: MediaPlayer?
        get() = playbackService?.player
    private var surfaceHolderCallback: SurfaceHolder.Callback? = null
    private var surfaceCreated = false
    private val updateProgressAction = Runnable { if (view != null) updateProgress() }

    override fun onStart() {
        super.onStart()
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceCreated = true
                if (viewModel.started && binding.playerSurface.isVisible) {
                    player?.setDisplay(binding.playerSurface.holder)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceCreated = false
            }
        }
        binding.playerSurface.holder.addCallback(callback)
        surfaceHolderCallback = callback
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (view != null) {
                    val binder = service as MediaPlayerService.ServiceBinder
                    playbackService = binder.getService()
                    if (surfaceCreated && binding.playerSurface.isVisible) {
                        player?.setDisplay(binding.playerSurface.holder)
                    }
                    playbackService?.preparedListener = MediaPlayer.OnPreparedListener { player ->
                        val duration = player.duration.takeIf { it != -1 }?.toLong() ?: 0
                        binding.playerControls.progressBar.setDuration(duration)
                        binding.playerControls.duration.text = DateUtils.formatElapsedTime(duration / 1000)
                        updatePlayingState()
                        chatFragment?.startReplayChatLoad()
                    }
                    playbackService?.seekCompleteListener = MediaPlayer.OnSeekCompleteListener {
                        updatePlayingState()
                        player?.currentPosition?.toLong()?.let { chatFragment?.updatePosition(it) }
                    }
                    playbackService?.completionListener = MediaPlayer.OnCompletionListener {
                        updatePlayingState()
                    }
                    playbackService?.infoListener = MediaPlayer.OnInfoListener { _, what, _ ->
                        when (what) {
                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> binding.bufferingIndicator.visibility = View.VISIBLE
                            MediaPlayer.MEDIA_INFO_BUFFERING_END -> binding.bufferingIndicator.visibility = View.GONE
                        }
                        return@OnInfoListener true
                    }
                    playbackService?.errorListener = MediaPlayer.OnErrorListener { _, _, _ ->
                        updatePlayingState()
                        return@OnErrorListener true
                    }
                    playbackService?.pauseListener = {
                        updatePlayingState()
                    }
                    playbackService?.speedListener = { speed ->
                        chatFragment?.updateSpeed(speed)
                    }
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
                        player?.start()
                    }
                    player?.let { player ->
                        if (viewModel.loaded.value && player.trackInfo.isEmpty()) {
                            viewModel.started = false
                        }
                        if (viewModel.started && player.duration != -1) {
                            chatFragment?.startReplayChatLoad()
                        }
                        if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                            requireView().keepScreenOn = player.isPlaying
                        }
                        updateProgress()
                        if (!player.isPlaying) {
                            binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                            binding.playerControls.playPause.visibility = View.VISIBLE
                        } else {
                            binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                            if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                                binding.playerControls.playPause.visibility = View.GONE
                            }
                        }
                    }
                    if ((isInitialized || !enableNetworkCheck) && !viewModel.started) {
                        startPlayer()
                    }
                    player?.let { player ->
                        setPipActions(player.isPlaying)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService = null
            }
        }
        serviceConnection = connection
        val intent = Intent(requireContext(), MediaPlayerService::class.java)
        requireContext().startService(intent)
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun updatePlayingState() {
        player?.let { player ->
            val isPlaying = player.isPlaying
            if (!isPlaying) {
                binding.playerControls.playPause.setImageResource(R.drawable.baseline_play_arrow_black_48)
                binding.playerControls.playPause.visibility = View.VISIBLE
            } else {
                binding.playerControls.playPause.setImageResource(R.drawable.baseline_pause_black_48)
                if (videoType == STREAM && !prefs.getBoolean(C.PLAYER_PAUSE, false)) {
                    binding.playerControls.playPause.visibility = View.GONE
                }
            }
            setPipActions(isPlaying)
            controllerAutoHide = isPlaying
            if (videoType != STREAM && useController) {
                showController()
            }
            updateProgress()
            if (!prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false) && canEnterPictureInPicture()) {
                requireView().keepScreenOn = isPlaying
            }
        }
    }

    override fun initialize() {
        if (player != null && !viewModel.started) {
            startPlayer()
        }
        super.initialize()
    }

    override fun startStream(url: String?) {
        player?.let { player ->
            if (url != null) {
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
                    val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    val isNetworkAvailable = networkCapabilities != null
                            && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (responseCode != null && isNetworkAvailable) {
                        when {
                            responseCode == 404 -> {
                                Toast.makeText(requireContext(), R.string.stream_ended, Toast.LENGTH_LONG).show()
                            }
                            viewModel.useCustomProxy && responseCode >= 400 -> {
                                Toast.makeText(requireContext(), R.string.proxy_error, Toast.LENGTH_LONG).show()
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
                                Toast.makeText(requireContext(), R.string.player_error, Toast.LENGTH_SHORT).show()
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
                                            val quality = getString(R.string.source)
                                            map["source"] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                        }
                                        quality.startsWith("audio", true) -> {
                                            map[AUDIO_ONLY_QUALITY] = Pair(getString(R.string.audio_only), url)
                                        }
                                        else -> {
                                            map[quality] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                        }
                                    }
                                }
                            }
                            if (!map.containsKey(AUDIO_ONLY_QUALITY)) {
                                map[AUDIO_ONLY_QUALITY] = Pair(getString(R.string.audio_only), null)
                            }
                            if (videoType == STREAM) {
                                map[CHAT_ONLY_QUALITY] = Pair(getString(R.string.chat_only), null)
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
                                quality.value.second?.let {
                                    player.reset()
                                    player.setDataSource(it)
                                    val volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setVolume(volume, volume)
                                    val params = PlaybackParams()
                                    params.speed = 1f
                                    player.playbackParams = params
                                    player.prepareAsync()
                                    viewModel.loaded.value = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun startVideo(url: String?, playbackPosition: Long?, multivariantPlaylist: Boolean) {
        player?.let { player ->
            if (url != null) {
                if (multivariantPlaylist) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (surfaceCreated) {
                            player.setDisplay(binding.playerSurface.holder)
                        }
                        binding.playerSurface.visibility = View.VISIBLE
                        val newId = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                        val position = if (playbackService?.videoId == newId && player.duration != -1) {
                            player.currentPosition.toLong().takeIf { it > 0 } ?: playbackPosition ?: 0
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
                        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val isNetworkAvailable = networkCapabilities != null
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (responseCode != null && isNetworkAvailable) {
                            val skipAccessToken = prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2
                            when {
                                skipAccessToken == 1 && viewModel.shouldRetry && responseCode != 0 -> {
                                    viewModel.shouldRetry = false
                                    playVideo(false, player.currentPosition.toLong())
                                }
                                skipAccessToken == 2 && viewModel.shouldRetry && responseCode != 0 -> {
                                    viewModel.shouldRetry = false
                                    playVideo(true, player.currentPosition.toLong())
                                }
                                responseCode == 403 -> {
                                    Toast.makeText(requireContext(), R.string.video_subscribers_only, Toast.LENGTH_LONG).show()
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
                                                val quality = getString(R.string.source)
                                                map["source"] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                            }
                                            quality.startsWith("audio", true) -> {
                                                map[AUDIO_ONLY_QUALITY] = Pair(getString(R.string.audio_only), url)
                                            }
                                            else -> {
                                                map[quality] = Pair(codecs?.getOrNull(index)?.let { "$quality $it" } ?: quality, url)
                                            }
                                        }
                                    }
                                }
                                if (!map.containsKey(AUDIO_ONLY_QUALITY)) {
                                    map[AUDIO_ONLY_QUALITY] = Pair(getString(R.string.audio_only), null)
                                }
                                if (videoType == STREAM) {
                                    map[CHAT_ONLY_QUALITY] = Pair(getString(R.string.chat_only), null)
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
                                    quality.value.second?.let {
                                        player.reset()
                                        player.setDataSource(it)
                                        val volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                                        player.setVolume(volume, volume)
                                        val params = PlaybackParams()
                                        params.speed = prefs.getFloat(C.PLAYER_SPEED, 1f)
                                        player.playbackParams = params
                                        playbackService?.seekPosition = position
                                        player.prepareAsync()
                                        viewModel.loaded.value = true
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (surfaceCreated) {
                        player.setDisplay(binding.playerSurface.holder)
                    }
                    binding.playerSurface.visibility = View.VISIBLE
                    val newId = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                    val position = if (playbackService?.videoId == newId && player.duration != -1) {
                        player.currentPosition.toLong().takeIf { it > 0 } ?: playbackPosition ?: 0
                    } else {
                        playbackPosition ?: 0
                    }
                    playbackService?.videoId = newId
                    playbackService?.offlineVideoId = null
                    playbackService?.title = requireArguments().getString(KEY_TITLE)
                    playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                    playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
                    player.reset()
                    player.setDataSource(url)
                    val volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setVolume(volume, volume)
                    val params = PlaybackParams()
                    params.speed = prefs.getFloat(C.PLAYER_SPEED, 1f)
                    player.playbackParams = params
                    playbackService?.seekPosition = position
                    player.prepareAsync()
                    viewModel.loaded.value = true
                }
            }
        }
    }

    override fun startClip(url: String?) {
        player?.let { player ->
            if (url != null) {
                val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
                if (quality?.key == AUDIO_ONLY_QUALITY) {
                    player.setDisplay(null)
                    binding.playerSurface.visibility = View.GONE
                } else {
                    if (surfaceCreated) {
                        player.setDisplay(binding.playerSurface.holder)
                    }
                    binding.playerSurface.visibility = View.VISIBLE
                }
                playbackService?.videoId = null
                playbackService?.offlineVideoId = null
                playbackService?.title = requireArguments().getString(KEY_TITLE)
                playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
                player.reset()
                player.setDataSource(url)
                val volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                player.setVolume(volume, volume)
                val params = PlaybackParams()
                params.speed = prefs.getFloat(C.PLAYER_SPEED, 1f)
                player.playbackParams = params
                player.prepareAsync()
                viewModel.loaded.value = true
            }
        }
    }

    override fun startOfflineVideo(url: String?, position: Long) {
        player?.let { player ->
            if (url != null) {
                val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
                if (quality?.key == AUDIO_ONLY_QUALITY) {
                    player.setDisplay(null)
                    binding.playerSurface.visibility = View.GONE
                } else {
                    if (surfaceCreated) {
                        player.setDisplay(binding.playerSurface.holder)
                    }
                    binding.playerSurface.visibility = View.VISIBLE
                }
                val newId = requireArguments().getInt(KEY_OFFLINE_VIDEO_ID).takeIf { it != 0 }
                val position = if (playbackService?.offlineVideoId == newId && player.duration != -1) {
                    player.currentPosition.toLong().takeIf { it > 0 } ?: position
                } else {
                    position
                }
                playbackService?.videoId = null
                playbackService?.offlineVideoId = newId
                playbackService?.title = requireArguments().getString(KEY_TITLE)
                playbackService?.channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                playbackService?.channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO)
                player.reset()
                player.setDataSource(requireContext(), url.toUri())
                val volume = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f
                player.setVolume(volume, volume)
                val params = PlaybackParams()
                params.speed = prefs.getFloat(C.PLAYER_SPEED, 1f)
                player.playbackParams = params
                playbackService?.seekPosition = position
                player.prepareAsync()
                viewModel.loaded.value = true
            }
        }
    }

    override fun getCurrentPosition(): Long? = player?.currentPosition?.toLong()

    override fun getCurrentSpeed(): Float {
        return if (videoType == STREAM) {
            1f
        } else {
            prefs.getFloat(C.PLAYER_SPEED, 1f)
        }
    }

    override fun getCurrentVolume(): Float = prefs.getInt(C.PLAYER_VOLUME, 100) / 100f

    override fun playPause() {
        player?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.start()
            }
            playbackService?.updatePlayingState()
            updatePlayingState()
        }
    }

    override fun rewind() {
        player?.let { player ->
            val rewindMs = prefs.getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
            val position = player.currentPosition - rewindMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(position.toInt())
            }
        }
    }

    override fun fastForward() {
        player?.let { player ->
            val fastForwardMs = prefs.getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
            val position = player.currentPosition + fastForwardMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(position.toInt())
            }
        }
    }

    override fun seek(position: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player?.seekTo(position, MediaPlayer.SEEK_CLOSEST)
        } else {
            player?.seekTo(position.toInt())
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val params = PlaybackParams()
        params.speed = speed
        player?.playbackParams = params
        chatFragment?.updateSpeed(speed)
    }

    override fun changeVolume(volume: Float) {
        player?.setVolume(volume, volume)
    }

    override fun updateProgress() {
        with(binding.playerControls) {
            if (root.isVisible && !progressBar.isPressed) {
                val currentPosition = player?.currentPosition?.toLong() ?: 0
                position.text = DateUtils.formatElapsedTime(currentPosition / 1000)
                progressBar.setPosition(currentPosition)
                root.removeCallbacks(updateProgressAction)
                player?.let { player ->
                    if (player.isPlaying) {
                        val speed = player.playbackParams.speed
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
    }

    override fun toggleAudioCompressor() {
        val enabled = playbackService?.toggleDynamicsProcessing()
        if (enabled == true) {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
        } else {
            binding.playerControls.audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
        }
    }

    override fun changeQuality(selectedQuality: String?) {
        viewModel.previousQuality = viewModel.quality
        viewModel.quality = selectedQuality
        viewModel.qualities.entries.find { it.key == selectedQuality }?.let { quality ->
            player?.let { player ->
                when (quality.key) {
                    AUDIO_ONLY_QUALITY -> {
                        player.setDisplay(null)
                        binding.playerSurface.visibility = View.GONE
                        quality.value.second?.let {
                            val position = player.currentPosition.toLong()
                            player.reset()
                            if (playbackService?.offlineVideoId != null) {
                                player.setDataSource(requireContext(), it.toUri())
                            } else {
                                player.setDataSource(it)
                            }
                            playbackService?.seekPosition = position
                            player.prepareAsync()
                        }
                    }
                    CHAT_ONLY_QUALITY -> {
                        player.stop()
                        playbackService?.updatePlayingState()
                        updatePlayingState()
                    }
                    else -> {
                        quality.value.second?.let {
                            val position = player.currentPosition.toLong()
                            player.reset()
                            if (playbackService?.offlineVideoId != null) {
                                player.setDataSource(requireContext(), it.toUri())
                            } else {
                                player.setDataSource(it)
                            }
                            playbackService?.seekPosition = position
                            player.prepareAsync()
                        }
                        if (surfaceCreated) {
                            player.setDisplay(binding.playerSurface.holder)
                        }
                        binding.playerSurface.visibility = View.VISIBLE
                    }
                }
                val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                if ((!cellular && prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved") == "saved") || (cellular && prefs.getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                    prefs.edit { putString(C.PLAYER_QUALITY, quality.key) }
                }
            }
        }
    }

    override fun startAudioOnly() {
        player?.let { player ->
            if (playbackService != null) {
                savePosition()
                if (viewModel.quality != AUDIO_ONLY_QUALITY) {
                    viewModel.restoreQuality = true
                    viewModel.previousQuality = viewModel.quality
                    viewModel.quality = AUDIO_ONLY_QUALITY
                    viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                        if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                            player.setDisplay(null)
                            binding.playerSurface.visibility = View.GONE
                        }
                        if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                            quality.value.second?.let {
                                val position = player.currentPosition.toLong()
                                player.reset()
                                if (playbackService?.offlineVideoId != null) {
                                    player.setDataSource(requireContext(), it.toUri())
                                } else {
                                    player.setDataSource(it)
                                }
                                playbackService?.seekPosition = position
                                player.prepareAsync()
                            }
                        }
                    }
                }
                playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            }
        }
        playbackService?.preparedListener = null
        playbackService?.seekCompleteListener = null
        playbackService?.completionListener = null
        playbackService?.infoListener = null
        playbackService?.errorListener = null
        playbackService?.pauseListener = null
        playbackService?.speedListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService = null
    }

    override fun downloadVideo() {
        val totalDuration = player?.duration?.toLong()
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
        player?.pause()
        playbackService?.updatePlayingState()
        updatePlayingState()
        playbackService?.preparedListener = null
        playbackService?.seekCompleteListener = null
        playbackService?.completionListener = null
        playbackService?.infoListener = null
        playbackService?.errorListener = null
        playbackService?.pauseListener = null
        playbackService?.speedListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
        playbackService?.stopSelf()
        playbackService = null
    }

    override fun onStop() {
        super.onStop()
        player?.let { player ->
            if (playbackService != null) {
                savePosition()
                val isInteractive = (requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                val isInPIPMode = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                    else -> false
                }
                if ((!isInPIPMode && isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO, true))
                    || (!isInPIPMode && !isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, true))
                    || (isInPIPMode && isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED, false))
                    || (isInPIPMode && !isInteractive && prefs.getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED, true))) {
                    if (player.isPlaying && viewModel.quality != AUDIO_ONLY_QUALITY) {
                        viewModel.restoreQuality = true
                        viewModel.previousQuality = viewModel.quality
                        viewModel.quality = AUDIO_ONLY_QUALITY
                        viewModel.qualities.entries.find { it.key == viewModel.quality }?.let { quality ->
                            if (prefs.getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                                player.setDisplay(null)
                                binding.playerSurface.visibility = View.GONE
                            }
                            if (prefs.getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                                quality.value.second?.let {
                                    val position = player.currentPosition.toLong()
                                    player.reset()
                                    if (playbackService?.offlineVideoId != null) {
                                        player.setDataSource(requireContext(), it.toUri())
                                    } else {
                                        player.setDataSource(it)
                                    }
                                    playbackService?.seekPosition = position
                                    player.prepareAsync()
                                }
                            }
                        }
                    }
                } else {
                    viewModel.resume = player.isPlaying
                    player.pause()
                    playbackService?.updatePlayingState()
                    updatePlayingState()
                }
                playbackService?.setSleepTimer((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
            }
        }
        binding.playerControls.root.removeCallbacks(updateProgressAction)
        playbackService?.preparedListener = null
        playbackService?.seekCompleteListener = null
        playbackService?.completionListener = null
        playbackService?.infoListener = null
        playbackService?.errorListener = null
        playbackService?.pauseListener = null
        playbackService?.speedListener = null
        surfaceHolderCallback?.let { binding.playerSurface.holder.removeCallback(it) }
        surfaceHolderCallback = null
        serviceConnection?.let { requireContext().unbindService(it) }
        serviceConnection = null
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            if (videoType == STREAM) {
                restartPlayer()
            } else {
                val position = player?.currentPosition?.toLong()
                playbackService?.seekPosition = position
                player?.prepareAsync()
            }
        }
    }

    override fun onNetworkLost() {
        if (videoType != STREAM && isResumed) {
            player?.stop()
            playbackService?.updatePlayingState()
            updatePlayingState()
        }
    }

    companion object {
        fun newInstance(item: Stream): MediaPlayerFragment {
            return MediaPlayerFragment().apply {
                arguments = getStreamArguments(item)
            }
        }

        fun newInstance(item: Video, offset: Long?, ignoreSavedPosition: Boolean): MediaPlayerFragment {
            return MediaPlayerFragment().apply {
                arguments = getVideoArguments(item, offset, ignoreSavedPosition)
            }
        }

        fun newInstance(item: Clip): MediaPlayerFragment {
            return MediaPlayerFragment().apply {
                arguments = getClipArguments(item)
            }
        }

        fun newInstance(item: OfflineVideo): MediaPlayerFragment {
            return MediaPlayerFragment().apply {
                arguments = getOfflineVideoArguments(item)
            }
        }
    }
}