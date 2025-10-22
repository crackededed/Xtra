package com.github.andreyasadchy.xtra.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ui.StreamFilter

@Dao
interface StreamFilterDao {

    @Query("SELECT * FROM stream_filter")
    fun getAllPagingSource(): PagingSource<Int, StreamFilter>

    @Insert
    fun insert(item: StreamFilter)

    @Delete
    fun delete(item: StreamFilter)
}