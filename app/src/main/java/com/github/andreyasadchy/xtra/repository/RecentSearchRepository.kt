package com.github.andreyasadchy.xtra.repository

import android.util.Log
import com.github.andreyasadchy.xtra.db.RecentSearchDao
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentSearchRepository @Inject constructor(
    private val recentSearchDao: RecentSearchDao
) {
    suspend fun loadRecentSearches(limit: Int): List<RecentSearch> = withContext(Dispatchers.IO) {
        recentSearchDao.recentSearches(limit)
    }

    suspend fun find(query: String) = withContext(Dispatchers.IO) {
        recentSearchDao.find(query)
    }

    suspend fun save(recentSearch: RecentSearch) = withContext(Dispatchers.IO) {
        recentSearchDao.insert(recentSearch)
    }

    suspend fun delete(recentSearch: RecentSearch) = withContext(Dispatchers.IO) {
        recentSearchDao.delete(recentSearch)
    }

    suspend fun deleteAll()  = withContext(Dispatchers.IO) {
        recentSearchDao.deleteAll()
    }
}