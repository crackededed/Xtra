package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.VideoSwap

@Dao
interface VideoSwapDao {

    @Query("SELECT * FROM video_swap")
    fun getAll(): List<VideoSwap>

    @Insert
    fun insertList(items: List<VideoSwap>)

    @Update
    fun updateList(items: List<VideoSwap>)

    @Insert
    fun insert(item: VideoSwap): Long

    @Delete
    fun delete(item: VideoSwap)

    @Update
    fun update(item: VideoSwap)
}
