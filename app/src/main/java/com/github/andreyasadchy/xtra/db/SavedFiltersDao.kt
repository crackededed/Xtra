package com.github.andreyasadchy.xtra.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ui.SavedFilter

@Dao
interface SavedFiltersDao {

    @Query("SELECT * FROM filters")
    fun getAllPagingSource(): PagingSource<Int, SavedFilter>

    @Insert
    fun insert(item: SavedFilter)

    @Delete
    fun delete(item: SavedFilter)
}