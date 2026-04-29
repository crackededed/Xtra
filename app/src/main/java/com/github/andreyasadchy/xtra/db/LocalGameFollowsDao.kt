package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.LocalGameFollow

@Dao
interface LocalGameFollowsDao {

    @Query("SELECT * FROM local_follows_games")
    fun getAll(): List<LocalGameFollow>

    @Query("SELECT * FROM local_follows_games WHERE gameId = :id")
    fun getById(id: String): LocalGameFollow?

    @Insert
    fun insert(item: LocalGameFollow)

    @Delete
    fun delete(item: LocalGameFollow)

    @Update
    fun update(item: LocalGameFollow)
}
