package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.SortChannelDao
import com.github.andreyasadchy.xtra.model.ui.SortChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SortChannelRepository @Inject constructor(
    private val sortChannelDao: SortChannelDao,
) {

    suspend fun getById(id: String) = withContext(Dispatchers.IO) {
        sortChannelDao.getById(id)
    }

    suspend fun save(item: SortChannel) = withContext(Dispatchers.IO) {
        sortChannelDao.insert(item)
    }

    suspend fun delete(item: SortChannel) = withContext(Dispatchers.IO) {
        sortChannelDao.delete(item)
    }
}
