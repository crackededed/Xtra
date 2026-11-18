package com.github.andreyasadchy.xtra.util.chat

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ChatProxy(
    private val type: Type,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String,
    private val password: String,
) {
    enum class Type { SOCKS5, HTTP, HTTPS }

    fun openSocket(target: String, targetPort: Int, tls: Boolean): Socket =
        TunnelSocket().apply { connect(InetSocketAddress.createUnresolved(target, targetPort), TIMEOUT) }.let {
            try {
                if (tls) secure(it, target, targetPort) else it
            } catch (e: Exception) {
                it.close()
                throw e
            }
        }

    private val trustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as java.security.KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }

    private val tlsFactory by lazy {
        SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
            init(null, arrayOf(trustManager), null)
        }.socketFactory.also { Conscrypt.setUseEngineSocket(it, true) }
    }

    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .sslSocketFactory(tlsFactory, trustManager)
        // A CONNECT tunnel is tied to one host even when the certificate covers other hosts.
        .protocols(listOf(Protocol.HTTP_1_1))
        // Keep the hostname for CONNECT and SOCKS5. The tunnel resolves it at the proxy.
        .dns { hostname -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1))) }
        .socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = TunnelSocket()
            override fun createSocket(host: String, port: Int): Socket = openSocket(host, port, false)
            override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostName, port)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                TunnelSocket().apply {
                    bind(InetSocketAddress(localHost, localPort))
                    connect(InetSocketAddress.createUnresolved(host, port), TIMEOUT)
                }
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
                createSocket(host.hostName, port, localHost, localPort)
        })
        .build()

    private inner class TunnelSocket : Socket(Proxy.NO_PROXY) {
        private var transport: SSLSocket? = null

        override fun connect(endpoint: SocketAddress) = connect(endpoint, TIMEOUT)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as InetSocketAddress
            val readTimeout = soTimeout
            try {
                super.connect(InetSocketAddress(proxyHost, proxyPort), timeout.takeIf { it > 0 } ?: TIMEOUT)
                soTimeout = TIMEOUT
                if (type == Type.HTTPS) {
                    transport = secure(this, proxyHost, proxyPort)
                }
                if (type == Type.SOCKS5) {
                    connectSocks5(target.hostString, target.port)
                } else {
                    connectHttp(target.hostString, target.port)
                }
                soTimeout = readTimeout
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        override fun getInputStream() = transport?.inputStream ?: super.getInputStream()
        override fun getOutputStream() = transport?.outputStream ?: super.getOutputStream()

        override fun close() {
            val tls = transport
            transport = null
            try {
                tls?.close()
            } finally {
                super.close()
            }
        }

        private fun connectHttp(target: String, port: Int) {
            val authority = if (':' in target) "[$target]:$port" else "$target:$port"
            val request = buildString {
                append("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n")
                if (username.isNotEmpty()) {
                    append("Proxy-Authorization: ${Credentials.basic(username, password)}\r\n")
                }
                append("\r\n")
            }
            outputStream.write(request.toByteArray(Charsets.US_ASCII))
            outputStream.flush()
            val input = inputStream
            // Read only through the header terminator to leave tunneled bytes in the stream.
            val header = StringBuilder()
            while (header.length < 32768) {
                val byte = input.read()
                if (byte == -1) throw EOFException("Proxy closed the connection")
                header.append(byte.toChar())
                if (header.endsWith("\r\n\r\n")) {
                    val code = header.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
                    if (code == 407) throw IOException("HTTP proxy authentication failed")
                    if (code == null || code !in 200..299) throw IOException("HTTP proxy rejected CONNECT ($code)")
                    return
                }
            }
            throw IOException("HTTP proxy response headers are too large")
        }

        private fun connectSocks5(target: String, port: Int) {
            val input = DataInputStream(inputStream)
            val output = outputStream
            val method = if (username.isEmpty()) 0 else 2
            output.write(byteArrayOf(5, 1, method.toByte()))
            output.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method) {
                throw IOException("SOCKS5 proxy rejected the authentication method")
            }
            if (method == 2) {
                val user = username.toByteArray()
                val pass = password.toByteArray()
                output.write(byteArrayOf(1, user.size.toByte()))
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                output.flush()
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                    throw IOException("SOCKS5 proxy authentication failed")
                }
            }
            val address = if (':' in target) {
                byteArrayOf(4) + InetAddress.getByName(target).address
            } else {
                val name = IDN.toASCII(target).toByteArray(Charsets.US_ASCII)
                if (name.size !in 1..255) throw IOException("Invalid SOCKS5 destination")
                byteArrayOf(3, name.size.toByte()) + name
            }
            output.write(byteArrayOf(5, 1, 0))
            output.write(address)
            output.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
            output.flush()
            if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS5 response")
            val status = input.readUnsignedByte()
            if (status != 0) throw IOException("SOCKS5 proxy rejected CONNECT ($status)")
            input.readUnsignedByte()
            val addressLength = when (input.readUnsignedByte()) {
                1 -> 4
                3 -> input.readUnsignedByte()
                4 -> 16
                else -> throw IOException("Invalid SOCKS5 address type")
            }
            input.readFully(ByteArray(addressLength + 2))
        }
    }

    private fun secure(socket: Socket, host: String, port: Int): SSLSocket {
        val ssl = tlsFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            // Verify the hostname against the platform trust store for both TLS layers.
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            val readTimeout = ssl.soTimeout
            ssl.soTimeout = TIMEOUT
            ssl.startHandshake()
            ssl.soTimeout = readTimeout
            return ssl
        } catch (e: Exception) {
            ssl.close()
            throw e
        }
    }

    companion object {
        private const val TIMEOUT = 10000

        fun parse(type: String?, host: String?, port: String?, username: String?, password: String?): ChatProxy? {
            val proxyType = Type.entries.find { it.name == type } ?: return null
            val proxyHost = host.orEmpty().trim().removeSurrounding("[", "]")
            val normalizedHost = try {
                if (':' in proxyHost) {
                    if (InetAddress.getByName(proxyHost).address.size != 16) return null
                    proxyHost
                } else {
                    IDN.toASCII(proxyHost, IDN.USE_STD3_ASCII_RULES)
                }
            } catch (_: Exception) {
                return null
            }
            if (normalizedHost.isBlank()) return null
            val proxyPort = port.orEmpty().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val user = username.orEmpty()
            val pass = password.orEmpty()
            if (user.isEmpty() && pass.isNotEmpty()) return null
            if (proxyType == Type.SOCKS5) {
                if (user.isNotEmpty() && (user.toByteArray().size > 255 || pass.toByteArray().size !in 1..255)) return null
            } else if (':' in user) {
                return null
            }
            return ChatProxy(proxyType, normalizedHost, proxyPort, user, pass)
        }
    }
}
