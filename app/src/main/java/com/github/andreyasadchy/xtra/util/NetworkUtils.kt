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
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Closeable
import okio.ForwardingSource
import okio.buffer
import org.chromium.net.CronetException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetworkUtils {
    private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
    private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    private const val BYTE_BUFFER_CAPACITY = 32 * 1024

    fun interface ProgressListener {
        fun update(bytesRead: Int)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    fun byteArrayUrlCallback(continuation: Continuation<Pair<UrlResponseInfo, ByteArray>>, progressListener: ProgressListener? = null): UrlRequest.Callback {
        return object : UrlRequest.Callback {
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
                progressListener?.update(mResponseBodyStream.size())
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                continuation.resume(Pair(info, mResponseBodyStream.toByteArray()))
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                continuation.resumeWithException(error)
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                continuation.resumeWithException(IOException("The request was canceled!"))
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    fun byteArrayUploadProvider(data: ByteArray, offset: Int = 0, length: Int = data.size): UploadDataProvider {
        return object : UploadDataProvider() {
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
    }

    fun byteArrayCronetUrlCallback(continuation: Continuation<Pair<org.chromium.net.UrlResponseInfo, ByteArray>>, progressListener: ProgressListener? = null): org.chromium.net.UrlRequest.Callback {
        return object : org.chromium.net.UrlRequest.Callback() {
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
                progressListener?.update(mResponseBodyStream.size())
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
                continuation.resume(Pair(info, mResponseBodyStream.toByteArray()))
            }

            override fun onFailed(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo, error: CronetException) {
                continuation.resumeWithException(error)
            }

            override fun onCanceled(request: org.chromium.net.UrlRequest, info: org.chromium.net.UrlResponseInfo) {
                continuation.resumeWithException(IOException("The request was canceled!"))
            }
        }
    }

    fun progressInterceptor(progressListener: ProgressListener?): Interceptor {
        return Interceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder().apply {
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

    fun Closeable.closeQuietly() {
        try {
            close()
        } catch (rethrown: RuntimeException) {
            throw rethrown
        } catch (_: Exception) {
        }
    }

    val Response.body
        get() = this.body()!!

    val Response.request: Request
        get() = this.request()

    val Response.code
        get() = this.code()

    fun Map<String, String>.toHeaders(): Headers {
        return Headers.of(this)
    }

    fun String.toRequestBody(contentType: MediaType? = null): RequestBody {
        return RequestBody.create(contentType, toByteArray())
    }
}