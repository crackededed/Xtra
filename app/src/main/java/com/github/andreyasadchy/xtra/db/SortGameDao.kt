package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ui.SortGame

@Dao
interface SortGameDao {

    @Query("SELECT * FROM sort_game WHERE id = :id")
    fun getById(id: String): SortGame?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(video: SortGame)

    @Delete
    fun delete(video: SortGame)
}
