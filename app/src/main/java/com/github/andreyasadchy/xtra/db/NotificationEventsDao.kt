package com.github.andreyasadchy.xtra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.andreyasadchy.xtra.model.NotificationEvent

@Dao
interface NotificationEventsDao {

    @Query("SELECT * FROM notification_events ORDER BY queuedAt ASC")
    fun getAll(): List<NotificationEvent>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertList(items: List<NotificationEvent>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: NotificationEvent)

    @Query("DELETE FROM notification_events WHERE eventId = :eventId")
    fun delete(eventId: String)

    @Query("DELETE FROM notification_events WHERE channelId = :channelId")
    fun deleteForChannel(channelId: String)

    @Query("DELETE FROM notification_events")
    fun deleteAll()
}
