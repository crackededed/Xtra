package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.StreamFilterDao
import com.github.andreyasadchy.xtra.model.ui.StreamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamFilterRepository @Inject constructor(
    private val streamFilterDao: StreamFilterDao,
) {
    fun loadStreamFiltersPagingSource() = streamFilterDao.getAllPagingSource()

    suspend fun save(item: StreamFilter) = withContext(Dispatchers.IO) {
        streamFilterDao.insert(item)
    }

    suspend fun delete(item: StreamFilter) = withContext(Dispatchers.IO) {
        streamFilterDao.delete(item)
    }
}