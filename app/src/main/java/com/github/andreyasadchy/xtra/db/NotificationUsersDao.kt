package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.andreyasadchy.xtra.model.NotificationUser

@Dao
interface NotificationUsersDao {

    @Query("SELECT * FROM notifications")
    fun getAll(): List<NotificationUser>

    @Query("SELECT * FROM notifications WHERE channelId = :id")
    fun getById(id: String): NotificationUser?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: NotificationUser)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertList(items: List<NotificationUser>)

    @Delete
    fun delete(item: NotificationUser)

    @Query("DELETE FROM notifications")
    fun deleteAll()

    @Transaction
    fun replaceAll(items: List<NotificationUser>) {
        deleteAll()
        insertList(items)
    }
}
