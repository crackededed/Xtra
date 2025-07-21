package com.github.andreyasadchy.xtra

import android.app.Application
import android.net.http.HttpEngine
import android.net.http.UrlResponseInfo
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.coil.CacheControlCacheStrategy
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import com.github.andreyasadchy.xtra.util.prefs
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Buffer
import okio.buffer
import okio.source
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UploadDataProviders
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine


@HiltAndroidApp
class XtraApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    companion object {
        lateinit var INSTANCE: Application
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @Inject
    @JvmField
    var httpEngine: Lazy<HttpEngine>? = null

    @Inject
    @JvmField
    var cronetEngine: Lazy<CronetEngine>? = null

    @Inject
    lateinit var cronetExecutor: ExecutorService

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context).apply {
            if (BuildConfig.DEBUG) {
                logger(DebugLogger())
            }
            components {
                val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
                when {
                    networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
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
                                        val response = suspendCoroutine<Pair<UrlResponseInfo, ByteArray>> { continuation ->
                                            httpEngine!!.get().newUrlRequestBuilder(request.url, cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                                                request.headers.asMap().forEach { entry ->
                                                    entry.value.forEach {
                                                        addHeader(entry.key, it)
                                                    }
                                                }
                                                requestBody?.let {
                                                    setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(requestBody), cronetExecutor)
                                                }
                                                setHttpMethod(request.method)
                                            }.build().start()
                                        }
                                        val responseMillis = System.currentTimeMillis()
                                        return block(
                                            NetworkResponse(
                                                code = response.first.httpStatusCode,
                                                requestMillis = requestMillis,
                                                responseMillis = responseMillis,
                                                headers = NetworkHeaders.Builder().apply {
                                                    response.first.headers.asList.forEach {
                                                        add(it.key, it.value)
                                                    }
                                                }.build(),
                                                body = response.second.inputStream().source().buffer().let(::NetworkResponseBody),
                                            )
                                        )
                                    }
                                }
                            },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                    networkLibrary == "Cronet" && cronetEngine != null -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            add(NetworkFetcher.Factory(
                                networkClient = {
                                    object : NetworkClient {
                                        override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                                            val cronetRequest = UrlRequestCallbacks.forByteArrayBody(RedirectHandlers.alwaysFollow())
                                            cronetEngine!!.get().newUrlRequestBuilder(request.url, cronetRequest.callback, cronetExecutor).apply {
                                                request.headers.asMap().forEach { entry ->
                                                    entry.value.forEach {
                                                        addHeader(entry.key, it)
                                                    }
                                                }
                                                request.body?.let {
                                                    val buffer = Buffer()
                                                    it.writeTo(buffer)
                                                    setUploadDataProvider(UploadDataProviders.create(buffer.readByteArray()), cronetExecutor)
                                                }
                                                setHttpMethod(request.method)
                                            }.build().start()
                                            val requestMillis = System.currentTimeMillis()
                                            val response = cronetRequest.future.get()
                                            val responseMillis = System.currentTimeMillis()
                                            return block(
                                                NetworkResponse(
                                                    code = response.urlResponseInfo.httpStatusCode,
                                                    requestMillis = requestMillis,
                                                    responseMillis = responseMillis,
                                                    headers = NetworkHeaders.Builder().apply {
                                                        response.urlResponseInfo.allHeadersAsList.forEach {
                                                            add(it.key, it.value)
                                                        }
                                                    }.build(),
                                                    body = (response.responseBody as ByteArray).inputStream().source().buffer().let(::NetworkResponseBody),
                                                )
                                            )
                                        }
                                    }
                                },
                                cacheStrategy = { CacheControlCacheStrategy() }
                            ))
                        } else {
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
                                            val response = suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                                                cronetEngine!!.get().newUrlRequestBuilder(request.url, getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                                                    request.headers.asMap().forEach { entry ->
                                                        entry.value.forEach {
                                                            addHeader(entry.key, it)
                                                        }
                                                    }
                                                    requestBody?.let {
                                                        setUploadDataProvider(UploadDataProviders.create(requestBody), cronetExecutor)
                                                    }
                                                    setHttpMethod(request.method)
                                                }.build().start()
                                            }
                                            val responseMillis = System.currentTimeMillis()
                                            return block(
                                                NetworkResponse(
                                                    code = response.first.httpStatusCode,
                                                    requestMillis = requestMillis,
                                                    responseMillis = responseMillis,
                                                    headers = NetworkHeaders.Builder().apply {
                                                        response.first.allHeadersAsList.forEach {
                                                            add(it.key, it.value)
                                                        }
                                                    }.build(),
                                                    body = response.second.inputStream().source().buffer().let(::NetworkResponseBody),
                                                )
                                            )
                                        }
                                    }
                                },
                                cacheStrategy = { CacheControlCacheStrategy() }
                            ))
                        }
                    }
                    else -> {
                        add(OkHttpNetworkFetcherFactory(
                            callFactory = { okHttpClient },
                            cacheStrategy = { CacheControlCacheStrategy() }
                        ))
                    }
                }
            }
        }.build()
    }
}
