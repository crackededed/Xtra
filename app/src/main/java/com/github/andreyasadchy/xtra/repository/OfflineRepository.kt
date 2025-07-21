package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.LocalFollowsChannelDao
import com.github.andreyasadchy.xtra.db.VideosDao
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineRepository @Inject constructor(
    private val videosDao: VideosDao,
    private val localFollowsChannelDao: LocalFollowsChannelDao,
    private val bookmarksDao: BookmarksDao,
) {

    fun loadAllVideos() = videosDao.getAll()

    suspend fun getVideoById(id: Int) = withContext(Dispatchers.IO) {
        videosDao.getById(id)
    }

    suspend fun getVideoByUrl(url: String) = withContext(Dispatchers.IO) {
        videosDao.getByUrl(url)
    }

    suspend fun getLiveDownload(login: String) = withContext(Dispatchers.IO) {
        videosDao.getLiveDownload(login)
    }

    suspend fun getVideosByUserId(id: String) = withContext(Dispatchers.IO) {
        videosDao.getByUserId(id)
    }

    suspend fun getPlaylists() = withContext(Dispatchers.IO) {
        videosDao.getPlaylists()
    }

    suspend fun saveVideo(video: OfflineVideo) = withContext(Dispatchers.IO) {
        videosDao.insert(video)
    }

    suspend fun deleteVideo(video: OfflineVideo) = withContext(Dispatchers.IO) {
        video.videoId?.let { id ->
            if (id.isNotBlank() && videosDao.getByVideoId(id).none { it.id != video.id } && bookmarksDao.getByVideoId(id) == null) {
                video.thumbnail?.let {
                    if (it.isNotBlank()) {
                        File(it).delete()
                    }
                }
            }
        }
        video.channelId?.let { id ->
            if (id.isNotBlank() && getVideosByUserId(id).none { it.id != video.id } && bookmarksDao.getByUserId(id).isEmpty()) {
                video.channelLogo?.let {
                    if (it.isNotBlank()) {
                        File(it).delete()
                    }
                }
            }
        }
        videosDao.delete(video)
    }

    suspend fun updateVideo(video: OfflineVideo) = withContext(Dispatchers.IO) {
        videosDao.update(video)
    }

    suspend fun updateVideoPosition(id: Int, position: Long) = withContext(Dispatchers.IO) {
        videosDao.updatePosition(id, position)
    }

    suspend fun deletePositions() = withContext(Dispatchers.IO) {
        videosDao.deletePositions()
    }

    suspend fun deleteOldImages() = withContext(Dispatchers.IO) {
        localFollowsChannelDao.getAll().forEach { item ->
            item.channelLogo?.let {
                if (it.isNotBlank() && !item.userId.isNullOrBlank() && bookmarksDao.getByUserId(item.userId).isEmpty() && videosDao.getByUserId(item.userId).isEmpty()) {
                    File(it).delete()
                }
            }
        }
    }
}
