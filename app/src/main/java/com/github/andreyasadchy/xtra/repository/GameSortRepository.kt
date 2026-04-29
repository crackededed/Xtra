package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.GameSortDao
import com.github.andreyasadchy.xtra.model.ui.GameSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameSortRepository @Inject constructor(
    private val gameSortDao: GameSortDao,
) {

    suspend fun getById(id: String) = withContext(Dispatchers.IO) {
        gameSortDao.getById(id)
    }

    suspend fun save(item: GameSort) = withContext(Dispatchers.IO) {
        gameSortDao.insert(item)
    }

    suspend fun delete(item: GameSort) = withContext(Dispatchers.IO) {
        gameSortDao.delete(item)
    }
}
