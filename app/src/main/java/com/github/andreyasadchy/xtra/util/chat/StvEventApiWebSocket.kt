package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.util.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.net.ssl.X509TrustManager

class StvEventApiWebSocket(
    private val channelId: String,
    private val trustManager: X509TrustManager?,
    private val listener: Listener,
) {
    private var webSocket: WebSocket? = null

    fun connect(coroutineScope: CoroutineScope): Job {
        webSocket = WebSocket(
            url = "wss://events.7tv.io/v3",
            trustManager = trustManager,
            listener = WebSocketListener(),
            headers = mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME)
        )
        webSocket?.coroutineScope = coroutineScope
        return coroutineScope.launch(Dispatchers.IO) {
            webSocket?.start()
        }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        job?.cancel()
        webSocket?.disconnect()
    }

    private suspend fun subscribe() = withContext(Dispatchers.IO) {
        listOf(
            "emote_set.*",
            "cosmetic.*",
            "entitlement.*",
        ).forEach { type ->
            val message = JSONObject().apply {
                put("op", OPCODE_SUBSCRIBE)
                put("d", JSONObject().apply {
                    put("type", type)
                    put("condition", JSONObject().apply {
                        put("ctx", "channel")
                        put("platform", "TWITCH")
                        put("id", channelId)
                    })
                })
            }.toString()
            webSocket?.write(message)
        }
    }

    interface Listener {
        suspend fun onConnect() {}
        suspend fun onEmoteSetUpdate(body: JSONObject) {}
        suspend fun onCosmetic(body: JSONObject) {}
        suspend fun onEntitlement(body: JSONObject) {}
        suspend fun onUpdatePresence(sessionId: String) {}
        suspend fun onDisconnect(message: String, fullMsg: String?) {}
    }

    private inner class WebSocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            subscribe()
            listener.onConnect()
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            try {
                val json = if (message.isNotBlank()) JSONObject(message) else null
                when (json?.optInt("op")) {
                    OPCODE_DISPATCH -> {
                        val data = json.optJSONObject("d")
                        val type = data?.optString("type")
                        val body = data?.optJSONObject("body")
                        if (type != null && body != null) {
                            when (type) {
                                "emote_set.update" -> listener.onEmoteSetUpdate(body)
                                "cosmetic.create" -> listener.onCosmetic(body)
                                "entitlement.create" -> listener.onEntitlement(body)
                            }
                        }
                    }
                    OPCODE_HELLO -> {
                        val data = json.optJSONObject("d")
                        val sessionId = if (data?.isNull("session_id") == false) data.optString("session_id").takeIf { it.isNotBlank() } else null
                        if (sessionId != null) {
                            listener.onUpdatePresence(sessionId)
                        }
                    }
                    OPCODE_RECONNECT -> {
                        webSocket.disconnect()
                    }
                }
            } catch (e: Exception) {

            }
        }

        override suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String?) {
            listener.onDisconnect(message, fullMsg)
        }
    }

    companion object {
        private const val OPCODE_DISPATCH = 0
        private const val OPCODE_HELLO = 1
        private const val OPCODE_RECONNECT = 4
        private const val OPCODE_SUBSCRIBE = 35
    }
}
