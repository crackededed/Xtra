package com.github.andreyasadchy.xtra.util.chat

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

class HermesWebSocketOkHttp(
    private val channelId: String,
    private val userId: String?,
    private val gqlClientId: String?,
    private val gqlToken: String?,
    private val collectPoints: Boolean,
    private val notifyPoints: Boolean,
    private val showRaids: Boolean,
    private val showPolls: Boolean,
    private val showPredictions: Boolean,
    private val client: OkHttpClient,
    private val onPlaybackMessage: (JSONObject) -> Unit,
    private val onStreamInfo: (JSONObject) -> Unit,
    private val onRewardMessage: (JSONObject) -> Unit,
    private val onPointsEarned: (JSONObject) -> Unit,
    private val onClaimAvailable: () -> Unit,
    private val onMinuteWatched: () -> Unit,
    private val onRaidUpdate: (JSONObject, Boolean) -> Unit,
    private val onPollUpdate: (JSONObject) -> Unit,
    private val onPredictionUpdate: (JSONObject) -> Unit,
) {
    private var socket: WebSocket? = null
    private var pongTimer: Timer? = null
    private var timeout = 15000L
    private var minuteWatchedTimer: Timer? = null
    private var topics = emptyMap<String, String>()
    private val handledMessageIds = mutableListOf<String>()

    fun connect() {
        socket = client.newWebSocket(
            Request.Builder().url("wss://hermes.twitch.tv/v1?clientId=${gqlClientId}").build(),
            HermesWebSocketListener()
        )
    }

    fun disconnect() {
        pongTimer?.cancel()
        minuteWatchedTimer?.cancel()
        minuteWatchedTimer = null
        socket?.close(1000, null)
    }

    private fun reconnect() {
        socket?.close(1000, null)
        connect()
    }

    private fun subscribe() {
        if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && collectPoints) {
            val authenticate = JSONObject().apply {
                put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 21))
                put("type", "authenticate")
                put("authenticate", JSONObject().apply {
                    put("token", gqlToken)
                })
                put("timestamp", getCurrentTime())
            }.toString()
            socket?.send(authenticate)
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
            socket?.send(subscribe)
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

    private fun startPongTimer() {
        pongTimer = Timer().apply {
            schedule(timeout) {
                reconnect()
            }
        }
    }

    private fun startMinuteWatchedTimer() {
        minuteWatchedTimer = Timer().apply {
            scheduleAtFixedRate(60000, 60000) {
                onMinuteWatched()
            }
        }
    }

    private inner class HermesWebSocketListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = if (text.isNotBlank()) JSONObject(text) else null
                val messageId = if (json?.isNull("id") == false) json.optString("id").takeIf { it.isNotBlank() } else null
                if (!messageId.isNullOrBlank()) {
                    if (handledMessageIds.contains(messageId)) {
                        return
                    } else {
                        if (handledMessageIds.size > 200) {
                            handledMessageIds.removeAt(0)
                        }
                        handledMessageIds.add(messageId)
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
                                topic.startsWith("video-playback-by-id") -> onPlaybackMessage(message)
                                topic.startsWith("broadcast-settings-update") -> {
                                    when {
                                        messageType.startsWith("broadcast_settings_update") -> onStreamInfo(message)
                                    }
                                }
                                topic.startsWith("community-points-channel") -> {
                                    when {
                                        messageType.startsWith("reward-redeemed") -> onRewardMessage(message)
                                    }
                                }
                                topic.startsWith("community-points-user") -> {
                                    when {
                                        messageType.startsWith("points-earned") -> {
                                            if (notifyPoints) {
                                                val messageData = message.optJSONObject("data")
                                                val messageChannelId = messageData?.optString("channel_id")
                                                if (channelId == messageChannelId) {
                                                    onPointsEarned(message)
                                                }
                                            }
                                        }
                                        messageType.startsWith("claim-available") -> {
                                            if (collectPoints) {
                                                onClaimAvailable()
                                            }
                                        }
                                    }
                                }
                                topic.startsWith("raid") -> {
                                    if (showRaids) {
                                        when {
                                            messageType.startsWith("raid_update") -> onRaidUpdate(message, false)
                                            messageType.startsWith("raid_go") -> onRaidUpdate(message, true)
                                        }
                                    }
                                }
                                topic.startsWith("polls") -> {
                                    if (showPolls) {
                                        onPollUpdate(message)
                                    }
                                }
                                topic.startsWith("predictions-channel") -> {
                                    if (showPredictions) {
                                        onPredictionUpdate(message)
                                    }
                                }
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
                        reconnect()
                    }
                    "welcome" -> {
                        val welcome = json.optJSONObject("welcome")
                        if (welcome?.isNull("keepaliveSec") == false) {
                            welcome.optInt("keepaliveSec").takeIf { it > 0 }?.let { timeout = it * 1000L }
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
    }
}
