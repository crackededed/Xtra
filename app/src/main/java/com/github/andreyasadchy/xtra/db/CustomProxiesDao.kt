package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.CustomProxy

@Dao
interface CustomProxiesDao {

    @Query("SELECT * FROM custom_proxies")
    fun getAll(): List<CustomProxy>

    @Insert
    fun insertList(items: List<CustomProxy>)

    @Update
    fun updateList(items: List<CustomProxy>)

    @Insert
    fun insert(item: CustomProxy): Long

    @Delete
    fun delete(item: CustomProxy)

    @Update
    fun update(item: CustomProxy)
}
