package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.BookmarkIgnoredUsersDao
import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.OfflineVideosDao
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.BookmarkIgnoredUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarksRepository @Inject constructor(
    private val bookmarksDao: BookmarksDao,
    private val bookmarkIgnoredUsersDao: BookmarkIgnoredUsersDao,
    private val offlineVideosDao: OfflineVideosDao,
) {

    fun getAllFlow() = bookmarksDao.getAllFlow()

    suspend fun getAll() = withContext(Dispatchers.IO) {
        bookmarksDao.getAll()
    }

    suspend fun getByVideoId(id: String) = withContext(Dispatchers.IO) {
        bookmarksDao.getByVideoId(id)
    }

    suspend fun getByUserId(id: String) = withContext(Dispatchers.IO) {
        bookmarksDao.getByUserId(id)
    }

    suspend fun save(item: Bookmark) = withContext(Dispatchers.IO) {
        bookmarksDao.insert(item)
    }

    suspend fun delete(item: Bookmark) = withContext(Dispatchers.IO) {
        if (!item.videoId.isNullOrBlank() && offlineVideosDao.getByVideoId(item.videoId).isEmpty()) {
            item.thumbnail?.let {
                if (it.isNotBlank()) {
                    File(it).delete()
                }
            }
        }
        if (!item.userId.isNullOrBlank() && getByUserId(item.userId).none { it.id != item.id } && offlineVideosDao.getByUserId(item.userId).isEmpty()) {
            item.userLogo?.let {
                if (it.isNotBlank()) {
                    File(it).delete()
                }
            }
        }
        bookmarksDao.delete(item)
    }

    suspend fun update(item: Bookmark) = withContext(Dispatchers.IO) {
        bookmarksDao.update(item)
    }

    fun getIgnoredUsersFlow() = bookmarkIgnoredUsersDao.getAllFlow()

    suspend fun getIgnoredUsers() = withContext(Dispatchers.IO) {
        bookmarkIgnoredUsersDao.getAll()
    }

    suspend fun getIgnoredUser(id: String) = withContext(Dispatchers.IO) {
        bookmarkIgnoredUsersDao.getById(id)
    }

    suspend fun saveIgnoredUser(item: BookmarkIgnoredUser) = withContext(Dispatchers.IO) {
        bookmarkIgnoredUsersDao.insert(item)
    }

    suspend fun deleteIgnoredUser(item: BookmarkIgnoredUser) = withContext(Dispatchers.IO) {
        bookmarkIgnoredUsersDao.delete(item)
    }
}
