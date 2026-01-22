package com.github.andreyasadchy.xtra.repository

import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.util.HttpEngineUtils
import com.github.andreyasadchy.xtra.util.getByteArrayCronetCallback
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.RedirectHandlers
import org.chromium.net.apihelpers.UploadDataProviders
import org.chromium.net.apihelpers.UrlRequestCallbacks
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.suspendCoroutine

@Singleton
class AuthRepository @Inject constructor(
    private val httpEngine: Lazy<HttpEngine>?,
    private val cronetEngine: Lazy<CronetEngine>?,
    private val cronetExecutor: ExecutorService,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    suspend fun validate(networkLibrary: String?, token: String): ValidationResponse = withContext(Dispatchers.IO) {
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/validate", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("Authorization", token)
                    }.build().start()
                }
                if (response.first.httpStatusCode != 401) {
                    json.decodeFromString<ValidationResponse>(String(response.second))
                } else {
                    throw IllegalStateException("401")
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/validate", request.callback, cronetExecutor).apply {
                        addHeader("Authorization", token)
                    }.build().start()
                    val response = request.future.get()
                    if (response.urlResponseInfo.httpStatusCode != 401) {
                        json.decodeFromString<ValidationResponse>(response.responseBody as String)
                    } else {
                        throw IllegalStateException("401")
                    }
                } else {
                    val response = suspendCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/validate", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("Authorization", token)
                        }.build().start()
                    }
                    if (response.first.httpStatusCode != 401) {
                        json.decodeFromString<ValidationResponse>(String(response.second))
                    } else {
                        throw IllegalStateException("401")
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://id.twitch.tv/oauth2/validate")
                    header("Authorization", token)
                }.build()).execute().use { response ->
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
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                suspendCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/revoke", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/revoke", request.callback, cronetExecutor).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    request.future.get().responseBody as String
                } else {
                    suspendCoroutine<Pair<org.chromium.net.UrlResponseInfo, ByteArray>> { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/revoke", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("Content-Type", "application/x-www-form-urlencoded")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://id.twitch.tv/oauth2/revoke")
                    header("Content-Type", "application/x-www-form-urlencoded")
                    post(body.toRequestBody())
                }.build()).execute()
            }
        }
    }

    suspend fun getDeviceCode(networkLibrary: String?, body: String): DeviceCodeResponse = withContext(Dispatchers.IO) {
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/device", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                json.decodeFromString<DeviceCodeResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/device", request.callback, cronetExecutor).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<DeviceCodeResponse>(response)
                } else {
                    val response = suspendCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/device", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("Content-Type", "application/x-www-form-urlencoded")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    json.decodeFromString<DeviceCodeResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://id.twitch.tv/oauth2/device")
                    header("Content-Type", "application/x-www-form-urlencoded")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    json.decodeFromString<DeviceCodeResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getToken(networkLibrary: String?, body: String): TokenResponse = withContext(Dispatchers.IO) {
        when {
            networkLibrary == "HttpEngine" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && httpEngine != null -> {
                val response = suspendCoroutine { continuation ->
                    httpEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/token", cronetExecutor, HttpEngineUtils.byteArrayUrlCallback(continuation)).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(HttpEngineUtils.byteArrayUploadProvider(body.toByteArray()), cronetExecutor)
                    }.build().start()
                }
                json.decodeFromString<TokenResponse>(String(response.second))
            }
            networkLibrary == "Cronet" && cronetEngine != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val request = UrlRequestCallbacks.forStringBody(RedirectHandlers.alwaysFollow())
                    cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/token", request.callback, cronetExecutor).apply {
                        addHeader("Content-Type", "application/x-www-form-urlencoded")
                        setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                    }.build().start()
                    val response = request.future.get().responseBody as String
                    json.decodeFromString<TokenResponse>(response)
                } else {
                    val response = suspendCoroutine { continuation ->
                        cronetEngine.get().newUrlRequestBuilder("https://id.twitch.tv/oauth2/token", getByteArrayCronetCallback(continuation), cronetExecutor).apply {
                            addHeader("Content-Type", "application/x-www-form-urlencoded")
                            setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor)
                        }.build().start()
                    }
                    json.decodeFromString<TokenResponse>(String(response.second))
                }
            }
            else -> {
                okHttpClient.newCall(Request.Builder().apply {
                    url("https://id.twitch.tv/oauth2/token")
                    header("Content-Type", "application/x-www-form-urlencoded")
                    post(body.toRequestBody())
                }.build()).execute().use { response ->
                    json.decodeFromString<TokenResponse>(response.body.string())
                }
            }
        }
    }
}
