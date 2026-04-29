package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.LocalChannelFollowsDao
import com.github.andreyasadchy.xtra.db.OfflineVideosDao
import com.github.andreyasadchy.xtra.model.ui.LocalChannelFollow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalChannelFollowsRepository @Inject constructor(
    private val localChannelFollowsDao: LocalChannelFollowsDao,
    private val offlineVideosDao: OfflineVideosDao,
    private val bookmarksDao: BookmarksDao,
) {

    suspend fun getAll() = withContext(Dispatchers.IO) {
        localChannelFollowsDao.getAll()
    }

    suspend fun getById(id: String) = withContext(Dispatchers.IO) {
        localChannelFollowsDao.getById(id)
    }

    suspend fun save(item: LocalChannelFollow) = withContext(Dispatchers.IO) {
        localChannelFollowsDao.insert(item)
    }

    suspend fun delete(item: LocalChannelFollow) = withContext(Dispatchers.IO) {
        localChannelFollowsDao.delete(item)
    }

    suspend fun update(item: LocalChannelFollow) = withContext(Dispatchers.IO) {
        localChannelFollowsDao.update(item)
    }

    suspend fun deleteOldImages() = withContext(Dispatchers.IO) {
        localChannelFollowsDao.getAll().forEach { item ->
            item.channelLogo?.let {
                if (it.isNotBlank()
                    && !item.userId.isNullOrBlank()
                    && bookmarksDao.getByUserId(item.userId).isEmpty()
                    && offlineVideosDao.getByUserId(item.userId).isEmpty()
                ) {
                    File(it).delete()
                }
            }
        }
    }
}
