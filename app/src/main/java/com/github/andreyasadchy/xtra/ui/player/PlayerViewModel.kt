package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.LocalFollowChannel
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Timer
import kotlin.concurrent.schedule


abstract class PlayerViewModel(
    val repository: ApiRepository,
    private val localFollowsChannel: LocalFollowChannelRepository) : ViewModel() {

    var started = false
    var background = false
    var playerMode = PlayerMode.NORMAL
    val loaded = MutableLiveData<Boolean>()
    val follow = MutableLiveData<Pair<Boolean, String?>>()

    private var timer: Timer? = null
    private val _sleepTimer = MutableLiveData<Boolean>()
    val sleepTimer: LiveData<Boolean>
        get() = _sleepTimer
    private var timerEndTime = 0L
    val timerTimeLeft
        get() = timerEndTime - System.currentTimeMillis()

    fun setTimer(duration: Long) {
        timer?.let {
            it.cancel()
            timerEndTime = 0L
            timer = null
        }
        if (duration > 0L) {
            timer = Timer().apply {
                timerEndTime = System.currentTimeMillis() + duration
                schedule(duration) {
                    _sleepTimer.postValue(true)
                }
            }
        }
    }

    override fun onCleared() {
        timer?.cancel()
    }

    fun isFollowingChannel(context: Context, channelId: String?, channelLogin: String?) {
        if (!follow.isInitialized) {
            viewModelScope.launch {
                try {
                    val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
                    val account = Account.get(context)
                    val helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
                    val gqlHeaders = TwitchApiHelper.getGQLHeaders(context)
                    val isFollowing = if (setting == 0 && !account.gqlToken.isNullOrBlank()) {
                        if ((!helixClientId.isNullOrBlank() && !account.helixToken.isNullOrBlank() && !account.id.isNullOrBlank() && !channelId.isNullOrBlank() && account.id != channelId) ||
                            (!account.login.isNullOrBlank() && !channelLogin.isNullOrBlank() && account.login != channelLogin)) {
                            repository.loadUserFollowing(helixClientId, account.helixToken, channelId, account.id, gqlHeaders, account.gqlToken, channelLogin)
                        } else false
                    } else {
                        channelId?.let {
                            localFollowsChannel.getFollowByUserId(it)
                        } != null
                    }
                    follow.postValue(Pair(isFollowing, null))
                } catch (e: Exception) {

                }
            }
        }
    }

    fun saveFollowChannel(context: Context, userId: String?, userLogin: String?, userName: String?, channelLogo: String?) {
        GlobalScope.launch {
            val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
            val account = Account.get(context)
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(context)
            try {
                if (setting == 0 && !account.gqlToken.isNullOrBlank()) {
                    val errorMessage = repository.followUser(gqlHeaders, account.gqlToken, userId)
                    follow.postValue(Pair(true, errorMessage))
                } else {
                    if (userId != null) {
                        try {
                            Glide.with(context)
                                .asBitmap()
                                .load(channelLogo)
                                .into(object: CustomTarget<Bitmap>() {
                                    override fun onLoadCleared(placeholder: Drawable?) {

                                    }

                                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                        DownloadUtils.savePng(context, "profile_pics", userId, resource)
                                    }
                                })
                        } catch (e: Exception) {

                        }
                        val downloadedLogo = File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${userId}.png").absolutePath
                        localFollowsChannel.saveFollow(LocalFollowChannel(userId, userLogin, userName, downloadedLogo))
                        follow.postValue(Pair(true, null))
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowChannel(context: Context, userId: String?) {
        GlobalScope.launch {
            val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
            val account = Account.get(context)
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(context)
            try {
                if (setting == 0 && !account.gqlToken.isNullOrBlank()) {
                    val errorMessage = repository.unfollowUser(gqlHeaders, account.gqlToken, userId)
                    follow.postValue(Pair(false, errorMessage))
                } else {
                    if (userId != null) {
                        localFollowsChannel.getFollowByUserId(userId)?.let { localFollowsChannel.deleteFollow(context, it) }
                        follow.postValue(Pair(false, null))
                    }
                }
            } catch (e: Exception) {

            }
        }
    }
}