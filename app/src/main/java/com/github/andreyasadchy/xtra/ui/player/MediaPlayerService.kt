package com.github.andreyasadchy.xtra.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.DynamicsProcessing
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.MediaButtonReceiver
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.time.Duration.Companion.milliseconds

class MediaPlayerService : BasePlaybackService() {

    var player: MediaPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var session: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var artworkUri: String? = null
    private var cachedBitmap: Bitmap? = null
    private var bitmapLoadJob: Job? = null

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var sleepTimer: Timer? = null
    private var sleepTimerEndTime = 0L
    private var lastSavedPosition: Long? = null
    private var savePositionTimer: Timer? = null
    private var stopServiceTimer: Timer? = null

    var seekPosition: Long? = null
    var startPlayer = true
    private var backupQualities: List<String>? = null
    private var created = false

    interface PlayerListener {
        fun onPrepared(player: MediaPlayer)
        fun onSeekComplete(player: MediaPlayer)
        fun onCompletion(player: MediaPlayer)
        fun onInfo(player: MediaPlayer, what: Int, extra: Int)
        fun onVideoSizeChanged(player: MediaPlayer, width: Int, height: Int)
        fun onError(player: MediaPlayer, what: Int, extra: Int)
        fun onIsPlayingChanged()
        fun onSpeedChanged(speed: Float)
    }

    var playerListener: PlayerListener? = null

    interface Listener {
        fun started()
        fun loaded()
        fun changePlayerMode()
        fun toast(resId: Int, duration: Int)
        fun changeSurfaceVisibility(visible: Boolean) {}
    }

