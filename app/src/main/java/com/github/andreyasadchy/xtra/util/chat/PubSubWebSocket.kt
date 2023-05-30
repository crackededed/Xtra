package com.github.andreyasadchy.xtra.util.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class PubSubWebSocket(
    private val channelId: String,
    private val userId: String?,
    private val gqlToken: String?,
    private val collectPoints: Boolean,
    private val notifyPoints: Boolean,
    private val showRaids: Boolean,
    private val client: OkHttpClient,
    private val coroutineScope: CoroutineScope,
    private val listener: OnMessageReceivedListener) {
    private var socket: WebSocket? = null
    private var isActive = false
    private var pongReceived = false

    fun connect() {
        socket = client.newWebSocket(Request.Builder().url("wss://pubsub-edge.twitch.tv").build(), PubSubListener())
    }

    fun disconnect() {
        isActive = false
        socket?.close(1000, null)
    }

    private fun reconnect() {
        if (isActive) {
            coroutineScope.launch {
                disconnect()
                delay(1000)
                connect()
            }
        }
    }

    private fun listen() {
        val message = JSONObject().apply {
            put("type", "LISTEN")
            put("data", JSONObject().apply {
                put("topics", JSONArray().apply {
                    put("video-playback-by-id.$channelId")
                    put("broadcast-settings-update.$channelId")
                    put("community-points-channel-v1.$channelId")
                    if (showRaids) {
                        put("raid.$channelId")
                    }
                    if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank()) {
                        if (collectPoints) {
                            put("community-points-user-v1.$userId")
                        }
                    }
                })
                if (!userId.isNullOrBlank() && !gqlToken.isNullOrBlank() && collectPoints) {
                    put("auth_token", gqlToken)
                }
            })
        }.toString()
        socket?.send(message)
    }

    private fun ping() {
        if (isActive) {
            val ping = JSONObject().apply { put("type", "PING") }.toString()
            socket?.send(ping)
            checkPong()
        }
    }

    private fun checkPong() {
        tickerFlowPong().onCompletion {
            if (isActive) {
                if (pongReceived) {
                    pongReceived = false
                    checkPongWait()
                } else {
                    reconnect()
                }
            }
        }.launchIn(coroutineScope)
    }

    private fun tickerFlowPong() = flow {
        for (i in 10 downTo 0) {
            if (pongReceived || !isActive) {
                emit(i downTo 0)
            } else {
                emit(i)
                delay(1000)
            }
        }
    }

    private fun checkPongWait() {
        tickerFlowActive(270).onCompletion {
            if (isActive) {
                ping()
            }
        }.launchIn(coroutineScope)
    }

    private fun minuteWatched() {
        tickerFlowActive(60).onCompletion {
            if (isActive) {
                listener.onMinuteWatched()
                minuteWatched()
            }
        }.launchIn(coroutineScope)
    }

    private fun tickerFlowActive(seconds: Int) = flow {
        for (i in seconds downTo 0) {
            if (!isActive) {
                emit(i downTo 0)
            } else {
                emit(i)
                delay(1000)
            }
        }
    }

    private inner class PubSubListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isActive = true
            listen()
            ping()
            if (collectPoints && !userId.isNullOrBlank() && !gqlToken.isNullOrBlank()) {
                minuteWatched()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = if (text.isNotBlank()) JSONObject(text) else null
                when (json?.optString("type")) {
                    "MESSAGE" -> {
                        val data = json.optString("data").let { if (it.isNotBlank()) JSONObject(it) else null }
                        val topic = data?.optString("topic")
                        val message = data?.optString("message")?.let { if (it.isNotBlank()) JSONObject(it) else null }
                        val messageType = message?.optString("type")
                        when {
                            (topic?.startsWith("video-playback-by-id") == true) -> listener.onPlaybackMessage(text)
                            (topic?.startsWith("broadcast-settings-update") == true) && (messageType?.startsWith("broadcast_settings_update") == true) -> listener.onTitleUpdate(text)
                            (topic?.startsWith("community-points-channel") == true) && (messageType?.startsWith("reward-redeemed") == true) -> listener.onRewardMessage(text)
                            topic?.startsWith("community-points-user") == true -> {
                                when {
                                    messageType?.startsWith("points-earned") == true && notifyPoints -> {
                                        val messageData = message.optString("data").let { if (it.isNotBlank()) JSONObject(it) else null }
                                        val messageChannelId = messageData?.optString("channel_id")
                                        if (channelId == messageChannelId) {
                                            listener.onPointsEarned(text)
                                        }
                                    }
                                    messageType?.startsWith("claim-available") == true && collectPoints -> listener.onClaimAvailable()
                                }
                            }
                            topic?.startsWith("raid") == true && showRaids -> {
                                when {
                                    messageType?.startsWith("raid_update") == true -> listener.onRaidUpdate(text, false)
                                    messageType?.startsWith("raid_go") == true -> listener.onRaidUpdate(text, true)
                                }
                            }
                        }
                    }
                    "PONG" -> pongReceived = true
                    "RECONNECT" -> reconnect()
                }
            } catch (e: Exception) {

            }
        }
    }

    interface OnMessageReceivedListener {
        fun onPlaybackMessage(text: String)
        fun onTitleUpdate(text: String)
        fun onRewardMessage(text: String)
        fun onPointsEarned(text: String)
        fun onClaimAvailable()
        fun onMinuteWatched()
        fun onRaidUpdate(text: String, openStream: Boolean)
    }
}
