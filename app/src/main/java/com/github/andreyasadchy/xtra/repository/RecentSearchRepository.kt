package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.RecentSearchDao
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentSearchRepository @Inject constructor(
    private val recentSearchDao: RecentSearchDao,
) {

    fun loadRecentSearchFlow(type: String) = recentSearchDao.getAllFlow(type)

    suspend fun getItem(query: String, type: String) = withContext(Dispatchers.IO) {
        recentSearchDao.getItem(query, type)
    }

    suspend fun save(item: RecentSearch) = withContext(Dispatchers.IO) {
        recentSearchDao.ensureMaxSizeAndInsert(item)
    }

    suspend fun delete(item: RecentSearch) = withContext(Dispatchers.IO) {
        recentSearchDao.delete(item)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        recentSearchDao.deleteAll()
    }
}