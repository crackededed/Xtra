package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.StreamProxy

@Dao
interface StreamProxiesDao {

    @Query("SELECT * FROM stream_proxies")
    fun getAll(): List<StreamProxy>

    @Insert
    fun insertList(items: List<StreamProxy>)

    @Update
    fun updateList(items: List<StreamProxy>)

    @Insert
    fun insert(item: StreamProxy): Long

    @Delete
    fun delete(item: StreamProxy)

    @Update
    fun update(item: StreamProxy)
}
