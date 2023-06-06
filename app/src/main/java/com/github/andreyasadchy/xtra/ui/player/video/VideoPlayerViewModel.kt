package com.github.andreyasadchy.xtra.ui.player.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.offline.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
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
class VideoPlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    repository: ApiRepository,
    localFollowsChannel: LocalFollowChannelRepository,
    private val bookmarksRepository: BookmarksRepository) : PlayerViewModel(repository, localFollowsChannel) {

    var result = MutableLiveData<Uri>()

    val bookmarkItem = MutableLiveData<Bookmark>()
    val gamesList = MutableLiveData<List<Game>>()
    var shouldRetry = true

    fun load(gqlHeaders: Map<String, String>, videoId: String?, playerType: String?) {
        viewModelScope.launch {
            try {
                playerRepository.loadVideoPlaylistUrl(gqlHeaders, videoId, playerType)
            } catch (e: Exception) {
                null
            }.let { result.postValue(it) }
        }
    }

    fun savePosition(id: Long, position: Long) {
        playerRepository.saveVideoPosition(VideoPosition(id, position))
    }

    fun loadGamesList(gqlHeaders: Map<String, String>, videoId: String?) {
        if (!gamesList.isInitialized) {
            viewModelScope.launch {
                try {
                    val get = repository.loadVideoGames(gqlHeaders, videoId)
                    gamesList.postValue(get)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun checkBookmark(id: String) {
        viewModelScope.launch {
            bookmarkItem.postValue(bookmarksRepository.getBookmarkByVideoId(id))
        }
    }

    fun saveBookmark(context: Context, video: Video) {
        GlobalScope.launch {
            if (bookmarkItem.value != null) {
                bookmarksRepository.deleteBookmark(context, bookmarkItem.value!!)
            } else {
                if (!video.id.isNullOrBlank()) {
                    try {
                        Glide.with(context)
                            .asBitmap()
                            .load(video.thumbnail)
                            .into(object: CustomTarget<Bitmap>() {
                                override fun onLoadCleared(placeholder: Drawable?) {

                                }

                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    DownloadUtils.savePng(context, "thumbnails", video.id!!, resource)
                                }
                            })
                    } catch (e: Exception) {

                    }
                }
                if (!video.channelId.isNullOrBlank()) {
                    try {
                        Glide.with(context)
                            .asBitmap()
                            .load(video.channelLogo)
                            .into(object: CustomTarget<Bitmap>() {
                                override fun onLoadCleared(placeholder: Drawable?) {

                                }

                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    DownloadUtils.savePng(context, "profile_pics", video.channelId!!, resource)
                                }
                            })
                    } catch (e: Exception) {

                    }
                }
                val userTypes = video.channelId?.let { repository.loadUserTypes(listOf(it), context.prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(context).helixToken, TwitchApiHelper.getGQLHeaders(context)) }?.first()
                val downloadedThumbnail = video.id?.let { File(context.filesDir.toString() + File.separator + "thumbnails" + File.separator + "${it}.png").absolutePath }
                val downloadedLogo = video.channelId?.let { File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${it}.png").absolutePath }
                bookmarksRepository.saveBookmark(Bookmark(
                    videoId = video.id,
                    userId = video.channelId,
                    userLogin = video.channelLogin,
                    userName = video.channelName,
                    userType = userTypes?.type,
                    userBroadcasterType = userTypes?.broadcasterType,
                    userLogo = downloadedLogo,
                    gameId = video.gameId,
                    gameName = video.gameName,
                    title = video.title,
                    createdAt = video.uploadDate,
                    thumbnail = downloadedThumbnail,
                    type = video.type,
                    duration = video.duration,
                    animatedPreviewURL = video.animatedPreviewURL
                ))
            }
        }
    }
}