package com.github.andreyasadchy.xtra.util.chat

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

class ChatReadIRC(
    private val useSSL: Boolean,
    private val channelLogin: String,
    private val trustManager: X509TrustManager?,
    private val listener: ChatReadWebSocket.Listener,
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                connect()
                var line = reader?.readLine()
                while (line != null) {
                    line.run {
                        when {
                            contains("PRIVMSG") -> listener.onChatMessage(this, false)
                            contains("USERNOTICE") -> listener.onChatMessage(this, true)
                            contains("CLEARMSG") -> listener.onClearMessage(this)
                            contains("CLEARCHAT") -> listener.onClearChat(this)
                            contains("NOTICE") -> listener.onNotice(this)
                            contains("ROOMSTATE") -> listener.onRoomState(this)
                            contains("USERSTATE") -> listener.onUserState(this)
                            startsWith("PING") -> {
                                write("PONG :tmi.twitch.tv")
                                writer?.flush()
                            }
                        }
                    }
                    line = reader?.readLine()
                }
            } catch (e: Exception) {
                if (socket?.isClosed != true && e.message != "Connection reset" && e.message != "recvfrom failed: ECONNRESET (Connection reset by peer)") {
                    listener.onDisconnect(e.toString(), e.stackTraceToString())
                }
            }
            close()
            delay(1000)
        }
    }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        socket = if (useSSL) {
            val socketFactory = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> SSLSocketFactory.getDefault()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> SSLContext.getDefault().socketFactory
                else -> {
                    val sslContext = SSLContext.getInstance("TLSv1.3")
                    sslContext.init(null, arrayOf(trustManager), null)
                    sslContext.socketFactory
                }
            }
            socketFactory.createSocket("irc.twitch.tv", 6697)
        } else {
            Socket("irc.twitch.tv", 6667)
        }
        reader = BufferedReader(InputStreamReader(socket?.inputStream))
        writer = BufferedWriter(OutputStreamWriter(socket?.outputStream))
        write("CAP REQ :twitch.tv/tags twitch.tv/commands")
        write("NICK justinfan${Random.nextInt(1000, 10000)}")
        write("JOIN #$channelLogin")
        writer?.flush()
        listener.onConnect()
    }

    private suspend fun write(message: String) = withContext(Dispatchers.IO) {
        writer?.write(message + System.lineSeparator())
    }

    suspend fun disconnect(job: Job?) = withContext(Dispatchers.IO) {
        job?.cancel()
        if (socket?.isClosed == false) {
            try {
                socket?.close()
            } catch (e: Exception) {

            }
        }
    }

    private suspend fun close() = withContext(Dispatchers.IO) {
        try {
            socket?.close()
        } catch (e: Exception) {

        }
    }
}
