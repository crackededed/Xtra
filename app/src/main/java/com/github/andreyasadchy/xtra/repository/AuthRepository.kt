package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.body
import com.github.andreyasadchy.xtra.util.NetworkUtils.code
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.NetworkUtils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.UploadDataProviders
import java.util.concurrent.ExecutorService

class AuthRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    suspend fun validate(networkLibrary: String?, token: String): ValidationResponse = withContext(Dispatchers.IO) {
        val url = "https://id.twitch.tv/oauth2/validate"
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation)
                    ).apply {
                        addHeader("Authorization", token)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
                if (response.info.httpStatusCode != 401) {
                    json.decodeFromString<ValidationResponse>(response.body.decodeToString())
                } else {
                    throw IllegalStateException("401")
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation),
                        cronetExecutor.value
                    ).apply {
                        addHeader("Authorization", token)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
                if (response.info.httpStatusCode != 401) {
                    json.decodeFromString<ValidationResponse>(response.body.decodeToString())
                } else {
                    throw IllegalStateException("401")
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    header("Authorization", token)
                }.build()).executeAsync().use { response ->
                    if (response.code != 401) {
                        json.decodeFromString<ValidationResponse>(response.body.string())
                    } else {
                        throw IllegalStateException("401")
                    }
                }
            }
        }
    }

    suspend fun revoke(networkLibrary: String?, body: String) = withContext(Dispatchers.IO) {
        val url = "https://id.twitch.tv/oauth2/revoke"
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                suspendCancellableCoroutine { continuation ->
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation)
                    ).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                suspendCancellableCoroutine<NetworkUtils.CronetResponse> { continuation ->
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation),
                        cronetExecutor.value
                    ).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    post(body.toRequestBody())
                }.build()).executeAsync()
            }
        }
    }

    suspend fun getDeviceCode(networkLibrary: String?, body: String): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val url = "https://id.twitch.tv/oauth2/device"
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation)
                    ).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
                json.decodeFromString<DeviceCodeResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation),
                        cronetExecutor.value
                    ).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
                json.decodeFromString<DeviceCodeResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<DeviceCodeResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getToken(networkLibrary: String?, body: String): TokenResponse = withContext(Dispatchers.IO) {
        val url = "https://id.twitch.tv/oauth2/token"
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation)
                    ).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
                json.decodeFromString<TokenResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation),
                        cronetExecutor.value
                    ).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                    }.build()
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                    }
                }
                json.decodeFromString<TokenResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<TokenResponse>(response.body.string())
                }
            }
        }
    }
}
