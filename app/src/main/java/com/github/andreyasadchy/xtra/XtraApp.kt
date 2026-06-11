package com.github.andreyasadchy.xtra

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.DebugLogger
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.coil.CacheControlCacheStrategy
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okio.buffer
import okio.source
import org.chromium.net.apihelpers.UploadDataProviders
import org.conscrypt.Conscrypt
import java.security.Security

class XtraApp : Application(), SingletonImageLoader.Factory {

    companion object {
        lateinit var INSTANCE: Application
    }

    lateinit var xtraModule: XtraModule

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        xtraModule = XtraModule(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val conscrypt = Conscrypt.newProvider()
            Security.insertProviderAt(conscrypt, 1)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context).apply {
            if (BuildConfig.DEBUG) {
                logger(DebugLogger())
            }
            components {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                when {
                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        add(NetworkFetcher.Factory(
                            networkClient = {
                                object : NetworkClient {
                                    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                                        val requestBody = request.body?.let {
                                            val buffer = Buffer()
                                            it.writeTo(buffer)
                                            buffer.readByteArray()
                                        }
                                        val requestMillis = System.currentTimeMillis()
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                                request.url,
                                                xtraModule.cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation)
                                            ).apply {
                                                request.headers.asMap().forEach { entry ->
                                                    entry.value.forEach {
                                                        addHeader(entry.key, it)
                                                    }
                                                }
                                                requestBody?.let {
                                                    setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(requestBody), xtraModule.cronetExecutor.value)
                                                }
                                                setHttpMethod(request.method)
                                            }.build()
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                            }
                                        }
                                        val responseMillis = System.currentTimeMillis()
                                        return block(
                                            NetworkResponse(
                                                code = response.info.httpStatusCode,
                                                requestMillis = requestMillis,
                                                responseMillis = responseMillis,
                                                headers = NetworkHeaders.Builder().apply {
                                                    response.info.headers.asList.forEach {
                                                        add(it.key, it.value)
                                                    }
                                                }.build(),
                                                body = response.body.inputStream().source().buffer().let(::NetworkResponseBody),
                                            )
                                        )
                                    }
                                }
                            },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        add(NetworkFetcher.Factory(
                            networkClient = {
                                object : NetworkClient {
                                    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                                        val requestBody = request.body?.let {
                                            val buffer = Buffer()
                                            it.writeTo(buffer)
                                            buffer.readByteArray()
                                        }
                                        val requestMillis = System.currentTimeMillis()
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                                request.url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation),
                                                xtraModule.cronetExecutor.value
                                            ).apply {
                                                request.headers.asMap().forEach { entry ->
                                                    entry.value.forEach {
                                                        addHeader(entry.key, it)
                                                    }
                                                }
                                                requestBody?.let {
                                                    setUploadDataProvider(UploadDataProviders.create(requestBody), xtraModule.cronetExecutor.value)
                                                }
                                                setHttpMethod(request.method)
                                            }.build()
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                            }
                                        }
                                        val responseMillis = System.currentTimeMillis()
                                        return block(
                                            NetworkResponse(
                                                code = response.info.httpStatusCode,
                                                requestMillis = requestMillis,
                                                responseMillis = responseMillis,
                                                headers = NetworkHeaders.Builder().apply {
                                                    response.info.allHeadersAsList.forEach {
                                                        add(it.key, it.value)
                                                    }
                                                }.build(),
                                                body = response.body.inputStream().source().buffer().let(::NetworkResponseBody),
                                            )
                                        )
                                    }
                                }
                            },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                    else -> {
                        add(OkHttpNetworkFetcherFactory(
                            callFactory = { xtraModule.okHttpClient.value },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                }
            }
        }.build()
    }
}
