package com.github.andreyasadchy.xtra.util

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Timer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.schedule
import kotlin.random.Random

class WebSocket(
    private val url: String,
    private val trustManager: X509TrustManager?,
    private val listener: Listener,
    private val headers: Map<String, String>? = null,
    private val sendPings: Boolean = false,
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var pingTimer: Timer? = null
    private var pongTimer: Timer? = null
    private var messageByteArray: ByteArray? = null
    private var useCompression = false
    private var nextFrameCompressed = false
    private var connectionAttempt = 0
    private var delayReconnect = false
    var isActive = true

    suspend fun start() = withContext(Dispatchers.IO) {
        do {
            try {
                connectionAttempt += 1
                connect()
                connectionAttempt = 0
                var end = false
                while (!end) {
                    end = readNextFrame()
                }
            } catch (e: Exception) {
                if (e is SSLHandshakeException) {
                    isActive = false
                    listener.onFailure(this@WebSocket, e)
                } else {
                    if (socket?.isClosed != true) {
                        listener.onFailure(this@WebSocket, e)
                    }
                }
            }
            close()
            if (connectionAttempt >= 20) {
                isActive = false
            }
            if (delayReconnect) {
                delayReconnect = false
                delay(60000)
            } else {
                delay(1000)
            }
        } while (isActive)
    }

    private fun connect() {
        val urlWithoutScheme = url.substringAfter("://")
        val host = urlWithoutScheme.substringBefore("/")
        val path = urlWithoutScheme.substringAfter('/', "")
        val socketFactory = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> SSLSocketFactory.getDefault()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> SSLContext.getDefault().socketFactory
            else -> {
                val sslContext = SSLContext.getInstance("TLSv1.3")
                sslContext.init(null, arrayOf(trustManager), null)
                sslContext.socketFactory
            }
        }
        socket = socketFactory.createSocket(host, 443)
        inputStream = socket?.inputStream
        outputStream = socket?.outputStream
        val reader = BufferedReader(InputStreamReader(inputStream))
        val writer = BufferedWriter(OutputStreamWriter(outputStream))
        val key = Base64.encodeToString(Random.nextBytes(16), Base64.NO_WRAP)
        writer.write("GET /$path HTTP/1.1\r\n")
        writer.write("Host: $host\r\n")
        writer.write("Upgrade: websocket\r\n")
        writer.write("Connection: Upgrade\r\n")
        writer.write("Sec-WebSocket-Key: $key\r\n")
        writer.write("Sec-WebSocket-Version: 13\r\n")
        writer.write("Sec-WebSocket-Extensions: permessage-deflate\r\n")
        headers?.forEach {
            writer.write("${it.key}: ${it.value}\r\n")
        }
        writer.write("\r\n")
        writer.flush()
        useCompression = false
        messageByteArray = null
        var validated = false
        var line = reader.readLine()
        if (!line.startsWith("HTTP/1.1 101", true)) {
            if (line.startsWith("HTTP/1.1 429", true)) {
                delayReconnect = true
            } else {
                isActive = false
            }
            throw Exception(line)
        }
        while (!line.isNullOrBlank()) {
            when {
                line.startsWith("Sec-WebSocket-Accept", true) -> {
                    val messageDigest = MessageDigest.getInstance("SHA-1")
                    messageDigest.update((key + ACCEPT_UUID).toByteArray())
                    val acceptString = Base64.encodeToString(messageDigest.digest(), Base64.NO_WRAP)
                    if (line.substringAfter(": ") == acceptString) {
                        validated = true
                    }
                }
                line.startsWith("Sec-WebSocket-Extensions", true) -> {
                    useCompression = line.substringAfter(": ").split(", ").find {
                        it.startsWith("permessage-deflate", true)
                    } != null
                }
            }
            line = reader.readLine()
        }
        if (!validated) {
            isActive = false
            throw Exception()
        }
        if (sendPings) {
            startPingTimer()
        }
        listener.onOpen(this)
    }

    private fun startPingTimer() {
        pingTimer = Timer().apply {
            schedule(270000) {
                if (isActive && socket?.isClosed != true) {
                    writeControlFrame(OPCODE_PING, byteArrayOf())
                    startPongTimer()
                }
            }
        }
    }

    private fun startPongTimer() {
        pongTimer = Timer().apply {
            schedule(10000) {
                try {
                    socket?.close()
                } catch (e: Exception) {

                }
            }
        }
    }

    private fun readNextFrame(): Boolean {
        val firstByte = inputStream!!.read()
        if (firstByte == -1) {
            return true
        }
        val isFinalFrame = firstByte and FIN_BIT != 0
        val compressed = useCompression && firstByte and COMPRESSED_BIT != 0
        val opcode = firstByte and OPCODE_BIT
        val isControlFrame = firstByte and OPCODE_CONTROL_FRAME != 0
        val secondByte = inputStream!!.read()
        val length = secondByte and LENGTH_BIT
        val frameLength = when (length) {
            PAYLOAD_SHORT -> {
                val size = 2
                val array = ByteArray(size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    inputStream?.readNBytes(array, 0, size)
                } else {
                    inputStream?.let {
                        var offset = 0
                        while (offset < size) {
                            val count = it.read(array, 0 + offset, size - offset)
                            if (count < 0) {
                                break
                            }
                            offset += count
                        }
                    }
                }
                ByteBuffer.wrap(array).short.toInt() and 0xffff
            }
            PAYLOAD_LONG -> {
                val size = 8
                val array = ByteArray(size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    inputStream?.readNBytes(array, 0, size)
                } else {
                    inputStream?.let {
                        var offset = 0
                        while (offset < size) {
                            val count = it.read(array, 0 + offset, size - offset)
                            if (count < 0) {
                                break
                            }
                            offset += count
                        }
                    }
                }
                ByteBuffer.wrap(array).long.toInt()
            }
            else -> length
        }
        val data = ByteArray(frameLength)
        if (frameLength > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                inputStream?.readNBytes(data, 0, frameLength)
            } else {
                inputStream?.let {
                    var offset = 0
                    while (offset < frameLength) {
                        val count = it.read(data, 0 + offset, frameLength - offset)
                        if (count < 0) {
                            break
                        }
                        offset += count
                    }
                }
            }
        }
        if (isControlFrame) {
            when (opcode) {
                OPCODE_PING -> {
                    writeControlFrame(OPCODE_PONG, data)
                }
                OPCODE_PONG -> {
                    if (sendPings) {
                        pingTimer?.cancel()
                        pongTimer?.cancel()
                        startPingTimer()
                    }
                }
                OPCODE_CLOSE -> {
                    if (isActive) {
                        val code = data.copyOf(2)
                        writeControlFrame(OPCODE_CLOSE, code)
                    }
                    close()
                }
            }
        } else {
            when (opcode) {
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    val messageData = if ((opcode == OPCODE_CONTINUATION && nextFrameCompressed) || compressed) {
                        val decompressedStream = ByteArrayOutputStream()
                        val inflater = Inflater(true)
                        val inflaterStream = InflaterOutputStream(decompressedStream, inflater)
                        inflaterStream.write(data)
                        inflaterStream.write(0x0000ffff)
                        inflaterStream.close()
                        decompressedStream.toByteArray()
                    } else {
                        data
                    }
                    messageByteArray.let {
                        messageByteArray = if (it != null) {
                            it + messageData
                        } else {
                            messageData
                        }
                    }
                    if (isFinalFrame) {
                        nextFrameCompressed = false
                        listener.onMessage(this, messageByteArray!!.decodeToString())
                        messageByteArray = null
                    } else {
                        if (opcode != OPCODE_CONTINUATION) {
                            nextFrameCompressed = compressed
                        }
                    }
                }
            }
        }
        return false
    }

    private fun writeControlFrame(opcode: Int, data: ByteArray) {
        val output = ByteArrayOutputStream()
        val firstByte = FIN_BIT or opcode
        output.write(firstByte)
        val dataSize = data.size
        val secondByte = MASKED_BIT or dataSize
        output.write(secondByte)
        val maskKey = Random.nextBytes(4)
        output.write(maskKey)
        if (dataSize > 0) {
            val maskedData = data.mapIndexed { index, byte ->
                (byte.toInt() xor maskKey[index % 4].toInt()).toByte()
            }.toByteArray()
            output.write(maskedData)
        }
        outputStream?.let { output.writeTo(it) }
    }

    suspend fun write(message: String) = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        var firstByte = FIN_BIT or OPCODE_TEXT
        val messageBytes = message.toByteArray()
        val data = if (useCompression && messageBytes.size >= MINIMUM_DEFLATE_SIZE) {
            firstByte = firstByte or COMPRESSED_BIT
            val compressedStream = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            val deflaterStream = DeflaterOutputStream(compressedStream, deflater)
            deflaterStream.write(messageBytes)
            deflaterStream.close()
            val compressedBytes = compressedStream.toByteArray()
            if (compressedBytes.takeLast(5) == EMPTY_DEFLATE_BLOCK) {
                compressedBytes.dropLast(4).toByteArray()
            } else {
                compressedBytes + 0x00
            }
        } else {
            messageBytes
        }
        output.write(firstByte)
        val dataSize = data.size
        when {
            dataSize <= PAYLOAD_BYTE_MAX -> {
                val secondByte = MASKED_BIT or dataSize
                output.write(secondByte)
            }
            dataSize <= PAYLOAD_SHORT_MAX -> {
                val secondByte = MASKED_BIT or PAYLOAD_SHORT
                output.write(secondByte)
                val sizeBytes = ByteBuffer.allocate(2).putShort(dataSize.toShort()).array()
                output.write(sizeBytes)
            }
            else -> {
                val secondByte = MASKED_BIT or PAYLOAD_LONG
                output.write(secondByte)
                val sizeBytes = ByteBuffer.allocate(8).putLong(dataSize.toLong()).array()
                output.write(sizeBytes)
            }
        }
        val maskKey = Random.nextBytes(4)
        output.write(maskKey)
        val maskedData = data.mapIndexed { index, byte ->
            (byte.toInt() xor maskKey[index % 4].toInt()).toByte()
        }.toByteArray()
        output.write(maskedData)
        if (socket?.isClosed == false) {
            outputStream?.let { output.writeTo(it) }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        isActive = false
        disconnect()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        pingTimer?.cancel()
        pongTimer?.cancel()
        if (socket?.isClosed == false) {
            try {
                val currentSocket = socket
                writeControlFrame(OPCODE_CLOSE, ByteBuffer.allocate(2).putShort(1000).array())
                delay(5000)
                currentSocket?.close()
            } catch (e: Exception) {

            }
        }
    }

    private fun close() {
        pingTimer?.cancel()
        pongTimer?.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {

        }
    }

    interface Listener {
        fun onOpen(webSocket: WebSocket) {}
        fun onMessage(webSocket: WebSocket, message: String) {}
        fun onFailure(webSocket: WebSocket, throwable: Throwable) {}
    }

    companion object {
        private const val ACCEPT_UUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val FIN_BIT = 128
        private const val COMPRESSED_BIT = 64
        private const val OPCODE_BIT = 15
        private const val OPCODE_CONTROL_FRAME = 8
        private const val MASKED_BIT = 128
        private const val LENGTH_BIT = 127
        private const val PAYLOAD_BYTE_MAX = 125L
        private const val PAYLOAD_SHORT = 126
        private const val PAYLOAD_SHORT_MAX = 0xffffL
        private const val PAYLOAD_LONG = 127
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xa
        private val EMPTY_DEFLATE_BLOCK = listOf(0x00, 0x00, 0x00, 0xFF, 0xFF)
        private const val MINIMUM_DEFLATE_SIZE = 1024
    }
}