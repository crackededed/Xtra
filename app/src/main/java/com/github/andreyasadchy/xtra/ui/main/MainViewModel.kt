package com.github.andreyasadchy.xtra.ui.main

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.LoggedIn
import com.github.andreyasadchy.xtra.model.NotValidated
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.Event
import com.github.andreyasadchy.xtra.util.SingleLiveEvent
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.nullIfEmpty
import com.github.andreyasadchy.xtra.util.toast
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import javax.inject.Inject

private const val TAG = "ChatViewModel"

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: ApiRepository,
    private val authRepository: AuthRepository,
    private val offlineRepository: OfflineRepository,
    private val okHttpClient: OkHttpClient) : ViewModel() {

    private val _integrity by lazy { SingleLiveEvent<Boolean>() }
    val integrity: LiveData<Boolean>
        get() = _integrity

    private val _isNetworkAvailable = MutableLiveData<Event<Boolean>>()
    val isNetworkAvailable: LiveData<Event<Boolean>>
        get() = _isNetworkAvailable

    var isPlayerMaximized = false
        private set

    var isPlayerOpened = false
        private set

    private val _video = MutableLiveData<Video?>()
    val video: MutableLiveData<Video?>
        get() = _video
    private val _clip = MutableLiveData<Clip?>()
    val clip: MutableLiveData<Clip?>
        get() = _clip
    private val _user = MutableLiveData<User?>()
    val user: MutableLiveData<User?>
        get() = _user
    private val _latestVersion = MutableLiveData<String?>()
    val latestVersion: MutableLiveData<String?>
        get() = _latestVersion

    init {
        offlineRepository.resumeDownloads(application)
        viewModelScope.launch {
            if (BuildConfig.BUILD_TYPE == "release") {
                _latestVersion.value = withContext(Dispatchers.IO) {
                    getLatestVersion()
                }
            }
        }
    }

    fun onMaximize() {
        isPlayerMaximized = true
    }

    fun onMinimize() {
        isPlayerMaximized = false
    }

    fun onPlayerStarted() {
        isPlayerOpened = true
        isPlayerMaximized = true
    }

    fun onPlayerClosed() {
        isPlayerOpened = false
        isPlayerMaximized = false
    }

    fun setNetworkAvailable(available: Boolean) {
        if (_isNetworkAvailable.value?.peekContent() != available) {
            _isNetworkAvailable.value = Event(available)
        }
    }

    fun loadVideo(videoId: String?, helixClientId: String? = null, helixToken: String? = null, gqlHeaders: Map<String, String>, checkIntegrity: Boolean) {
        _video.value = null
        viewModelScope.launch {
            try {
                repository.loadVideo(videoId, helixClientId, helixToken, gqlHeaders, checkIntegrity)?.let { _video.postValue(it) }
            } catch (e: Exception) {
                if (e.message == "failed integrity check") {
                    _integrity.postValue(true)
                }
            }
        }
    }

    fun loadClip(clipId: String?, helixClientId: String? = null, helixToken: String? = null, gqlHeaders: Map<String, String>, checkIntegrity: Boolean) {
        _clip.value = null
        viewModelScope.launch {
            try {
                repository.loadClip(clipId, helixClientId, helixToken, gqlHeaders, checkIntegrity)?.let { _clip.postValue(it) }
            } catch (e: Exception) {
                if (e.message == "failed integrity check") {
                    _integrity.postValue(true)
                }
            }
        }
    }

    fun loadUser(login: String? = null, helixClientId: String? = null, helixToken: String? = null, gqlHeaders: Map<String, String>, checkIntegrity: Boolean) {
        _user.value = null
        viewModelScope.launch {
            try {
                repository.loadCheckUser(channelLogin = login, helixClientId = helixClientId, helixToken = helixToken, gqlHeaders = gqlHeaders, checkIntegrity = checkIntegrity)?.let { _user.postValue(it) }
            } catch (e: Exception) {
                if (e.message == "failed integrity check") {
                    _integrity.postValue(true)
                }
            }
        }
    }

    fun validate(helixClientId: String?, gqlHeaders: Map<String, String>, activity: Activity) {
        val account = Account.get(activity)
        if (account is NotValidated) {
            viewModelScope.launch {
                try {
                    if (!account.helixToken.isNullOrBlank()) {
                        val response = authRepository.validate(TwitchApiHelper.addTokenPrefixHelix(account.helixToken))
                        if (!response?.clientId.isNullOrBlank() && response?.clientId == helixClientId) {
                            if ((!response?.userId.isNullOrBlank() && response?.userId != account.id) || (!response?.login.isNullOrBlank() && response?.login != account.login)) {
                                Account.set(activity, LoggedIn(response?.userId?.nullIfEmpty() ?: account.id, response?.login?.nullIfEmpty() ?: account.login, account.helixToken))
                            }
                        } else {
                            throw IllegalStateException("401")
                        }
                    }
                    val gqlToken = gqlHeaders[C.HEADER_TOKEN]
                    if (!gqlToken.isNullOrBlank()) {
                        val response = authRepository.validate(gqlToken)
                        if (!response?.clientId.isNullOrBlank() && response?.clientId == gqlHeaders[C.HEADER_CLIENT_ID]) {
                            if ((!response?.userId.isNullOrBlank() && response?.userId != account.id) || (!response?.login.isNullOrBlank() && response?.login != account.login)) {
                                Account.set(activity, LoggedIn(response?.userId?.nullIfEmpty() ?: account.id, response?.login?.nullIfEmpty() ?: account.login, account.helixToken))
                            }
                        } else {
                            throw IllegalStateException("401")
                        }
                    }
                    if (!account.helixToken.isNullOrBlank() || !gqlToken.isNullOrBlank()) {
                        Account.validated()
                    }
                } catch (e: Exception) {
                    if ((e is IllegalStateException && e.message == "401") || (e is HttpException && e.code() == 401)) {
                        Account.set(activity, null)
                        activity.toast(R.string.token_expired)
                        activity.startActivityForResult(Intent(activity, LoginActivity::class.java), 2)
                    }
                }
            }
        }
        TwitchApiHelper.checkedValidation = true
    }

    private fun getLatestVersion() = try {
        val request = Request.Builder()
            .url("https://f-droid.org/api/v1/packages/com.github.andreyasadchy.xtra")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val jsonResponse =
                JsonParser.parseString(response.body.string()).asJsonObject
            val suggestedVersionCode =
                jsonResponse.getAsJsonPrimitive("suggestedVersionCode").asInt
            jsonResponse.getAsJsonArray("packages")
                .filter { it.asJsonObject.get("versionCode").asInt == suggestedVersionCode }
                .map { it.asJsonObject.get("versionName").asString }
                .firstOrNull()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't fetch the latest app version", e)
        null
    }
}