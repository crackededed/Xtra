package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.SavedFiltersDao
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedFiltersRepository @Inject constructor(
    private val savedFiltersDao: SavedFiltersDao,
) {

    fun loadFiltersPagingSource() = savedFiltersDao.getAllPagingSource()

    suspend fun saveFilter(item: SavedFilter) = withContext(Dispatchers.IO) {
        savedFiltersDao.insert(item)
    }

    suspend fun deleteFilter(item: SavedFilter) = withContext(Dispatchers.IO) {
        savedFiltersDao.delete(item)
    }
}
