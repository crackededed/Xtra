package com.github.andreyasadchy.xtra.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarksDao {

    @Query("SELECT * FROM bookmarks")
    fun getAllPagingSource(): PagingSource<Int, Bookmark>

    @Query("SELECT * FROM bookmarks")
    fun getAllFlow(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks")
    fun getAll(): List<Bookmark>

    @Query("SELECT * FROM bookmarks ORDER BY id DESC")
    fun getAllPagingSourceDesc(): PagingSource<Int, Bookmark>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllPSCreatedAtDesc(): PagingSource<Int,Bookmark>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt")
    fun getAllPSCreatedAt(): PagingSource<Int, Bookmark>

    @RewriteQueriesToDropUnusedColumns
    @Query("""
        SELECT *,
        (
            (
                strftime('%s', createdAt) +
                CASE 
                    WHEN lower(userType) = '' OR lower(userType) = 'affiliate'
                        THEN 14 * 24 * 60 * 60
                    ELSE 60 * 24 * 60 * 60
                END
            )
            - strftime('%s', 'now')
        ) AS expiratedAt
        FROM bookmarks ORDER BY expiratedAt DESC
        """)
    fun getAllPSExpiredAtDesc(): PagingSource<Int, Bookmark>

    @RewriteQueriesToDropUnusedColumns
    @Query("""
        SELECT *,
        (
            (
                strftime('%s', createdAt) +
                CASE 
                    WHEN lower(userType) = '' OR lower(userType) = 'affiliate'
                        THEN 14 * 24 * 60 * 60
                    ELSE 60 * 24 * 60 * 60
                END
            )
            - strftime('%s', 'now')
        ) AS expiratedAt
        FROM bookmarks ORDER BY expiratedAt
        """)
    fun getAllPSExpiredAt(): PagingSource<Int,Bookmark>

    @Query("SELECT * FROM bookmarks WHERE videoId = :id")
    fun getByVideoId(id: String): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE userId = :id")
    fun getByUserId(id: String): List<Bookmark>

    @Insert
    fun insert(video: Bookmark)

    @Delete
    fun delete(video: Bookmark)

    @Update
    fun update(video: Bookmark)
}
