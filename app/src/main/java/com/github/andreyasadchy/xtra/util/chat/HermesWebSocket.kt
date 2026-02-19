package com.github.andreyasadchy.xtra.util.chat

import android.os.Build
import com.github.andreyasadchy.xtra.util.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import java.util.UUID
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

class HermesWebSocket(
    private val channelId: String,
    private val userId: String?,
    private val gqlClientId: String?,
    private val gqlToken: String?,
    private val collectPoints: Boolean,
    private val showRaids: Boolean,
    private val showPolls: Boolean,
    private val showPredictions: Boolean,
    private val trustManager: X509TrustManager?,
    private val listener: Listener,
) {
    private var webSocket: WebSocket? = null
    private var pongTimer: Timer? = null
    private var timeout = 15000L
    private var minuteWatchedTimer: Timer? = null
    private var topics = emptyMap<String, String>()
    private val handledMessageIds = mutableListOf<String>()

    fun connect(coroutineScope: CoroutineScope): Job {
        webSocket = WebSocket("wss://hermes.twitch.tv/v1?clientId=${gqlClientId}", trustManager, WebSocketListener())
        return coroutineScope.launch(Dispatchers.IO) {
            webSocket?.start()
        }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        pongTimer?.cancel()
        minuteWatchedTimer?.cancel()
        minuteWatchedTimer = null
        job?.cancel()
        webSocket?.disconnect()
    }

    private suspend fun subscribe() = withContext(Dispatchers.IO) {
        if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && collectPoints) {
            val authenticate = JSONObject().apply {
                put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 21))
                put("type", "authenticate")
                put("authenticate", JSONObject().apply {
                    put("token", gqlToken)
                })
                put("timestamp", getCurrentTime())
            }.toString()
            webSocket?.write(authenticate)
        }
        topics = buildMap {
            put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "video-playback-by-id.$channelId")
            put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "broadcast-settings-update.$channelId")
            put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "community-points-channel-v1.$channelId")
            if (showRaids) {
                put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "raid.$channelId")
            }
            if (showPolls) {
                put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "polls.$channelId")
            }
            if (showPredictions) {
                put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "predictions-channel-v1.$channelId")
            }
            if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank()) {
                if (collectPoints) {
                    put(UUID.randomUUID().toString().replace("-", "").substring(0, 21), "community-points-user-v1.$userId")
                }
            }
        }
        topics.forEach {
            val subscribe = JSONObject().apply {
                put("type", "subscribe")
                put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 21))
                put("subscribe", JSONObject().apply {
                    put("id", it.key)
                    put("type", "pubsub")
                    put("pubsub", JSONObject().apply {
                        put("topic", it.value)
                    })
                })
                put("timestamp", getCurrentTime())
            }.toString()
            webSocket?.write(subscribe)
        }
    }

    private fun getCurrentTime(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val date = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(date)
        } else {
            val calendar = Calendar.getInstance()
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.format(calendar.time)
        }
    }

    private suspend fun startPongTimer() = withContext(Dispatchers.IO) {
        pongTimer = Timer().apply {
            schedule(timeout) {
                launch {
                    webSocket?.disconnect()
                }
            }
        }
    }

    private suspend fun startMinuteWatchedTimer() = withContext(Dispatchers.IO) {
        minuteWatchedTimer = Timer().apply {
            scheduleAtFixedRate(60000, 60000) {
                launch {
                    listener.onMinuteWatched()
                }
            }
        }
    }

    interface Listener {
        suspend fun onConnect() {}
        suspend fun onPlaybackMessage(message: JSONObject) {}
        suspend fun onStreamInfo(message: JSONObject) {}
        suspend fun onRewardMessage(message: JSONObject) {}
        suspend fun onPointsEarned(message: JSONObject) {}
        suspend fun onClaimAvailable() {}
        suspend fun onMinuteWatched() {}
        suspend fun onRaidUpdate(message: JSONObject, openStream: Boolean) {}
        suspend fun onPollUpdate(message: JSONObject) {}
        suspend fun onPredictionUpdate(message: JSONObject) {}
        suspend fun onDisconnect(message: String, fullMsg: String?) {}
    }

    private inner class WebSocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            listener.onConnect()
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            try {
                val json = if (message.isNotBlank()) JSONObject(message) else null
                val messageId = if (json?.isNull("id") == false) json.optString("id").takeIf { it.isNotBlank() } else null
                if (!messageId.isNullOrBlank()) {
                    if (handledMessageIds.contains(messageId)) {
                        return
                    } else {
                        handledMessageIds.add(messageId)
                        if (handledMessageIds.size > 200) {
                            handledMessageIds.removeAt(0)
                        }
                    }
                }
                when (json?.optString("type")) {
                    "notification" -> {
                        pongTimer?.cancel()
                        startPongTimer()
                        val notification = json.optJSONObject("notification")
                        val subscription = notification?.optJSONObject("subscription")
                        val subscriptionId = subscription?.optString("id")
                        val topic = topics[subscriptionId]
                        val message = notification?.optString("pubsub")?.let { if (it.isNotBlank()) JSONObject(it) else null }
                        val messageType = message?.optString("type")
                        if (topic != null && messageType != null) {
                            when {
                                topic.startsWith("video-playback-by-id") -> listener.onPlaybackMessage(message)
                                topic.startsWith("broadcast-settings-update") -> {
                                    when {
                                        messageType.startsWith("broadcast_settings_update") -> listener.onStreamInfo(message)
                                    }
                                }
                                topic.startsWith("community-points-channel") -> {
                                    when {
                                        messageType.startsWith("reward-redeemed") -> listener.onRewardMessage(message)
                                    }
                                }
                                topic.startsWith("community-points-user") -> {
                                    when {
                                        messageType.startsWith("points-earned") -> listener.onPointsEarned(message)
                                        messageType.startsWith("claim-available") -> listener.onClaimAvailable()
                                    }
                                }
                                topic.startsWith("raid") -> {
                                    when {
                                        messageType.startsWith("raid_update") -> listener.onRaidUpdate(message, false)
                                        messageType.startsWith("raid_go") -> listener.onRaidUpdate(message, true)
                                    }
                                }
                                topic.startsWith("polls") -> listener.onPollUpdate(message)
                                topic.startsWith("predictions-channel") -> listener.onPredictionUpdate(message)
                            }
                        }
                    }
                    "keepalive" -> {
                        pongTimer?.cancel()
                        startPongTimer()
                    }
                    "reconnect" -> {
                        //val reconnect = json.optJSONObject("reconnect")
                        //val reconnectUrl = if (reconnect?.isNull("url") == false) reconnect.optString("url").takeIf { it.isNotBlank() } else null
                        pongTimer?.cancel()
                        webSocket.disconnect()
                    }
                    "welcome" -> {
                        val welcome = json.optJSONObject("welcome")
                        if (welcome?.isNull("keepaliveSec") == false) {
                            welcome.optInt("keepaliveSec").takeIf { it > 0 }?.let {
                                timeout = it * 1000L
                            }
                        }
                        pongTimer?.cancel()
                        startPongTimer()
                        subscribe()
                        if (collectPoints && !userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && minuteWatchedTimer == null) {
                            startMinuteWatchedTimer()
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }

        override suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String?) {
            listener.onDisconnect(message, fullMsg)
        }
    }
}
