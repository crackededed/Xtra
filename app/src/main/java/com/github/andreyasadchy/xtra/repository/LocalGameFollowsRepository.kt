package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.LocalGameFollowsDao
import com.github.andreyasadchy.xtra.model.ui.LocalGameFollow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalGameFollowsRepository @Inject constructor(
    private val localGameFollowsDao: LocalGameFollowsDao,
) {

    suspend fun getAll() = withContext(Dispatchers.IO) {
        localGameFollowsDao.getAll()
    }

    suspend fun getById(id: String) = withContext(Dispatchers.IO) {
        localGameFollowsDao.getById(id)
    }

    suspend fun save(item: LocalGameFollow) = withContext(Dispatchers.IO) {
        localGameFollowsDao.insert(item)
    }

    suspend fun delete(item: LocalGameFollow) = withContext(Dispatchers.IO) {
        item.boxArt?.let {
            if (it.isNotBlank()) {
                File(it).delete()
            }
        }
        localGameFollowsDao.delete(item)
    }

    suspend fun update(item: LocalGameFollow) = withContext(Dispatchers.IO) {
        localGameFollowsDao.update(item)
    }
}
