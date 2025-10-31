package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ui.SortChannel

@Dao
interface SortChannelDao {

    @Query("SELECT * FROM sort_channel WHERE id = :id")
    fun getById(id: String): SortChannel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(video: SortChannel)

    @Delete
    fun delete(video: SortChannel)
}
