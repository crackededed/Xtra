package com.github.andreyasadchy.xtra.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.ui.Bookmark
import com.github.andreyasadchy.xtra.model.ui.LocalFollowChannel
import com.github.andreyasadchy.xtra.model.ui.LocalFollowGame
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.SavedFilter
import com.github.andreyasadchy.xtra.model.ui.SortChannel
import com.github.andreyasadchy.xtra.model.ui.SortGame
import com.github.andreyasadchy.xtra.model.ui.TranslateAllMessagesUser
import com.github.andreyasadchy.xtra.model.ui.VodBookmarkIgnoredUser

@Database(
    entities = [OfflineVideo::class, RecentEmote::class, VideoPosition::class, LocalFollowChannel::class, LocalFollowGame::class, Bookmark::class, VodBookmarkIgnoredUser::class, SortChannel::class, SortGame::class, ShownNotification::class, NotificationUser::class, TranslateAllMessagesUser::class, SavedFilter::class],
    version = 32
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videos(): VideosDao
    abstract fun recentEmotes(): RecentEmotesDao
    abstract fun videoPositions(): VideoPositionsDao
    abstract fun localFollowsChannel(): LocalFollowsChannelDao
    abstract fun localFollowsGame(): LocalFollowsGameDao
    abstract fun bookmarks(): BookmarksDao
    abstract fun vodBookmarkIgnoredUsers(): VodBookmarkIgnoredUsersDao
    abstract fun sortChannelDao(): SortChannelDao
    abstract fun sortGameDao(): SortGameDao
    abstract fun shownNotificationsDao(): ShownNotificationsDao
    abstract fun notificationsDao(): NotificationUsersDao
    abstract fun translateAllMessagesUsersDao(): TranslateAllMessagesUsersDao
    abstract fun savedFiltersDao(): SavedFiltersDao
}