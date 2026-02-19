package com.github.andreyasadchy.xtra.util

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import kotlin.coroutines.cancellation.CancellationException
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

    suspend fun start() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                connectionAttempt += 1
                val error = connect()
                if (error) {
                    close()
                    return@withContext
                }
                connectionAttempt = 0
                var end = false
                while (!end) {
                    end = readNextFrame()
                }
            } catch (e: CancellationException) {
                ensureActive()
            } catch (e: SSLHandshakeException) {
                listener.onDisconnect(this@WebSocket, e.toString(), e.stackTraceToString())
                close()
                return@withContext
            } catch (e: Exception) {
                if (socket?.isClosed != true) {
                    listener.onDisconnect(this@WebSocket, e.toString(), e.stackTraceToString())
                }
            }
            close()
            if (connectionAttempt >= 20) {
                return@withContext
            }
            if (delayReconnect) {
                delayReconnect = false
                delay(60000)
            } else {
                delay(1000)
            }
        }
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
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
            listener.onDisconnect(this@WebSocket, line)
            if (line.startsWith("HTTP/1.1 429", true)) {
                delayReconnect = true
                return@withContext false
            } else {
                return@withContext true
            }
        }
        line = reader.readLine()
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
            listener.onDisconnect(this@WebSocket, "")
            return@withContext true
        }
        if (sendPings) {
            startPingTimer()
        }
        listener.onConnect(this@WebSocket)
        return@withContext false
    }

    private suspend fun startPingTimer() = withContext(Dispatchers.IO) {
        pingTimer = Timer().apply {
            schedule(270000) {
                if (socket?.isClosed == false) {
                    launch {
                        writeControlFrame(OPCODE_PING, byteArrayOf())
                        startPongTimer()
                    }
                }
            }
        }
    }

    private suspend fun startPongTimer() = withContext(Dispatchers.IO) {
        pongTimer = Timer().apply {
            schedule(10000) {
                try {
                    socket?.close()
                } catch (e: Exception) {

                }
            }
        }
    }

    suspend fun readNextFrame(): Boolean = withContext(Dispatchers.IO) {
        val firstByte = inputStream!!.read()
        if (firstByte < 0) {
            return@withContext true
        }
        val isFinalFrame = firstByte and FIN_BIT != 0
        val compressed = useCompression && firstByte and COMPRESSED_BIT != 0
        val opcode = firstByte and OPCODE
        val isControlFrame = firstByte and OPCODE_CONTROL_FRAME != 0
        val secondByte = inputStream!!.read()
        if (secondByte < 0) {
            return@withContext true
        }
        val lengthBytes = secondByte and LENGTH
        val length = when (lengthBytes) {
            LENGTH_SHORT -> {
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
            LENGTH_LONG -> {
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
            else -> lengthBytes
        }
        val data = ByteArray(length)
        if (length > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                inputStream?.readNBytes(data, 0, length)
            } else {
                inputStream?.let {
                    var offset = 0
                    while (offset < length) {
                        val count = it.read(data, 0 + offset, length - offset)
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
                    val code = data.copyOf(2)
                    writeControlFrame(OPCODE_CLOSE, code)
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
                        listener.onMessage(this@WebSocket, messageByteArray!!.decodeToString())
                        messageByteArray = null
                    } else {
                        if (opcode != OPCODE_CONTINUATION) {
                            nextFrameCompressed = compressed
                        }
                    }
                }
            }
        }
        return@withContext false
    }

    private suspend fun writeControlFrame(opcode: Int, data: ByteArray) = withContext(Dispatchers.IO) {
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
        if (socket?.isClosed == false) {
            outputStream?.let { output.writeTo(it) }
        }
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
            dataSize <= LENGTH_BYTE_MAX -> {
                val secondByte = MASKED_BIT or dataSize
                output.write(secondByte)
            }
            dataSize <= LENGTH_SHORT_MAX -> {
                val secondByte = MASKED_BIT or LENGTH_SHORT
                output.write(secondByte)
                val sizeBytes = ByteBuffer.allocate(2).putShort(dataSize.toShort()).array()
                output.write(sizeBytes)
            }
            else -> {
                val secondByte = MASKED_BIT or LENGTH_LONG
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

    private suspend fun close() = withContext(Dispatchers.IO) {
        pingTimer?.cancel()
        pongTimer?.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {

        }
    }

    interface Listener {
        suspend fun onConnect(webSocket: WebSocket) {}
        suspend fun onMessage(webSocket: WebSocket, message: String) {}
        suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String? = null) {}
    }

    companion object {
        private const val ACCEPT_UUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val FIN_BIT = 128
        private const val COMPRESSED_BIT = 64
        private const val OPCODE = 15
        private const val OPCODE_CONTROL_FRAME = 8
        private const val MASKED_BIT = 128
        private const val LENGTH = 127
        private const val LENGTH_SHORT = 126
        private const val LENGTH_LONG = 127
        private const val LENGTH_BYTE_MAX = 125
        private const val LENGTH_SHORT_MAX = 0xffff
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xa
        private val EMPTY_DEFLATE_BLOCK = listOf(0x00, 0x00, 0x00, 0xFF, 0xFF)
        private const val MINIMUM_DEFLATE_SIZE = 1024
    }
}