package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ui.GameSort

@Dao
interface GameSortDao {

    @Query("SELECT * FROM sort_game WHERE id = :id")
    fun getById(id: String): GameSort?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: GameSort)

    @Delete
    fun delete(item: GameSort)
}
