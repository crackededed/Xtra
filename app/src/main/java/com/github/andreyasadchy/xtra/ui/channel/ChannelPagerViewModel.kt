package com.github.andreyasadchy.xtra.ui.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.LocalFollowChannel
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.OfflineRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ChannelPagerViewModel @Inject constructor(
    private val repository: ApiRepository,
    private val localFollowsChannel: LocalFollowChannelRepository,
    private val offlineRepository: OfflineRepository,
    private val bookmarksRepository: BookmarksRepository,
    savedStateHandle: SavedStateHandle) : ViewModel() {

    private val args = ChannelPagerFragmentArgs.fromSavedStateHandle(savedStateHandle)
    val follow = MutableLiveData<Pair<Boolean, String?>>()
    private var updatedLocalUser = false

    private val _stream = MutableLiveData<Stream?>()
    val stream: MutableLiveData<Stream?>
        get() = _stream
    private val _user = MutableLiveData<User?>()
    val user: MutableLiveData<User?>
        get() = _user

    fun loadStream(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>) {
        if (!_stream.isInitialized) {
            viewModelScope.launch {
                try {
                    repository.loadUserChannelPage(args.channelId, args.channelLogin, helixClientId, helixToken, gqlHeaders)?.let { _stream.postValue(it) }
                } catch (e: Exception) {}
            }
        }
    }

    fun loadUser(helixClientId: String?, helixToken: String?) {
        if (!_user.isInitialized && !helixToken.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    repository.loadUser(args.channelId, args.channelLogin, helixClientId, helixToken)?.let { _user.postValue(it) }
                } catch (e: Exception) {}
            }
        }
    }

    fun retry(helixClientId: String?, helixToken: String?, gqlHeaders: Map<String, String>) {
        if (_stream.value == null) {
            loadStream(helixClientId, helixToken, gqlHeaders)
        } else {
            if (_stream.value?.user == null && _user.value == null) {
                loadUser(helixClientId, helixToken)
            }
        }
    }

    fun isFollowingChannel(context: Context, channelId: String?, channelLogin: String?) {
        if (!follow.isInitialized) {
            viewModelScope.launch {
                try {
                    val setting = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toInt() ?: 0
                    val account = Account.get(context)
                    val helixClientId = context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
                    val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
                    val isFollowing = if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        if ((!helixClientId.isNullOrBlank() && !account.helixToken.isNullOrBlank() && !account.id.isNullOrBlank() && !channelId.isNullOrBlank() && account.id != channelId) ||
                            (!account.login.isNullOrBlank() && !channelLogin.isNullOrBlank() && account.login != channelLogin)) {
                            repository.loadUserFollowing(helixClientId, account.helixToken, channelId, account.id, gqlHeaders, channelLogin)
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
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
            try {
                if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val errorMessage = repository.followUser(gqlHeaders, userId)
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
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, true)
            try {
                if (setting == 0 && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val errorMessage = repository.unfollowUser(gqlHeaders, userId)
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

    fun updateLocalUser(context: Context, user: User) {
        if (!updatedLocalUser) {
            updatedLocalUser = true
            GlobalScope.launch {
                try {
                    if (user.channelId != null) {
                        try {
                            Glide.with(context)
                                .asBitmap()
                                .load(user.channelLogo)
                                .into(object: CustomTarget<Bitmap>() {
                                    override fun onLoadCleared(placeholder: Drawable?) {

                                    }

                                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                        DownloadUtils.savePng(context, "profile_pics", user.channelId, resource)
                                    }
                                })
                        } catch (e: Exception) {

                        }
                        val downloadedLogo = File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${user.channelId}.png").absolutePath
                        localFollowsChannel.getFollowByUserId(user.channelId)?.let { localFollowsChannel.updateFollow(it.apply {
                            userLogin = user.channelLogin
                            userName = user.channelName
                            channelLogo = downloadedLogo }) }
                        for (i in offlineRepository.getVideosByUserId(user.channelId.toInt())) {
                            offlineRepository.updateVideo(i.apply {
                                channelLogin = user.channelLogin
                                channelName = user.channelName
                                channelLogo = downloadedLogo })
                        }
                        for (i in bookmarksRepository.getBookmarksByUserId(user.channelId)) {
                            bookmarksRepository.updateBookmark(i.apply {
                                userLogin = user.channelLogin
                                userName = user.channelName
                                userLogo = downloadedLogo })
                        }
                    }
                } catch (e: Exception) {

                }
            }
        }
    }
}
