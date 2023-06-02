package com.github.andreyasadchy.xtra.ui.videos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.offline.Bookmark
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

abstract class BaseVideosViewModel(
    playerRepository: PlayerRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val repository: ApiRepository) : ViewModel() {

    val positions = playerRepository.loadVideoPositions()
    val bookmarks = bookmarksRepository.loadBookmarksLiveData()

    fun saveBookmark(context: Context, video: Video) {
        GlobalScope.launch {
            val item = video.id?.let { bookmarksRepository.getBookmarkByVideoId(it) }
            if (item != null) {
                bookmarksRepository.deleteBookmark(context, item)
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
                                    DownloadUtils.savePng(context, "thumbnails", video.id, resource)
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