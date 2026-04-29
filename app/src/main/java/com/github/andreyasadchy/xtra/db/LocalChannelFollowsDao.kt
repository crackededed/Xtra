package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.LocalChannelFollow

@Dao
interface LocalChannelFollowsDao {

    @Query("SELECT * FROM local_follows")
    fun getAll(): List<LocalChannelFollow>

    @Query("SELECT * FROM local_follows WHERE userId = :id")
    fun getById(id: String): LocalChannelFollow?

    @Insert
    fun insert(item: LocalChannelFollow)

    @Delete
    fun delete(item: LocalChannelFollow)

    @Update
    fun update(item: LocalChannelFollow)
}