    var serviceListener: Listener? = null

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
    }

    private fun create(restorePauseState: Boolean) {
        if (!created) {
            created = true
            val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
            val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
            val sessionCallback = object : MediaSession.Callback() {
                override fun onPrepare() {
                    player?.prepareAsync()
                }

                override fun onPlay() {
                    player?.let { player ->
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.start()
                        }
                        updatePlayingState()
                        playerListener?.onIsPlayingChanged()
                    }
                }

                override fun onPause() {
                    player?.let { player ->
                        player.pause()
                        updatePlayingState()
                        playerListener?.onIsPlayingChanged()
                    }
                }

                override fun onSkipToNext() {
                    player?.let { player ->
                        val position = player.currentPosition + fastForwardMs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            player.seekTo(position.toInt())
                        }
                    }
                }

                override fun onSkipToPrevious() {
                    player?.let { player ->
                        val position = player.currentPosition - rewindMs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            player.seekTo(position.toInt())
                        }
                    }
                }

                override fun onFastForward() {
                    player?.let { player ->
                        val position = player.currentPosition + fastForwardMs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            player.seekTo(position.toInt())
                        }
                    }
                }

                override fun onRewind() {
                    player?.let { player ->
                        val position = player.currentPosition - rewindMs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            player.seekTo(position.toInt())
                        }
                    }
                }

                override fun onStop() {
                    player?.let { player ->
                        player.stop()
                        updatePlayingState()
                        playerListener?.onIsPlayingChanged()
                    }
                }

                override fun onSeekTo(pos: Long) {
                    player?.let { player ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            player.seekTo(pos, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            player.seekTo(pos.toInt())
                        }
                    }
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    player?.let { player ->
                        val params = PlaybackParams()
                        params.speed = speed
                        player.playbackParams = params
                        playerListener?.onSpeedChanged(speed)
                    }
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    player?.let { player ->
                        when (action) {
                            INTENT_REWIND -> {
                                val position = player.currentPosition - rewindMs
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    player.seekTo(position.toInt())
                                }
                            }
                            INTENT_FAST_FORWARD -> {
                                val position = player.currentPosition + fastForwardMs
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    player.seekTo(position.toInt())
                                }
                            }
                        }
                    }
                }
            }
            val player = MediaPlayer().apply {
                setWakeMode(this@MediaPlayerService, PowerManager.PARTIAL_WAKE_LOCK)
            }
            this.player = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            player.setOnPreparedListener { player ->
                seekPosition?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player?.seekTo(it, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player?.seekTo(it.toInt())
                    }
                    seekPosition = null
                }
                if (startPlayer) {
                    player.start()
                } else {
                    startPlayer = true
                }
                updateMetadata()
                updatePlayingState()
                playerListener?.onPrepared(player)
            }
            player.setOnSeekCompleteListener { player ->
                updatePlaybackState()
                updateNotification()
                playerListener?.onSeekComplete(player)
            }
            player.setOnCompletionListener { player ->
                updatePlaybackState()
                updateNotification()
                playerListener?.onCompletion(player)
            }
            player.setOnInfoListener { player, what, extra ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START, MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        updatePlaybackState()
                        updateNotification()
                    }
                }
                playerListener?.onError(player, what, extra)
                return@setOnInfoListener true
            }
            player.setOnVideoSizeChangedListener { player, width, height ->
                playerListener?.onError(player, width, height)
            }
            player.setOnErrorListener { player, what, extra ->
                updatePlaybackState(true)
                updateNotification()
                playerListener?.onError(player, what, extra)
                return@setOnErrorListener true
            }
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MediaPlayer:WifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MediaPlayer:WifiLock")
            }
            wifiLock?.acquire()
            val session = MediaSession(this, "MediaPlayerService")
            this.session = session
            session.setCallback(sessionCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    session.setMediaButtonBroadcastReceiver(ComponentName(this, MediaButtonReceiver::class.java))
                } catch (e: IllegalArgumentException) {
                    // https://github.com/androidx/media/issues/1730
                }
            } else {
                @Suppress("DEPRECATION")
                session.setMediaButtonReceiver(
                    PendingIntent.getBroadcast(this, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver::class.java), PendingIntent.FLAG_MUTABLE)
                )
            }
            session.isActive = true
            notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = getString(R.string.notification_playback_channel_id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                notificationManager?.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        ContextCompat.getString(this, R.string.notification_playback_channel_title),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                            setShowBadge(false)
                        }
                    }
                )
            }
            start(restorePauseState)
        }
    }

    private fun start(restorePauseState: Boolean) {
        lifecycleScope.launch {
            restorePlaybackState()
            when (type) {
                STREAM -> {
                    started = true
                    serviceListener?.started()
                    if (qualities.isNullOrEmpty()) {
                        useCustomProxy = prefs().getBoolean(C.PLAYER_STREAM_PROXY, false)
                    }
                    loadStream(restorePauseState)
                }
                VIDEO -> {
                    started = true
                    serviceListener?.started()
                    loadVideo(restorePauseState)
                }
                CLIP -> {
                    started = true
                    serviceListener?.started()
                    loadClip(restorePauseState)
                }
                OFFLINE_VIDEO -> {
                    offlineVideoId?.let { id ->
                        val video = xtraModule.offlineVideosRepository.getById(id)
                        if (video != null) {
                            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                                video.lastWatchPosition
                            } else {
                                null
                            } ?: savedPosition ?: 0
                            chatUrl = video.chatUrl
                            started = true
                            serviceListener?.started()
                            if (qualities.isNullOrEmpty()) {
                                qualities = listOf(
                                    VideoQuality(SOURCE_QUALITY, null, video.url),
                                    VideoQuality(AUDIO_ONLY_QUALITY),
                                )
                                setDefaultQuality()
                            }
                            serviceListener?.changePlayerMode()
                            val url = quality?.url ?: qualities?.firstOrNull()?.url
                            if (url != null) {
                                player?.let { player ->
                                    serviceListener?.changeSurfaceVisibility(quality?.name != AUDIO_ONLY_QUALITY)
                                    player.reset()
                                    player.setDataSource(this@MediaPlayerService, url.toUri())
                                    val volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                    player.setVolume(volume, volume)
                                    val params = PlaybackParams()
                                    params.speed = prefs().getFloat(C.PLAYER_SPEED, 1f)
                                    player.playbackParams = params
                                    seekPosition = playbackPosition
                                    player.prepareAsync()
                                    loaded = true
                                    serviceListener?.loaded()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadStream(restorePauseState: Boolean = false, restart: Boolean = false) {
        channelLogin?.let { channelLogin ->
            if (restart || qualities.isNullOrEmpty()) {
                val proxyUrl = prefs().getString(C.PLAYER_PROXY_URL, "")
                if (useCustomProxy && !proxyUrl.isNullOrBlank()) {
                    playlistUrl = proxyUrl.replace("\$channel", channelLogin)
                } else {
                    useCustomProxy = false
                    val url = try {
                        xtraModule.playerRepository.loadStreamPlaylistUrl(
                            networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(this@MediaPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                            channelLogin = channelLogin,
                            randomDeviceId = prefs().getBoolean(C.TOKEN_RANDOM_DEVICE_ID, true),
                            xDeviceId = prefs().getString(C.TOKEN_X_DEVICE_ID, "twitch-web-wall-mason"),
                            playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                            supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                            proxyPlaybackAccessToken = prefs().getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                            proxyHost = prefs().getString(C.PROXY_HOST, null),
                            proxyPort = prefs().getString(C.PROXY_PORT, null)?.toIntOrNull(),
                            proxyUser = prefs().getString(C.PROXY_USER, null),
                            proxyPassword = prefs().getString(C.PROXY_PASSWORD, null),
                            enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false)
                        )
                    } catch (e: Exception) {
                        if (e.message == C.FAILED_INTEGRITY_CHECK) {
                            integrity.emit("refreshStream")
                        }
                        null
                    }
                    playlistUrl = url
                }
            }
            val url = playlistUrl
            if (url != null) {
                player?.let { player ->
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                    val proxyMultivariantPlaylist = prefs().getBoolean(C.PROXY_MULTIVARIANT_PLAYLIST, false)
                    val proxyHost = prefs().getString(C.PROXY_HOST, null)
                    val proxyPort = prefs().getString(C.PROXY_PORT, null)?.toIntOrNull()
                    val proxyUser = prefs().getString(C.PROXY_USER, null)
                    val proxyPassword = prefs().getString(C.PROXY_PASSWORD, null)
                    val response = try {
                        val useProxy = !useCustomProxy && proxyMultivariantPlaylist && !proxyHost.isNullOrBlank() && proxyPort != null
                        when {
                            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null && !useProxy -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                }
                                if (response.first.httpStatusCode in 200..299) {
                                    String(response.second) to null
                                } else {
                                    null to response.first.httpStatusCode
                                }
                            }
                            networkLibrary == "Cronet" && xtraModule.cronetEngine.value != null && !useProxy -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                }
                                if (response.first.httpStatusCode in 200..299) {
                                    String(response.second) to null
                                } else {
                                    null to response.first.httpStatusCode
                                }
                            }
                            else -> {
                                xtraModule.okHttpClient.value.newBuilder().apply {
                                    if (useProxy) {
                                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                                        if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                            proxyAuthenticator { _, response ->
                                                response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                                            }
                                        }
                                    }
                                }.build().newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                    if (response.isSuccessful) {
                                        response.body.string() to null
                                    } else {
                                        null to response.code
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    val playlist = response?.first
                    val responseCode = response?.second
                    if (responseCode != null) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val isNetworkAvailable = networkCapabilities != null
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (isNetworkAvailable) {
                            when {
                                responseCode == 404 -> {
                                    serviceListener?.toast(R.string.stream_ended, Toast.LENGTH_LONG)
                                }
                                useCustomProxy && responseCode >= 400 -> {
                                    useCustomProxy = false
                                    serviceListener?.toast(R.string.proxy_error, Toast.LENGTH_LONG)
                                    lifecycleScope.launch {
                                        delay(1500.milliseconds)
                                        restartPlayer()
                                    }
                                }
                                else -> {
                                    serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                    lifecycleScope.launch {
                                        delay(1500.milliseconds)
                                        restartPlayer()
                                    }
                                }
                            }
                        }
                    }
                    if (!playlist.isNullOrBlank()) {
                        val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList().ifEmpty {
                            Regex("NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                        }
                        val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                        val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toMutableList()
                        val list = names.mapIndexedNotNull { index, name ->
                            urls.getOrNull(index)?.let { url ->
                                VideoQuality(name, codecs.getOrNull(index), url)
                            }
                        }
                        qualities = list.asSequence()
                            .sortedByDescending {
                                it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                            }
                            .sortedByDescending {
                                it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                            }
                            .toMutableList().apply {
                                find { it.name.equals("source", true) }?.let { source ->
                                    remove(source)
                                    add(0, VideoQuality(SOURCE_QUALITY, source.codecs, source.url))
                                }
                                val audio = find { it.name?.startsWith("audio", true) == true }?.also {
                                    remove(it)
                                }
                                add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.url))
                                add(VideoQuality(CHAT_ONLY_QUALITY))
                            }
                        setDefaultQuality()
                        serviceListener?.changePlayerMode()
                        quality?.url?.let { url ->
                            serviceListener?.changeSurfaceVisibility(quality?.name != AUDIO_ONLY_QUALITY)
                            player.reset()
                            player.setDataSource(url)
                            val volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                            player.setVolume(volume, volume)
                            val params = PlaybackParams()
                            params.speed = prefs().getFloat(C.PLAYER_SPEED, 1f)
                            player.playbackParams = params
                            seekPosition = savedPosition ?: 0
                            startPlayer = !restorePauseState || !paused
                            player.prepareAsync()
                            loaded = true
                            serviceListener?.loaded()
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadVideo(restorePauseState: Boolean = false) {
        videoId?.let { videoId ->
            val playbackPosition = if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                videoId.toLongOrNull()?.let { xtraModule.playerRepository.getVideoPosition(it)?.position }
            } else {
                null
            } ?: savedPosition ?: 0
            if (qualities.isNullOrEmpty()) {
                val result = try {
                    xtraModule.playerRepository.loadVideoPlaylistUrl(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@MediaPlayerService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                        videoId = videoId,
                        playerType = prefs().getString(C.TOKEN_PLAYER_TYPE_VIDEO, "channel_home_live"),
                        supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshVideo")
                    }
                    null
                }
                if (result != null) {
                    playlistUrl = result.first
                    backupQualities = result.second
                }
            }
            val url = playlistUrl
            if (url != null) {
                player?.let { player ->
                    val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                    val response = try {
                        when {
                            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                }
                                if (response.first.httpStatusCode in 200..299) {
                                    String(response.second) to null
                                } else {
                                    null to response.first.httpStatusCode
                                }
                            }
                            networkLibrary == "Cronet" && xtraModule.cronetEngine.value != null -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                }
                                if (response.first.httpStatusCode in 200..299) {
                                    String(response.second) to null
                                } else {
                                    null to response.first.httpStatusCode
                                }
                            }
                            else -> {
                                xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                    if (response.isSuccessful) {
                                        response.body.string() to null
                                    } else {
                                        null to response.code
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    val playlist = response?.first
                    val responseCode = response?.second
                    if (responseCode != null) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        val isNetworkAvailable = networkCapabilities != null
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (isNetworkAvailable) {
                            when {
                                !skipAccessToken && responseCode != 0 -> {
                                    skipAccessToken = true
                                    videoAnimatedPreviewURL?.let { preview ->
                                        val urls = TwitchApiHelper.getVideoUrlsFromPreview(preview, videoType, backupQualities)
                                        val list = urls.map {
                                            VideoQuality(it.key, null, it.value)
                                        }
                                        qualities = list.asSequence()
                                            .sortedByDescending {
                                                it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            }
                                            .sortedByDescending {
                                                it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                                            }
                                            .toMutableList().apply {
                                                find { it.name.equals("source", true) }?.let { source ->
                                                    remove(source)
                                                    add(0, VideoQuality(SOURCE_QUALITY, source.codecs, source.url))
                                                }
                                                val audio = find { it.name?.startsWith("audio", true) == true }?.also {
                                                    remove(it)
                                                }
                                                add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.url))
                                            }
                                        quality = qualities?.firstOrNull()
                                        serviceListener?.changePlayerMode()
                                        val url = quality?.url
                                        if (url != null) {
                                            val playbackPosition = player.currentPosition.toLong()
                                            serviceListener?.changeSurfaceVisibility(quality?.name != AUDIO_ONLY_QUALITY)
                                            player.reset()
                                            player.setDataSource(url)
                                            val volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                                            player.setVolume(volume, volume)
                                            val params = PlaybackParams()
                                            params.speed = prefs().getFloat(C.PLAYER_SPEED, 1f)
                                            player.playbackParams = params
                                            seekPosition = playbackPosition
                                            player.prepareAsync()
                                            loaded = true
                                            serviceListener?.loaded()
                                        }
                                    }
                                }
                                responseCode == 403 -> {
                                    serviceListener?.toast(R.string.video_subscribers_only, Toast.LENGTH_LONG)
                                }
                                else -> {
                                    serviceListener?.toast(R.string.player_error, Toast.LENGTH_SHORT)
                                    lifecycleScope.launch {
                                        delay(1500.milliseconds)
                                        player.prepare()
                                    }
                                }
                            }
                        }
                    }
                    if (!playlist.isNullOrBlank()) {
                        val names = Regex("IVS-NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList().ifEmpty {
                            Regex("NAME=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
                        }
                        val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toMutableList()
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
                                                                            codecs.add(codec)
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
                        val list = names.mapIndexedNotNull { index, name ->
                            urls.getOrNull(index)?.let { url ->
                                VideoQuality(name, codecs.getOrNull(index), url)
                            }
                        }
                        qualities = list.asSequence()
                            .sortedByDescending {
                                it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                            }
                            .sortedByDescending {
                                it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                            }
                            .toMutableList().apply {
                                find { it.name.equals("source", true) }?.let { source ->
                                    remove(source)
                                    add(0, VideoQuality(SOURCE_QUALITY, source.codecs, source.url))
                                }
                                val audio = find { it.name?.startsWith("audio", true) == true }?.also {
                                    remove(it)
                                }
                                add(VideoQuality(AUDIO_ONLY_QUALITY, audio?.codecs, audio?.url))
                            }
                        setDefaultQuality()
                        serviceListener?.changePlayerMode()
                        quality?.url?.let { url ->
                            serviceListener?.changeSurfaceVisibility(quality?.name != AUDIO_ONLY_QUALITY)
                            player.reset()
                            player.setDataSource(url)
                            val volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                            player.setVolume(volume, volume)
                            val params = PlaybackParams()
                            params.speed = prefs().getFloat(C.PLAYER_SPEED, 1f)
                            player.playbackParams = params
                            seekPosition = playbackPosition
                            startPlayer = !restorePauseState || !paused
                            player.prepareAsync()
                            loaded = true
                            serviceListener?.loaded()
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadClip(restorePauseState: Boolean = false) {
        clipId?.let { clipId ->
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
            if (qualities.isNullOrEmpty()) {
                val list = try {
                    xtraModule.playerRepository.loadClipQualities(
                        networkLibrary = networkLibrary,
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@MediaPlayerService),
                        clipId = clipId,
                        enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false)
                    )
                } catch (e: Exception) {
                    if (e.message == C.FAILED_INTEGRITY_CHECK) {
                        integrity.emit("refreshClip")
                    }
                    null
                }
                if (list != null) {
                    val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")?.split(',') ?: emptyList()
                    val filtered = list.filterNot {
                        it.codecs?.substringBefore('.').let { codec ->
                            (codec == "av01" && !supportedCodecs.contains("av1")) || ((codec == "hev1" || codec == "hvc1") && !supportedCodecs.contains("h265"))
                        }
                    }
                    qualities = filtered
                        .sortedByDescending {
                            it.name?.substringAfter("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        }
                        .sortedByDescending {
                            it.name?.substringBefore("p", "")?.takeWhile { it.isDigit() }?.toIntOrNull()
                        }
                        .toMutableList().apply {
                            add(VideoQuality(AUDIO_ONLY_QUALITY))
                        }
                    setDefaultQuality()
                }
            }
            serviceListener?.changePlayerMode()
            val url = quality?.url ?: qualities?.firstOrNull()?.url
            if (url != null) {
                player?.let { player ->
                    serviceListener?.changeSurfaceVisibility(quality?.name != AUDIO_ONLY_QUALITY)
                    player.reset()
                    player.setDataSource(url)
                    val volume = prefs().getInt(C.PLAYER_VOLUME, 100) / 100f
                    player.setVolume(volume, volume)
                    val params = PlaybackParams()
                    params.speed = prefs().getFloat(C.PLAYER_SPEED, 1f)
                    player.playbackParams = params
                    seekPosition = savedPosition ?: 0
                    startPlayer = !restorePauseState || !paused
                    player.prepareAsync()
                    loaded = true
                    serviceListener?.loaded()
                }
            }
        }
    }

    fun retry(item: String) {
        when (item) {
            "refreshStream" -> {
                lifecycleScope.launch {
                    loadStream()
                }
            }
            "refreshVideo" -> {
                lifecycleScope.launch {
                    loadVideo()
                }
            }
            "refreshClip" -> {
                lifecycleScope.launch {
                    loadClip()
                }
            }
        }
    }

    fun changeQuality(selectedQuality: VideoQuality?) {
        previousQuality = quality
        quality = selectedQuality
        quality?.let { quality ->
            player?.let { player ->
                when (quality.name) {
                    AUDIO_ONLY_QUALITY -> {
                        serviceListener?.changeSurfaceVisibility(false)
                        quality.url?.let {
                            val position = player.currentPosition.toLong()
                            player.reset()
                            if (offlineVideoId != null) {
                                player.setDataSource(this, it.toUri())
                            } else {
                                player.setDataSource(it)
                            }
                            seekPosition = position
                            player.prepareAsync()
                        }
                    }
                    CHAT_ONLY_QUALITY -> {
                        player.stop()
                        updatePlayingState()
                        playerListener?.onIsPlayingChanged()
                    }
                    else -> {
                        quality.url?.let {
                            val position = player.currentPosition.toLong()
                            player.reset()
                            if (offlineVideoId != null) {
                                player.setDataSource(this, it.toUri())
                            } else {
                                player.setDataSource(it)
                            }
                            seekPosition = position
                            player.prepareAsync()
                        }
                        serviceListener?.changeSurfaceVisibility(true)
                    }
                }
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                if ((!cellular && prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved") == "saved") || (cellular && prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved") == "saved")) {
                    prefs().edit { putString(C.PLAYER_QUALITY, quality.name) }
                }
            }
        }
    }

    fun restartPlayer() {
        if (quality?.name != CHAT_ONLY_QUALITY) {
            lifecycleScope.launch {
                loadStream(restart = true)
            }
        }
    }

    fun startAudioOnly() {
        player?.let { player ->
            if (quality?.name != AUDIO_ONLY_QUALITY) {
                restoreQuality = true
                previousQuality = quality
                quality = qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                quality?.let { quality ->
                    if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                        serviceListener?.changeSurfaceVisibility(false)
                    }
                    if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                        quality.url?.let { url ->
                            val position = player.currentPosition.toLong()
                            player.reset()
                            if (offlineVideoId != null) {
                                player.setDataSource(this, url.toUri())
                            } else {
                                player.setDataSource(url)
                            }
                            seekPosition = position
                            player.prepareAsync()
                        }
                    }
                }
            }
        }
    }

    fun stop(isInPIPMode: Boolean) {
        player?.let { player ->
            val isInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
            if ((!isInPIPMode && isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO, true))
                || (!isInPIPMode && !isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_LOCKED, true))
                || (isInPIPMode && isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED, false))
                || (isInPIPMode && !isInteractive && prefs().getBoolean(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED, true))) {
                if (player.isPlaying && quality?.name != AUDIO_ONLY_QUALITY) {
                    restoreQuality = true
                    previousQuality = quality
                    quality = qualities?.find { it.name == AUDIO_ONLY_QUALITY }
                    quality?.let { quality ->
                        if (prefs().getBoolean(C.PLAYER_DISABLE_BACKGROUND_VIDEO, true)) {
                            serviceListener?.changeSurfaceVisibility(false)
                        }
                        if (prefs().getBoolean(C.PLAYER_USE_BACKGROUND_AUDIO_TRACK, false)) {
                            quality.url?.let { url ->
                                val position = player.currentPosition.toLong()
                                player.reset()
                                if (offlineVideoId != null) {
                                    player.setDataSource(this, url.toUri())
                                } else {
                                    player.setDataSource(url)
                                }
                                seekPosition = position
                                player.prepareAsync()
                            }
                        }
                    }
                }
            } else {
                player.pause()
            }
        }
    }

    private fun updatePlaybackState(error: Boolean = false) {
        player?.let { player ->
            val isLive = !error && player.duration == -1
            session?.setPlaybackState(
                PlaybackState.Builder().apply {
                    setState(
                        if (!player.isPlaying) {
                            PlaybackState.STATE_PAUSED
                        } else {
                            PlaybackState.STATE_PLAYING
                        },
                        if (!isLive) {
                            player.currentPosition.toLong()
                        } else {
                            -1
                        },
                        if (player.isPlaying && !isLive) {
                            player.playbackParams.speed
                        } else {
                            0f
                        }
                    )
                    setActions(
                        (PlaybackState.ACTION_STOP
                                or PlaybackState.ACTION_PAUSE
                                or PlaybackState.ACTION_PLAY
                                or PlaybackState.ACTION_REWIND
                                or PlaybackState.ACTION_FAST_FORWARD
                                or PlaybackState.ACTION_SET_RATING
                                or PlaybackState.ACTION_PLAY_PAUSE).let {
                            if (!isLive) {
                                it or PlaybackState.ACTION_SEEK_TO
                            } else {
                                it
                            }.let {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    (it or PlaybackState.ACTION_PREPARE).let {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            it or PlaybackState.ACTION_SET_PLAYBACK_SPEED
                                        } else {
                                            it
                                        }
                                    }
                                } else {
                                    it
                                }
                            }
                        }
                    )
                    addCustomAction(INTENT_REWIND, ContextCompat.getString(this@MediaPlayerService, R.string.rewind), androidx.media3.session.R.drawable.media3_icon_rewind)
                    addCustomAction(INTENT_FAST_FORWARD, ContextCompat.getString(this@MediaPlayerService, R.string.forward), androidx.media3.session.R.drawable.media3_icon_fast_forward)
                }.build()
            )
        }
    }

    private fun updateMetadata() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    if (response.first.httpStatusCode in 200..299) {
                                        response.second
                                    } else null
                                }
                                networkLibrary == "Cronet" && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                    }
                                    if (response.first.httpStatusCode in 200..299) {
                                        response.second
                                    } else null
                                }
                                else -> {
                                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.bytes()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    setMetadata(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
        setMetadata(bitmap)
    }

    private fun setMetadata(bitmap: Bitmap?) {
        player?.let { player ->
            session?.setMetadata(
                MediaMetadata.Builder().apply {
                    putText(MediaMetadata.METADATA_KEY_TITLE, title)
                    putText(MediaMetadata.METADATA_KEY_ARTIST, channelName)
                    if (bitmap != null) {
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                    }
                    putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration.toLong())
                }.build()
            )
        }
    }

    private fun updateNotification() {
        val url = channelImage
        val bitmap = if (!url.isNullOrBlank()) {
            if (url == artworkUri && cachedBitmap != null) {
                cachedBitmap
            } else {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                artworkUri = url
                bitmapLoadJob?.cancel()
                bitmapLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val scheme = url.toUri().scheme
                        val response = if (scheme == "https" || scheme == "http") {
                            when {
                                networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && xtraModule.httpEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.httpEngine.value!!.newUrlRequestBuilder(url, xtraModule.cronetExecutor.value, NetworkUtils.byteArrayUrlCallback(continuation)).build().start()
                                    }
                                    if (response.first.httpStatusCode in 200..299) {
                                        response.second
                                    } else null
                                }
                                networkLibrary == "Cronet" && xtraModule.cronetEngine.value != null -> {
                                    val response = suspendCancellableCoroutine { continuation ->
                                        xtraModule.cronetEngine.value!!.newUrlRequestBuilder(url, NetworkUtils.byteArrayCronetUrlCallback(continuation), xtraModule.cronetExecutor.value).build().start()
                                    }
                                    if (response.first.httpStatusCode in 200..299) {
                                        response.second
                                    } else null
                                }
                                else -> {
                                    xtraModule.okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                        if (response.isSuccessful) {
                                            response.body.bytes()
                                        } else null
                                    }
                                }
                            }
                        } else {
                            FileInputStream(url).use {
                                it.readBytes()
                            }
                        }
                        if (response != null) {
                            val bitmap = BitmapFactory.decodeByteArray(response, 0, response.size)
                            if (bitmap != null) {
                                cachedBitmap = bitmap
                                withContext(Dispatchers.Main) {
                                    sendNotification(bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }
                }
                null
            }
        } else null
        sendNotification(bitmap)
    }

    private fun sendNotification(bitmap: Bitmap?) {
        player?.let { player ->
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, getString(R.string.notification_playback_channel_id))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle(title)
                setContentText(channelName)
                setSmallIcon(R.drawable.notification_icon)
                if (bitmap != null) {
                    setLargeIcon(bitmap)
                }
                setGroup(GROUP_KEY)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setOngoing(false)
                setOnlyAlertOnce(true)
                if (player.isPlaying && player.playbackParams.speed == 1f) {
                    setWhen(System.currentTimeMillis() - player.currentPosition)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                setContentIntent(
                    PendingIntent.getActivity(
                        this@MediaPlayerService,
                        REQUEST_CODE_RESUME,
                        Intent(this@MediaPlayerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                            action = MainActivity.INTENT_OPEN_PLAYER
                        },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_rewind),
                        ContextCompat.getString(this@MediaPlayerService, R.string.rewind),
                        PendingIntent.getService(
                            this@MediaPlayerService,
                            REQUEST_CODE_REWIND,
                            Intent(this@MediaPlayerService, MediaPlayerService::class.java).apply {
                                action = INTENT_REWIND
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
                if (!player.isPlaying) {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_play),
                            ContextCompat.getString(this@MediaPlayerService, R.string.resume),
                            PendingIntent.getService(
                                this@MediaPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@MediaPlayerService, MediaPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                } else {
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_pause),
                            ContextCompat.getString(this@MediaPlayerService, R.string.pause),
                            PendingIntent.getService(
                                this@MediaPlayerService,
                                REQUEST_CODE_PLAY_PAUSE,
                                Intent(this@MediaPlayerService, MediaPlayerService::class.java).apply {
                                    action = INTENT_PLAY_PAUSE
                                },
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ).build()
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@MediaPlayerService, androidx.media3.session.R.drawable.media3_icon_fast_forward),
                        ContextCompat.getString(this@MediaPlayerService, R.string.forward),
                        PendingIntent.getService(
                            this@MediaPlayerService,
                            REQUEST_CODE_FAST_FORWARD,
                            Intent(this@MediaPlayerService, MediaPlayerService::class.java).apply {
                                action = INTENT_FAST_FORWARD
                            },
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
            }.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    fun setSleepTimer(duration: Long): Long {
        val endTime = sleepTimerEndTime
        sleepTimer?.cancel()
        sleepTimerEndTime = 0L
        if (duration > 0L) {
            sleepTimer = Timer().apply {
                schedule(duration) {
                    Handler(Looper.getMainLooper()).post {
                        savePosition()
                        player?.pause()
                        updatePlayingState()
                        playerListener?.onIsPlayingChanged()
                        stopSelf()
                    }
                }
            }
            sleepTimerEndTime = System.currentTimeMillis() + duration
        }
        return endTime
    }

    fun setStopServiceTimer(start: Boolean) {
        if (start) {
            if (stopServiceTimer == null && player?.isPlaying == false) {
                stopServiceTimer = Timer().apply {
                    schedule(600000) {
                        Handler(Looper.getMainLooper()).post {
                            stopSelf()
                        }
                    }
                }
            }
        } else {
            stopServiceTimer?.cancel()
            stopServiceTimer = null
        }
    }

    fun toggleDynamicsProcessing(): Boolean {
        if (dynamicsProcessing?.enabled == true) {
            dynamicsProcessing?.enabled = false
        } else {
            if (dynamicsProcessing == null) {
                player?.audioSessionId?.let { reinitializeDynamicsProcessing(it) }
            } else {
                dynamicsProcessing?.enabled = true
            }
        }
        val enabled = dynamicsProcessing?.enabled == true
        prefs().edit { putBoolean(C.PLAYER_AUDIO_COMPRESSOR, enabled) }
        return enabled
    }

    private fun reinitializeDynamicsProcessing(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, null).apply {
                for (channelIdx in 0 until channelCount) {
                    for (bandIdx in 0 until getMbcByChannelIndex(channelIdx).bandCount) {
                        setMbcBandByChannelIndex(
                            channelIdx,
                            bandIdx,
                            getMbcBandByChannelIndex(channelIdx, bandIdx).apply {
                                attackTime = 0f
                                releaseTime = 0.25f
                                ratio = 1.6f
                                threshold = -50f
                                kneeWidth = 40f
                                preGain = 0f
                                postGain = 10f
                            }
                        )
                    }
                }
                enabled = true
            }
        }
    }

    private fun savePosition() {
        player?.let { player ->
            if (player.duration != -1) {
                if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                    when (type) {
                        VIDEO -> {
                            videoId?.toLongOrNull()?.let {
                                runBlocking {
                                    xtraModule.playerRepository.saveVideoPosition(VideoPosition(it, player.currentPosition.toLong()))
                                }
                            }
                        }
                        OFFLINE_VIDEO -> {
                            offlineVideoId?.let {
                                runBlocking {
                                    xtraModule.offlineVideosRepository.updatePosition(it, player.currentPosition.toLong())
                                }
                            }
                        }
                    }
                }
                runBlocking {
                    xtraModule.playerRepository.deletePlaybackStates()
                }
            }
        }
    }

    fun updatePlayingState() {
        updatePlaybackState()
        updateNotification()
        player?.let { player ->
            if (player.isPlaying) {
                if (savePositionTimer == null && (videoId != null || offlineVideoId != null)) {
                    savePositionTimer = Timer().apply {
                        scheduleAtFixedRate(30000, 30000) {
                            Handler(Looper.getMainLooper()).post {
                                updateSavedPosition()
                            }
                        }
                    }
                }
                stopServiceTimer?.cancel()
                stopServiceTimer = null
            } else {
                savePositionTimer?.cancel()
                savePositionTimer = null
                updateSavedPosition()
                if (stopServiceTimer == null && serviceListener == null) {
                    stopServiceTimer = Timer().apply {
                        schedule(600000) {
                            Handler(Looper.getMainLooper()).post {
                                stopSelf()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateSavedPosition() {
        player?.let { player ->
            if (player.duration != -1) {
                val currentPosition = player.currentPosition.toLong()
                val savedPosition = lastSavedPosition
                if (savedPosition == null || currentPosition - savedPosition !in 0..2000) {
                    lastSavedPosition = currentPosition
                    if (prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
                        when (type) {
                            VIDEO -> {
                                videoId?.toLongOrNull()?.let {
                                    runBlocking {
                                        xtraModule.playerRepository.saveVideoPosition(VideoPosition(it, currentPosition))
                                    }
                                }
                            }
                            OFFLINE_VIDEO -> {
                                offlineVideoId?.let {
                                    runBlocking {
                                        xtraModule.offlineVideosRepository.updatePosition(it, currentPosition)
                                    }
                                }
                            }
                        }
                    }
                    runBlocking {
                        savePlaybackState(currentPosition, !player.isPlaying)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            INTENT_REWIND -> {
                player?.let { player ->
                    val rewindMs = prefs().getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000
                    val position = player.currentPosition - rewindMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }
            }
            INTENT_PLAY_PAUSE -> {
                player?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.start()
                    }
                    updatePlayingState()
                    playerListener?.onIsPlayingChanged()
                }
            }
            INTENT_FAST_FORWARD -> {
                player?.let { player ->
                    val fastForwardMs = prefs().getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000
                    val position = player.currentPosition + fastForwardMs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        player.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        player.seekTo(position.toInt())
                    }
                }
            }
            INTENT_START -> create(restorePauseState = true)
            Intent.ACTION_MEDIA_BUTTON -> create(restorePauseState = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@MediaPlayerService
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        player?.pause()
        updatePlayingState()
        playerListener?.onIsPlayingChanged()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiLock?.release()
        player?.release()
        session?.release()
        bitmapLoadJob?.cancel()
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.PLAYBACK_NOTIFICATIONS"

        private const val REQUEST_CODE_RESUME = 0
        private const val REQUEST_CODE_REWIND = 1
        private const val REQUEST_CODE_PLAY_PAUSE = 2
        private const val REQUEST_CODE_FAST_FORWARD = 3

        private const val INTENT_REWIND = "com.github.andreyasadchy.xtra.REWIND"
        private const val INTENT_PLAY_PAUSE = "com.github.andreyasadchy.xtra.PLAY_PAUSE"
        private const val INTENT_FAST_FORWARD = "com.github.andreyasadchy.xtra.FAST_FORWARD"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_PLAYBACK_SERVICE"
    }
}