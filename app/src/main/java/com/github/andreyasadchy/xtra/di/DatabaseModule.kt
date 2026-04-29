package com.github.andreyasadchy.xtra.di

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.db.BookmarkIgnoredUsersDao
import com.github.andreyasadchy.xtra.db.BookmarksDao
import com.github.andreyasadchy.xtra.db.ChannelSortDao
import com.github.andreyasadchy.xtra.db.GameSortDao
import com.github.andreyasadchy.xtra.db.LocalChannelFollowsDao
import com.github.andreyasadchy.xtra.db.LocalGameFollowsDao
import com.github.andreyasadchy.xtra.db.NotificationUsersDao
import com.github.andreyasadchy.xtra.db.OfflineVideosDao
import com.github.andreyasadchy.xtra.db.PlaybackStatesDao
import com.github.andreyasadchy.xtra.db.RecentEmotesDao
import com.github.andreyasadchy.xtra.db.RecentSearchesDao
import com.github.andreyasadchy.xtra.db.SavedFiltersDao
import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.db.TranslatedChannelsDao
import com.github.andreyasadchy.xtra.db.VideoPositionsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {

    @Singleton
    @Provides
    fun providesOfflineVideosDao(database: AppDatabase): OfflineVideosDao = database.offlineVideos()

    @Singleton
    @Provides
    fun providesRecentEmotesDao(database: AppDatabase): RecentEmotesDao = database.recentEmotes()

    @Singleton
    @Provides
    fun providesVideoPositions(database: AppDatabase): VideoPositionsDao = database.videoPositions()

    @Singleton
    @Provides
    fun providesLocalChannelFollowsDao(database: AppDatabase): LocalChannelFollowsDao = database.localChannelFollows()

    @Singleton
    @Provides
    fun providesLocalGameFollowsDao(database: AppDatabase): LocalGameFollowsDao = database.localGameFollows()

    @Singleton
    @Provides
    fun providesBookmarksDao(database: AppDatabase): BookmarksDao = database.bookmarks()

    @Singleton
    @Provides
    fun providesBookmarkIgnoredUsersDao(database: AppDatabase): BookmarkIgnoredUsersDao = database.bookmarkIgnoredUsers()

    @Singleton
    @Provides
    fun providesChannelSortDao(database: AppDatabase): ChannelSortDao = database.channelSort()

    @Singleton
    @Provides
    fun providesGameSortDao(database: AppDatabase): GameSortDao = database.gameSort()

    @Singleton
    @Provides
    fun providesShownNotificationsDao(database: AppDatabase): ShownNotificationsDao = database.shownNotifications()

    @Singleton
    @Provides
    fun providesNotificationUsersDao(database: AppDatabase): NotificationUsersDao = database.notificationUsers()

    @Singleton
    @Provides
    fun providesTranslatedChannelsDao(database: AppDatabase): TranslatedChannelsDao = database.translatedChannels()

    @Singleton
    @Provides
    fun providesSavedFiltersDao(database: AppDatabase): SavedFiltersDao = database.savedFilters()

    @Singleton
    @Provides
    fun providesRecentSearchesDao(database: AppDatabase): RecentSearchesDao = database.recentSearches()

    @Singleton
    @Provides
    fun providesPlaybackStatesDao(database: AppDatabase): PlaybackStatesDao = database.playbackStates()

    @Singleton
    @Provides
    fun providesAppDatabase(application: Application): AppDatabase =
        Room.databaseBuilder(application, AppDatabase::class.java, "database")
            .addMigrations(
                object : Migration(9, 10) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("DELETE FROM emotes")
                    }
                },
                object : Migration(10, 11) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE videos ADD COLUMN videoId TEXT DEFAULT null")
                    }
                },
                object : Migration(11, 12) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games (game_id TEXT NOT NULL, game_name TEXT, boxArt TEXT, PRIMARY KEY (game_id))")
                    }
                },
                object : Migration(12, 13) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT NOT NULL, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER NOT NULL, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    }
                },
                object : Migration(13, 14) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER, type TEXT, videoId TEXT, is_bookmark INTEGER, userType TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id = id, is_vod = is_vod FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                        db.execSQL("CREATE TABLE IF NOT EXISTS vod_bookmark_ignored_users (user_id TEXT NOT NULL, PRIMARY KEY (user_id))")
                    }
                },
                object : Migration(14, 15) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT OR IGNORE INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id = id, is_vod = is_vod FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL, userId TEXT, userLogin TEXT, userName TEXT, userLogo TEXT, gameId TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, PRIMARY KEY (id))")
                    }
                },
                object : Migration(15, 16) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE bookmarks ADD COLUMN userType TEXT DEFAULT null")
                        db.execSQL("ALTER TABLE bookmarks ADD COLUMN userBroadcasterType TEXT DEFAULT null")
                    }
                },
                object : Migration(16, 17) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS sort_channel (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoType TEXT, clipPeriod TEXT, PRIMARY KEY (id))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS sort_game (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguageIndex INTEGER, clipPeriod TEXT, clipLanguageIndex INTEGER, PRIMARY KEY (id))")
                    }
                },
                object : Migration(17, 18) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS recent_emotes1 (name TEXT NOT NULL, url1x TEXT, url2x TEXT, url3x TEXT, url4x TEXT, used_at INTEGER NOT NULL, PRIMARY KEY (name))")
                        db.execSQL("INSERT INTO recent_emotes1 (name, url1x, used_at) SELECT name, url, used_at FROM recent_emotes")
                        db.execSQL("DROP TABLE recent_emotes")
                        db.execSQL("ALTER TABLE recent_emotes1 RENAME TO recent_emotes")
                    }
                },
                object : Migration(18, 19) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks1 (videoId TEXT, userId TEXT, userLogin TEXT, userName TEXT, userType TEXT, userBroadcasterType TEXT, userLogo TEXT, gameId TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, animatedPreviewURL TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO bookmarks1 (videoId, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration) SELECT id, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration FROM bookmarks")
                        db.execSQL("DROP TABLE bookmarks")
                        db.execSQL("ALTER TABLE bookmarks1 RENAME TO bookmarks")
                        db.execSQL("CREATE TABLE IF NOT EXISTS local_follows1 (userId TEXT, userLogin TEXT, userName TEXT, channelLogo TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO local_follows1 (userId, userLogin, userName, channelLogo) SELECT user_id, user_login, user_name, channelLogo FROM local_follows")
                        db.execSQL("DROP TABLE local_follows")
                        db.execSQL("ALTER TABLE local_follows1 RENAME TO local_follows")
                        db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games1 (gameId TEXT, gameName TEXT, boxArt TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO local_follows_games1 (gameId, gameName, boxArt) SELECT game_id, game_name, boxArt FROM local_follows_games")
                        db.execSQL("DROP TABLE local_follows_games")
                        db.execSQL("ALTER TABLE local_follows_games1 RENAME TO local_follows_games")
                        db.execSQL("CREATE TABLE IF NOT EXISTS requests1 (offline_video_id INTEGER NOT NULL, url TEXT NOT NULL, path TEXT NOT NULL, video_id TEXT, video_type TEXT, segment_from INTEGER, segment_to INTEGER, PRIMARY KEY (offline_video_id), FOREIGN KEY('offline_video_id') REFERENCES videos('id') ON DELETE CASCADE)")
                        db.execSQL("INSERT INTO requests1 (offline_video_id, url, path, video_id, segment_from, segment_to) SELECT offline_video_id, url, path, video_id, segment_from, segment_to FROM requests")
                        db.execSQL("DROP TABLE requests")
                        db.execSQL("ALTER TABLE requests1 RENAME TO requests")
                    }
                },
                object : Migration(19, 20) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS recent_emotes1 (name TEXT NOT NULL, used_at INTEGER NOT NULL, PRIMARY KEY (name))")
                        db.execSQL("INSERT INTO recent_emotes1 (name, used_at) SELECT name, used_at FROM recent_emotes")
                        db.execSQL("DROP TABLE recent_emotes")
                        db.execSQL("ALTER TABLE recent_emotes1 RENAME TO recent_emotes")
                    }
                },
                object : Migration(20, 21) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS requests1 (offline_video_id INTEGER NOT NULL, url TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY (offline_video_id), FOREIGN KEY('offline_video_id') REFERENCES videos('id') ON DELETE CASCADE)")
                        db.execSQL("INSERT INTO requests1 (offline_video_id, url, path) SELECT offline_video_id, url, path FROM requests")
                        db.execSQL("DROP TABLE requests")
                        db.execSQL("ALTER TABLE requests1 RENAME TO requests")
                    }
                },
                object : Migration(21, 22) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks1 (videoId TEXT, userId TEXT, userLogin TEXT, userName TEXT, userType TEXT, userBroadcasterType TEXT, userLogo TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, animatedPreviewURL TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO bookmarks1 (videoId, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration, animatedPreviewURL, id) SELECT videoId, userId, userLogin, userName, userType, userBroadcasterType, userLogo, gameId, gameName, title, createdAt, thumbnail, type, duration, animatedPreviewURL, id FROM bookmarks")
                        db.execSQL("DROP TABLE bookmarks")
                        db.execSQL("ALTER TABLE bookmarks1 RENAME TO bookmarks")
                        db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games1 (gameId TEXT, gameSlug TEXT, gameName TEXT, boxArt TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO local_follows_games1 (gameId, gameName, boxArt, id) SELECT gameId, gameName, boxArt, id FROM local_follows_games")
                        db.execSQL("DROP TABLE local_follows_games")
                        db.execSQL("ALTER TABLE local_follows_games1 RENAME TO local_follows_games")
                    }
                },
                object : Migration(22, 23) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, quality TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    }
                },
                object : Migration(23, 24) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, quality TEXT, downloadChat INTEGER, downloadChatEmotes INTEGER, chatProgress INTEGER, chatUrl TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, id, is_vod FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    }
                },
                object : Migration(24, 25) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("DROP TABLE requests")
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, 0, downloadPath, fromTime, toTime, status, type, videoId, quality, 0, 0, 0, 100, 0, 0, chatUrl, 0, id FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    }
                },
                object : Migration(25, 26) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, 0, id FROM videos")
                        db.execSQL("DROP TABLE videos")
                        db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    }
                },
                object : Migration(26, 27) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS shown_notifications (channelId TEXT NOT NULL, startedAt INTEGER NOT NULL, PRIMARY KEY (channelId))")
                    }
                },
                object : Migration(27, 28) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS notifications (channelId TEXT NOT NULL, PRIMARY KEY (channelId))")
                    }
                },
                object : Migration(28, 29) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS translate_all_messages (channelId TEXT NOT NULL, PRIMARY KEY (channelId))")
                    }
                },
                object : Migration(29, 30) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS sort_game1 (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguages TEXT, clipPeriod TEXT, clipLanguages TEXT, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO sort_game1 (id, saveSort, videoSort, videoPeriod, videoType, clipPeriod) SELECT id, saveSort, videoSort, videoPeriod, videoType, clipPeriod FROM sort_game")
                        db.execSQL("DROP TABLE sort_game")
                        db.execSQL("ALTER TABLE sort_game1 RENAME TO sort_game")
                    }
                },
                object : Migration(30, 31) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS sort_game1 (id TEXT NOT NULL, streamSort TEXT, streamTags TEXT, streamLanguages TEXT, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguages TEXT, clipPeriod TEXT, clipLanguages TEXT, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO sort_game1 (id, videoSort, videoPeriod, videoType, videoLanguages, clipPeriod, clipLanguages) SELECT id, videoSort, videoPeriod, videoType, videoLanguages, clipPeriod, clipLanguages FROM sort_game WHERE saveSort=1")
                        db.execSQL("DROP TABLE sort_game")
                        db.execSQL("ALTER TABLE sort_game1 RENAME TO sort_game")
                        db.execSQL("CREATE TABLE IF NOT EXISTS sort_channel1 (id TEXT NOT NULL, videoSort TEXT, videoType TEXT, clipPeriod TEXT, PRIMARY KEY (id))")
                        db.execSQL("INSERT INTO sort_channel1 (id, videoSort, videoType, clipPeriod) SELECT id, videoSort, videoType, clipPeriod FROM sort_channel WHERE saveSort=1")
                        db.execSQL("DROP TABLE sort_channel")
                        db.execSQL("ALTER TABLE sort_channel1 RENAME TO sort_channel")
                    }
                },
                object : Migration(31, 32) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS filters (id INTEGER NOT NULL, gameId TEXT, gameSlug TEXT, gameName TEXT, tags TEXT, languages TEXT, PRIMARY KEY (id))")
                    }
                },
                object : Migration(32, 33) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS recent_search (id INTEGER NOT NULL, query TEXT NOT NULL, type TEXT NOT NULL, lastSearched INTEGER NOT NULL, PRIMARY KEY (id))")
                    }
                },
                object : Migration(33, 34) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS playback_states (type TEXT, streamId TEXT, videoId TEXT, clipId TEXT, offlineVideoId INTEGER, channelId TEXT, channelLogin TEXT, channelName TEXT, channelImage TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, thumbnail TEXT, createdAt TEXT, viewerCount INTEGER, durationSeconds INTEGER, videoType TEXT, videoOffsetSeconds INTEGER, videoAnimatedPreviewURL TEXT, position INTEGER, paused INTEGER NOT NULL, qualities TEXT, quality TEXT, previousQuality TEXT, restoreQuality INTEGER NOT NULL, playlistUrl TEXT, restorePlaylist INTEGER NOT NULL, useCustomProxy INTEGER NOT NULL, skipAccessToken INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    }
                },
            )
            .build()
}
