package com.github.andreyasadchy.xtra.util

import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import androidx.annotation.RequiresExtension
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
object HttpEngineUtils {
    private const val CONTENT_LENGTH_HEADER_NAME = "Content-Length"
    private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    private const val BYTE_BUFFER_CAPACITY = 32 * 1024

    fun byteArrayUrlCallback(continuation: Continuation<Pair<UrlResponseInfo, ByteArray>>): UrlRequest.Callback {
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
}