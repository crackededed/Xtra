package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {

    @Query("SELECT * FROM recent_search WHERE type = :type ORDER BY lastSearched DESC")
    fun getAllFlow(type: String): Flow<List<RecentSearch>>

    @Query("SELECT * FROM recent_search WHERE `query` = :query AND type = :type")
    fun getItem(query: String, type: String) : RecentSearch?

    @Insert
    fun insert(item: RecentSearch)

    @Delete
    fun delete(item: RecentSearch)

    @Query("DELETE FROM recent_search WHERE `query` NOT IN (SELECT `query` FROM recent_search WHERE type = :type ORDER BY lastSearched DESC LIMIT 20) AND type = :type")
    fun deleteOld(type: String)

    @Transaction
    fun ensureMaxSizeAndInsert(item: RecentSearch) {
        insert(item)
        deleteOld(item.type)
    }

    @Query("DELETE FROM recent_search")
    fun deleteAll()
}