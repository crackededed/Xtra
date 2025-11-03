package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.SortGameDao
import com.github.andreyasadchy.xtra.model.ui.SortGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SortGameRepository @Inject constructor(
    private val sortGameDao: SortGameDao,
) {

    suspend fun getById(id: String) = withContext(Dispatchers.IO) {
        sortGameDao.getById(id)
    }

    suspend fun save(item: SortGame) = withContext(Dispatchers.IO) {
        sortGameDao.insert(item)
    }

    suspend fun delete(item: SortGame) = withContext(Dispatchers.IO) {
        sortGameDao.delete(item)
    }
}
