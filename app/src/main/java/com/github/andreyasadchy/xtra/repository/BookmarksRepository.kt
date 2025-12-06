package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.VideosDao
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarksRepository @Inject constructor(
    private val bookmarksDao: BookmarksDao,
    private val videosDao: VideosDao,
) {

    fun loadBookmarksFlow() = bookmarksDao.getAllFlow()

    suspend fun loadBookmarks() = withContext(Dispatchers.IO) {
        bookmarksDao.getAll()
    }

    suspend fun getBookmarkByVideoId(id: String) = withContext(Dispatchers.IO) {
        bookmarksDao.getByVideoId(id)
    }

    suspend fun getBookmarksByUserId(id: String) = withContext(Dispatchers.IO) {
        bookmarksDao.getByUserId(id)
    }

    suspend fun saveBookmark(item: Bookmark) = withContext(Dispatchers.IO) {
        bookmarksDao.insert(item)
    }

    suspend fun deleteBookmark(item: Bookmark) = withContext(Dispatchers.IO) {
        if (!item.videoId.isNullOrBlank() && videosDao.getByVideoId(item.videoId).isEmpty()) {
            item.thumbnail?.let {
                if (it.isNotBlank()) {
                    File(it).delete()
                }
            }
        }
        if (!item.userId.isNullOrBlank() && getBookmarksByUserId(item.userId).none { it.id != item.id } && videosDao.getByUserId(item.userId).isEmpty()) {
            item.userLogo?.let {
                if (it.isNotBlank()) {
                    File(it).delete()
                }
            }
        }
        bookmarksDao.delete(item)
    }

    suspend fun updateBookmark(item: Bookmark) = withContext(Dispatchers.IO) {
        bookmarksDao.update(item)
    }
}
