package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.util.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Timer
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule

class ChatWriteWebSocket(
    private val userLogin: String?,
    private val userToken: String?,
    private val channelLogin: String,
    private val trustManager: Lazy<X509TrustManager>,
    private val listener: ChatReadWebSocket.Listener,
) {
    private var webSocket: WebSocket? = null
    private var pingTimer: Timer? = null
    private var pongTimer: Timer? = null

    fun connect(coroutineScope: CoroutineScope): Job {
        webSocket = WebSocket("wss://irc-ws.chat.twitch.tv", trustManager, WebSocketListener())
        webSocket?.coroutineScope = coroutineScope
        return coroutineScope.launch(Dispatchers.IO) {
            webSocket?.start()
        }
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        pingTimer?.cancel()
        pongTimer?.cancel()
        job?.cancel()
        webSocket?.disconnect()
    }

    private suspend fun startPingTimer() = withContext(Dispatchers.IO) {
        pingTimer = Timer().apply {
            schedule(270000) {
                webSocket?.coroutineScope?.launch {
                    webSocket?.write("PING")
                    startPongTimer()
                }
            }
        }
    }

    private suspend fun startPongTimer() = withContext(Dispatchers.IO) {
        pongTimer = Timer().apply {
            schedule(10000) {
                webSocket?.coroutineScope?.launch {
                    webSocket?.disconnect()
                }
            }
        }
    }

    suspend fun send(message: CharSequence, replyId: String?) = withContext(Dispatchers.IO) {
        val reply = replyId?.let { "@reply-parent-msg-id=${it} " } ?: ""
        webSocket?.write("${reply}PRIVMSG #$channelLogin :$message")
    }

    private inner class WebSocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            webSocket.write("CAP REQ :twitch.tv/tags twitch.tv/commands")
            webSocket.write("PASS oauth:$userToken")
            webSocket.write("NICK $userLogin")
            webSocket.write("JOIN #$channelLogin")
            listener.onConnect()
            pingTimer?.cancel()
            pongTimer?.cancel()
            startPingTimer()
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            message.removeSuffix("\r\n").split("\r\n").forEach {
                when {
                    it.startsWith("PING") -> {
                        webSocket.write("PONG")
                    }
                    it.startsWith("PONG") -> {
                        pingTimer?.cancel()
                        pongTimer?.cancel()
                        startPingTimer()
                    }
                    it.startsWith("RECONNECT") -> {
                        pingTimer?.cancel()
                        pongTimer?.cancel()
                        webSocket.disconnect()
                    }
                    else -> {
                        val ircMessage = ChatUtils.parseIRCMessage(it)
                        when (ircMessage.command) {
                            "PRIVMSG" -> listener.onChatMessage(ircMessage, false)
                            "USERNOTICE" -> listener.onChatMessage(ircMessage, true)
                            "CLEARMSG" -> listener.onClearMessage(ircMessage)
                            "CLEARCHAT" -> listener.onClearChat(ircMessage)
                            "NOTICE" -> listener.onNotice(ircMessage)
                            "ROOMSTATE" -> listener.onRoomState(ircMessage)
                            "USERSTATE" -> listener.onUserState(ircMessage)
                        }
                    }
                }
            }
        }

        override suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String?) {
            listener.onDisconnect(message, fullMsg)
        }
    }
}
