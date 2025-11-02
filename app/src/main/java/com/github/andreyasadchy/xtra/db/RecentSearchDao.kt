package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.ui.RecentSearch

@Dao
interface RecentSearchDao {

    @Query("SELECT * FROM recent_search ORDER BY lastSearched DESC LIMIT :limit")
    fun recentSearches(limit: Int): List<RecentSearch>

    @Query("SELECT * FROM recent_search WHERE `query` = :query")
    fun find(query: String) : RecentSearch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(recentSearch: RecentSearch)

    @Delete
    fun delete(recentSearch: RecentSearch)

    @Query("DELETE FROM recent_search")
    fun deleteAll()
}