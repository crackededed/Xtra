package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.PlaybackState

@Dao
interface PlaybackStatesDao {

    @Query("SELECT * FROM playback_states")
    fun getAll(): List<PlaybackState>

    @Insert
    fun insertList(items: List<PlaybackState>)

    @Query("DELETE FROM playback_states")
    fun deleteAll()

    @Transaction
    fun replaceItems(items: List<PlaybackState>) {
        deleteAll()
        insertList(items)
    }
}
