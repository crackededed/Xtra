package com.github.andreyasadchy.xtra.util

import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import androidx.annotation.RequiresExtension
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.chromium.net.CronetException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetworkUtils {
    private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
    private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    private const val BYTE_BUFFER_CAPACITY = 32 * 1024

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val IDLE_TIMEOUT_MS = 60_000L
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val timeoutQueue = PriorityQueue<Timeout>()
    private var timeoutThread: TimeoutThread? = null

    fun interface ProgressListener {
        fun update(bytesRead: Int)
    }

    class HttpEngineResponse(
        val info: UrlResponseInfo,
        val body: ByteArray,
    )

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class ByteArrayUrlCallback(
        val continuation: Continuation<HttpEngineResponse>,
        val timeout: HttpEngineTimeout,
        val progressListener: ProgressListener? = null,
    ): UrlRequest.Callback {
        private lateinit var mResponseBodyStream: ByteArrayOutputStream
        private lateinit var mResponseBodyChannel: WritableByteChannel

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            val bodyLength = info.headers.asMap[CONTENT_LENGTH_HEADER_NAME]?.takeIf { it.size == 1 }?.getOrNull(0)?.toLongOrNull() ?: -1
            require(bodyLength <= MAX_ARRAY_SIZE) { "The body is too large and wouldn't fit in a byte array!" }
            mResponseBodyStream = if (bodyLength >= 0) {
                ByteArrayOutputStream(bodyLength.toInt())
            } else {
                ByteArrayOutputStream()
            }
            mResponseBodyChannel = Channels.newChannel(mResponseBodyStream)
            request.read(ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            mResponseBodyChannel.write(byteBuffer)
            byteBuffer.clear()
            timeout.updateTimeout()
            progressListener?.update(mResponseBodyStream.size())
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            timeout.stop()
            continuation.resume(HttpEngineResponse(info, mResponseBodyStream.toByteArray()))
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
            timeout.stop()
            continuation.resumeWithException(error)
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class ByteArrayUploadProvider(data: ByteArray, offset: Int = 0, length: Int = data.size): UploadDataProvider() {
        private val mUploadBuffer = ByteBuffer.wrap(data, offset, length).slice()

        override fun getLength(): Long {
            return mUploadBuffer.limit().toLong()
        }

        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            check(byteBuffer.hasRemaining())
            if (byteBuffer.remaining() >= mUploadBuffer.remaining()) {
                byteBuffer.put(mUploadBuffer)
            } else {
                val oldLimit = mUploadBuffer.limit()
                mUploadBuffer.limit(mUploadBuffer.position() + byteBuffer.remaining())
                byteBuffer.put(mUploadBuffer)
                mUploadBuffer.limit(oldLimit)
            }
            uploadDataSink.onReadSucceeded(false)
        }

        override fun rewind(uploadDataSink: UploadDataSink) {
            mUploadBuffer.position(0)
            uploadDataSink.onRewindSucceeded()
        }
    }

    class CronetResponse(
        val info: org.chromium.net.UrlResponseInfo,
        val body: ByteArray,
    )

    class ByteArrayCronetCallback(
        val continuation: Continuation<CronetResponse>,
        val timeout: CronetTimeout,
        val progressListener: ProgressListener? = null,
    ): org.chromium.net.UrlRequest.Callback() {
        private lateinit var mResponseBodyStream: ByteArrayOutputStream
        private lateinit var mResponseBodyChannel: WritableByteChannel

        override fun onRedirectReceived(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
            val bodyLength = info.allHeaders[CONTENT_LENGTH_HEADER_NAME]?.takeIf { it.size == 1 }?.getOrNull(0)?.toLongOrNull() ?: -1
            require(bodyLength <= MAX_ARRAY_SIZE) { "The body is too large and wouldn't fit in a byte array!" }
            mResponseBodyStream = if (bodyLength >= 0) {
                ByteArrayOutputStream(bodyLength.toInt())
            } else {
                ByteArrayOutputStream()
            }
            mResponseBodyChannel = Channels.newChannel(mResponseBodyStream)
            request.read(ByteBuffer.allocateDirect(BYTE_BUFFER_CAPACITY))
        }

        override fun onReadCompleted(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            mResponseBodyChannel.write(byteBuffer)
            byteBuffer.clear()
            timeout.updateTimeout()
            progressListener?.update(mResponseBodyStream.size())
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
            timeout.stop()
            continuation.resume(CronetResponse(info, mResponseBodyStream.toByteArray()))
        }

        override fun onFailed(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?, error: CronetException) {
            timeout.stop()
            continuation.resumeWithException(error)
        }

        override fun onCanceled(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo?) {
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    class HttpEngineTimeout(timeout: Long = DEFAULT_TIMEOUT_MS): Timeout(timeout) {
        lateinit var request: UrlRequest
        lateinit var continuation: Continuation<HttpEngineResponse>

        fun start(request: UrlRequest, continuation: Continuation<HttpEngineResponse>) {
            this.request = request
            this.continuation = continuation
            updateTimeout()
        }

        override fun timeout() {
            request.cancel()
            continuation.resumeWithException(IOException("Timed out"))
        }
    }

    class CronetTimeout(timeout: Long = DEFAULT_TIMEOUT_MS): Timeout(timeout) {
        lateinit var request: org.chromium.net.UrlRequest
        lateinit var continuation: Continuation<CronetResponse>

        fun start(request: org.chromium.net.UrlRequest, continuation: Continuation<CronetResponse>) {
            this.request = request
            this.continuation = continuation
            updateTimeout()
        }

        override fun timeout() {
            request.cancel()
            continuation.resumeWithException(IOException("Timed out"))
        }
    }

    abstract class Timeout(val timeout: Long): Comparable<Timeout> {
        var timeoutAt = System.currentTimeMillis() + timeout

        fun updateTimeout() {
            lock.withLock {
                timeoutQueue.remove(this)
                timeoutAt = System.currentTimeMillis() + timeout
                timeoutQueue.add(this)
                if (timeoutQueue.peek() == this) {
                    condition.signal()
                    if (timeoutThread == null) {
                        val thread = TimeoutThread()
                        timeoutThread = thread
                        thread.start()
                    }
                }
            }
        }

        fun stop() {
            lock.withLock {
                timeoutQueue.remove(this)
                if (timeoutQueue.peek() == this) {
                    condition.signal()
                }
            }
        }

        abstract fun timeout()

        override fun compareTo(other: Timeout): Int {
            return 0L.compareTo(other.timeoutAt - timeoutAt)
        }
    }

    private class TimeoutThread: Thread("TimeoutThread") {
        init {
            isDaemon = true
        }

        override fun run() {
            while (true) {
                try {
                    lock.withLock {
                        while (true) {
                            val item = timeoutQueue.peek()
                            if (item == null) {
                                val startTime = System.currentTimeMillis()
                                condition.await(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                if (timeoutQueue.peek() == null && System.currentTimeMillis() - startTime >= IDLE_TIMEOUT_MS) {
                                    break
                                }
                            } else {
                                val waitTime = item.timeoutAt - System.currentTimeMillis()
                                if (waitTime > 0) {
                                    condition.await(waitTime, TimeUnit.MILLISECONDS)
                                } else {
                                    timeoutQueue.remove().timeout()
                                }
                            }
                        }
                    }
                    break
                } catch (_: InterruptedException) {
                }
            }
            timeoutThread = null
        }
    }

    class ProgressInterceptor(val progressListener: ProgressListener?): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            return response.newBuilder().apply {
                body(
                    object : ResponseBody() {
                        private var bufferedSource: BufferedSource? = null

                        override fun contentType() = response.body.contentType()

                        override fun contentLength() = response.body.contentLength()

                        override fun source(): BufferedSource {
                            return bufferedSource ?: object : ForwardingSource(response.body.source()) {
                                private var totalBytesRead = 0L

                                override fun read(sink: Buffer, byteCount: Long): Long {
                                    val bytesRead = super.read(sink, byteCount)
                                    if (bytesRead != -1L) {
                                        totalBytesRead += bytesRead
                                        progressListener?.update(totalBytesRead.toInt())
                                    }
                                    return bytesRead
                                }
                            }.buffer().also { bufferedSource = it }
                        }
                    }
                )
            }.build()
        }
    }

    suspend fun Call.executeAsync(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                this.cancel()
            }
            this.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: okio.IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response) { _, value, _ ->
                            value.closeQuietly()
                        }
                    }
                },
            )
        }
}