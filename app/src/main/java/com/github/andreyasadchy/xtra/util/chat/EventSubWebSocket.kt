package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.util.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Timer
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule

class EventSubWebSocket(
    private val trustManager: X509TrustManager?,
    private val listener: Listener,
) {
    private var webSocket: WebSocket? = null
    private var pongTimer: Timer? = null
    private var timeout = 10000L
    private val handledMessageIds = mutableListOf<String>()

    fun connect(coroutineScope: CoroutineScope): Job {
        webSocket = WebSocket("wss://eventsub.wss.twitch.tv/ws", trustManager, WebSocketListener())
        return coroutineScope.launch(Dispatchers.IO) {
            webSocket?.start()
        }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        pongTimer?.cancel()
        job?.cancel()
        webSocket?.disconnect()
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

    interface Listener {
        suspend fun onConnect() {}
        suspend fun onWelcomeMessage(sessionId: String) {}
        suspend fun onChatMessage(event: JSONObject, timestamp: String?) {}
        suspend fun onUserNotice(event: JSONObject, timestamp: String?) {}
        suspend fun onClearChat(event: JSONObject, timestamp: String?) {}
        suspend fun onRoomState(event: JSONObject, timestamp: String?) {}
        suspend fun onDisconnect(message: String, fullMsg: String?) {}
    }

    private inner class WebSocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            listener.onConnect()
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            try {
                val json = if (message.isNotBlank()) JSONObject(message) else null
                if (json != null) {
                    val metadata = json.optJSONObject("metadata")
                    val messageId = if (metadata?.isNull("message_id") == false) metadata.optString("message_id").takeIf { it.isNotBlank() } else null
                    val timestamp = if (metadata?.isNull("message_timestamp") == false) metadata.optString("message_timestamp").takeIf { it.isNotBlank() } else null
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
                    when (metadata?.optString("message_type")) {
                        "notification" -> {
                            pongTimer?.cancel()
                            startPongTimer()
                            val payload = json.optJSONObject("payload")
                            val event = payload?.optJSONObject("event")
                            if (event != null) {
                                when (metadata.optString("subscription_type")) {
                                    "channel.chat.message" -> listener.onChatMessage(event, timestamp)
                                    "channel.chat.notification" -> listener.onUserNotice(event, timestamp)
                                    "channel.chat.clear" -> listener.onClearChat(event, timestamp)
                                    "channel.chat_settings.update" -> listener.onRoomState(event, timestamp)
                                }
                            }
                        }
                        "session_keepalive" -> {
                            pongTimer?.cancel()
                            startPongTimer()
                        }
                        "session_reconnect" -> {
                            //val payload = json.optJSONObject("payload")
                            //val session = payload?.optJSONObject("session")
                            //val reconnectUrl = if (session?.isNull("reconnect_url") == false) session.optString("reconnect_url").takeIf { it.isNotBlank() } else null
                            pongTimer?.cancel()
                            webSocket.disconnect()
                        }
                        "session_welcome" -> {
                            val payload = json.optJSONObject("payload")
                            val session = payload?.optJSONObject("session")
                            if (session?.isNull("keepalive_timeout_seconds") == false) {
                                session.optInt("keepalive_timeout_seconds").takeIf { it > 0 }?.let {
                                    timeout = it * 1000L
                                }
                            }
                            pongTimer?.cancel()
                            startPongTimer()
                            val sessionId = session?.optString("id")
                            if (!sessionId.isNullOrBlank()) {
                                listener.onWelcomeMessage(sessionId)
                            }
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
