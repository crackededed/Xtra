package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.RecentSearchesDao
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentSearchesRepository @Inject constructor(
    private val recentSearchesDao: RecentSearchesDao,
) {

    fun getAll(type: String) = recentSearchesDao.getAll(type)

    suspend fun getItem(query: String, type: String) = withContext(Dispatchers.IO) {
        recentSearchesDao.getItem(query, type)
    }

    suspend fun save(item: RecentSearch) = withContext(Dispatchers.IO) {
        recentSearchesDao.ensureMaxSizeAndInsert(item)
    }

    suspend fun delete(item: RecentSearch) = withContext(Dispatchers.IO) {
        recentSearchesDao.delete(item)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        recentSearchesDao.deleteAll()
    }
}