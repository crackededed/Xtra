package com.github.andreyasadchy.xtra.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo

@Dao
interface VideosDao {

    @Query("SELECT * FROM videos ORDER BY id DESC")
    fun getAll(): PagingSource<Int, OfflineVideo>

    @Query("SELECT * FROM videos WHERE id = :id")
    fun getById(id: Int): OfflineVideo?

    @Query("SELECT * FROM videos WHERE url = :url")
    fun getByUrl(url: String): OfflineVideo?

    @Query("SELECT * FROM videos WHERE channel_login = :login AND live = 1 AND status != ${OfflineVideo.STATUS_DOWNLOADED}")
    fun getLiveDownload(login: String): OfflineVideo?

    @Query("SELECT * FROM videos WHERE videoId = :id")
    fun getByVideoId(id: String): List<OfflineVideo>

    @Query("SELECT * FROM videos WHERE channel_id = :id")
    fun getByUserId(id: String): List<OfflineVideo>

    @Query("SELECT * FROM videos WHERE lower(url) LIKE '%.m3u8'")
    fun getPlaylists(): List<OfflineVideo>

    @Insert
    fun insert(video: OfflineVideo): Long

    @Delete
    fun delete(video: OfflineVideo)

    @Update
    fun update(video: OfflineVideo)

    @Query("UPDATE videos SET last_watch_position = :position WHERE id = :id")
    fun updatePosition(id: Int, position: Long)

    @Query("UPDATE videos SET last_watch_position = null")
    fun deletePositions()
}
