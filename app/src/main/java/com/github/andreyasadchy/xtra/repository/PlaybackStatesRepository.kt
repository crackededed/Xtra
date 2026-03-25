package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.PlaybackStatesDao
import com.github.andreyasadchy.xtra.model.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackStatesRepository @Inject constructor(
    private val playbackStatesDao: PlaybackStatesDao,
) {

    suspend fun loadStates() = withContext(Dispatchers.IO) {
        playbackStatesDao.getAll()
    }

    suspend fun saveStates(items: List<PlaybackState>) = withContext(Dispatchers.IO) {
        playbackStatesDao.replaceItems(items)
    }

    suspend fun deleteStates() = withContext(Dispatchers.IO) {
        playbackStatesDao.deleteAll()
    }
}
