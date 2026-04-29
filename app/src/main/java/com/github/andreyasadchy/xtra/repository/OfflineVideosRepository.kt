package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.OfflineVideosDao
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineVideosRepository @Inject constructor(
    private val offlineVideosDao: OfflineVideosDao,
    private val bookmarksDao: BookmarksDao,
) {

    fun getAll() = offlineVideosDao.getAll()

    suspend fun getById(id: Int) = withContext(Dispatchers.IO) {
        offlineVideosDao.getById(id)
    }

    suspend fun getByUrl(url: String) = withContext(Dispatchers.IO) {
        offlineVideosDao.getByUrl(url)
    }

    suspend fun getLiveDownload(login: String) = withContext(Dispatchers.IO) {
        offlineVideosDao.getLiveDownload(login)
    }

    suspend fun getByUserId(id: String) = withContext(Dispatchers.IO) {
        offlineVideosDao.getByUserId(id)
    }

    suspend fun getPlaylists() = withContext(Dispatchers.IO) {
        offlineVideosDao.getPlaylists()
    }

    suspend fun save(video: OfflineVideo) = withContext(Dispatchers.IO) {
        offlineVideosDao.insert(video)
    }

    suspend fun delete(video: OfflineVideo) = withContext(Dispatchers.IO) {
        video.videoId?.let { id ->
            if (id.isNotBlank() && offlineVideosDao.getByVideoId(id).none { it.id != video.id } && bookmarksDao.getByVideoId(id) == null) {
                video.thumbnail?.let {
                    if (it.isNotBlank()) {
                        File(it).delete()
                    }
                }
            }
        }
        video.channelId?.let { id ->
            if (id.isNotBlank() && getByUserId(id).none { it.id != video.id } && bookmarksDao.getByUserId(id).isEmpty()) {
                video.channelLogo?.let {
                    if (it.isNotBlank()) {
                        File(it).delete()
                    }
                }
            }
        }
        offlineVideosDao.delete(video)
    }

    suspend fun update(video: OfflineVideo) = withContext(Dispatchers.IO) {
        offlineVideosDao.update(video)
    }

    suspend fun updatePosition(id: Int, position: Long) = withContext(Dispatchers.IO) {
        offlineVideosDao.updatePosition(id, position)
    }

    suspend fun deletePositions() = withContext(Dispatchers.IO) {
        offlineVideosDao.deletePositions()
    }
}
