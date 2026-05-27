package com.github.andreyasadchy.xtra

import android.app.Application
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.repository.BookmarksRepository
import com.github.andreyasadchy.xtra.repository.ChannelSortRepository
import com.github.andreyasadchy.xtra.repository.GameSortRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.LocalGameFollowsRepository
import com.github.andreyasadchy.xtra.repository.NotificationsRepository
import com.github.andreyasadchy.xtra.repository.OfflineVideosRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchesRepository
import com.github.andreyasadchy.xtra.repository.SavedFiltersRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import org.chromium.net.RequestFinishedInfo
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class XtraModule(application: Application) {

    val httpEngine = lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
            HttpEngine.Builder(application).apply {
                addQuicHint("gql.twitch.tv", 443, 443)
                addQuicHint("www.twitch.tv", 443, 443)
                addQuicHint("7tv.io", 443, 443)
                addQuicHint("cdn.7tv.app", 443, 443)
                addQuicHint("api.betterttv.net", 443, 443)
            }.build()
        } else {
            null
        }
    }

    val cronetEngine = lazy {
        if (CronetProvider.getAllProviders(application).any { it.isEnabled }) {
            CronetEngine.Builder(application).apply {
                val userAgent = "Cronet/" + defaultUserAgent.substringAfter("Cronet/", "").substringBefore(')')
                setUserAgent(userAgent)
                @QuicOptions.Experimental
                setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
                addQuicHint("gql.twitch.tv", 443, 443)
                addQuicHint("www.twitch.tv", 443, 443)
                addQuicHint("7tv.io", 443, 443)
                addQuicHint("cdn.7tv.app", 443, 443)
                addQuicHint("api.betterttv.net", 443, 443)
            }.build().also {
                if (BuildConfig.DEBUG) {
                    it.addRequestFinishedListener(object : RequestFinishedInfo.Listener(Executors.newSingleThreadExecutor()) {
                        override fun onRequestFinished(requestInfo: RequestFinishedInfo) {
                            requestInfo.responseInfo?.let {
                                Log.i("Cronet", "${it.httpStatusCode} ${it.negotiatedProtocol} ${it.url}")
                                it.allHeadersAsList?.forEach {
                                    Log.i("Cronet", "${it.key}: ${it.value}")
                                }
                            }
                        }
                    })
                }
            }
        } else {
            null
        }
    }

    val cronetExecutor = lazy {
        Executors.newCachedThreadPool()
    }

    val okHttpClient = lazy {
        OkHttpClient.Builder().apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val sslContext = SSLContext.getInstance("TLSv1.3")
                sslContext.init(null, arrayOf(trustManager.value), null)
                sslSocketFactory(sslContext.socketFactory, trustManager.value)
            }
        }.build()
    }

    val trustManager = lazy {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        var count = 0
        val certificateFactory = CertificateFactory.getInstance("X.509")
        application.resources.openRawResource(R.raw.isrgrootx1).use {
            val certificate = certificateFactory.generateCertificate(it)
            keyStore.setCertificateEntry("cert_0", certificate)
            count += 1
        }
        val defaultTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        defaultTrustManagerFactory.init(null as KeyStore?)
        val defaultTrustManager = defaultTrustManagerFactory.trustManagers.first() as X509TrustManager
        defaultTrustManager.acceptedIssuers.forEach {
            keyStore.setCertificateEntry("cert_$count", it)
            count += 1
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        trustManagerFactory.trustManagers.first() as X509TrustManager
    }

    val json by lazy {
        Json { ignoreUnknownKeys = true }
    }

    val database by lazy {
        Room.databaseBuilder(application, AppDatabase::class.java, "database").apply {
            addMigrations(
                Migration(9, 10) { db ->
                    db.execSQL("DELETE FROM emotes")
                },
                Migration(10, 11) { db ->
                    db.execSQL("ALTER TABLE videos ADD COLUMN videoId TEXT DEFAULT null")
                },
                Migration(11, 12) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS local_follows_games (game_id TEXT NOT NULL, game_name TEXT, boxArt TEXT, PRIMARY KEY (game_id))")
                },
                Migration(12, 13) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT NOT NULL, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER NOT NULL, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(13, 14) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER, type TEXT, videoId TEXT, is_bookmark INTEGER, userType TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id = id, is_vod = is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS vod_bookmark_ignored_users (user_id TEXT NOT NULL, PRIMARY KEY (user_id))")
                },
                Migration(14, 15) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, status INTEGER NOT NULL, type TEXT, videoId TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT OR IGNORE INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id = id, is_vod = is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL, userId TEXT, userLogin TEXT, userName TEXT, userLogo TEXT, gameId TEXT, gameName TEXT, title TEXT, createdAt TEXT, thumbnail TEXT, type TEXT, duration TEXT, PRIMARY KEY (id))")
                },
                Migration(15, 16) { db ->
                    db.execSQL("ALTER TABLE bookmarks ADD COLUMN userType TEXT DEFAULT null")
                    db.execSQL("ALTER TABLE bookmarks ADD COLUMN userBroadcasterType TEXT DEFAULT null")
                },
                Migration(16, 17) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_channel (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoType TEXT, clipPeriod TEXT, PRIMARY KEY (id))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_game (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguageIndex INTEGER, clipPeriod TEXT, clipLanguageIndex INTEGER, PRIMARY KEY (id))")
                },
                Migration(17, 18) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_emotes1 (name TEXT NOT NULL, url1x TEXT, url2x TEXT, url3x TEXT, url4x TEXT, used_at INTEGER NOT NULL, PRIMARY KEY (name))")
                    db.execSQL("INSERT INTO recent_emotes1 (name, url1x, used_at) SELECT name, url, used_at FROM recent_emotes")
                    db.execSQL("DROP TABLE recent_emotes")
                    db.execSQL("ALTER TABLE recent_emotes1 RENAME TO recent_emotes")
                },
                Migration(18, 19) { db ->
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
                },
                Migration(19, 20) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_emotes1 (name TEXT NOT NULL, used_at INTEGER NOT NULL, PRIMARY KEY (name))")
                    db.execSQL("INSERT INTO recent_emotes1 (name, used_at) SELECT name, used_at FROM recent_emotes")
                    db.execSQL("DROP TABLE recent_emotes")
                    db.execSQL("ALTER TABLE recent_emotes1 RENAME TO recent_emotes")
                },
                Migration(20, 21) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS requests1 (offline_video_id INTEGER NOT NULL, url TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY (offline_video_id), FOREIGN KEY('offline_video_id') REFERENCES videos('id') ON DELETE CASCADE)")
                    db.execSQL("INSERT INTO requests1 (offline_video_id, url, path) SELECT offline_video_id, url, path FROM requests")
                    db.execSQL("DROP TABLE requests")
                    db.execSQL("ALTER TABLE requests1 RENAME TO requests")
                },
                Migration(21, 22) { db ->
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
                },
                Migration(22, 23) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, quality TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, status, type, videoId, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(23, 24) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT NOT NULL, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, quality TEXT, downloadChat INTEGER, downloadChatEmotes INTEGER, chatProgress INTEGER, chatUrl TEXT, id INTEGER NOT NULL, is_vod INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, id, is_vod) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, id, is_vod FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(24, 25) { db ->
                    db.execSQL("DROP TABLE requests")
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, 0, downloadPath, fromTime, toTime, status, type, videoId, quality, 0, 0, 0, 100, 0, 0, chatUrl, 0, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(25, 26) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, 0, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(26, 27) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS shown_notifications (channelId TEXT NOT NULL, startedAt INTEGER NOT NULL, PRIMARY KEY (channelId))")
                },
                Migration(27, 28) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS notifications (channelId TEXT NOT NULL, PRIMARY KEY (channelId))")
                },
                Migration(28, 29) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS translate_all_messages (channelId TEXT NOT NULL, PRIMARY KEY (channelId))")
                },
                Migration(29, 30) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_game1 (id TEXT NOT NULL, saveSort INTEGER, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguages TEXT, clipPeriod TEXT, clipLanguages TEXT, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO sort_game1 (id, saveSort, videoSort, videoPeriod, videoType, clipPeriod) SELECT id, saveSort, videoSort, videoPeriod, videoType, clipPeriod FROM sort_game")
                    db.execSQL("DROP TABLE sort_game")
                    db.execSQL("ALTER TABLE sort_game1 RENAME TO sort_game")
                },
                Migration(30, 31) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_game1 (id TEXT NOT NULL, streamSort TEXT, streamTags TEXT, streamLanguages TEXT, videoSort TEXT, videoPeriod TEXT, videoType TEXT, videoLanguages TEXT, clipPeriod TEXT, clipLanguages TEXT, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO sort_game1 (id, videoSort, videoPeriod, videoType, videoLanguages, clipPeriod, clipLanguages) SELECT id, videoSort, videoPeriod, videoType, videoLanguages, clipPeriod, clipLanguages FROM sort_game WHERE saveSort=1")
                    db.execSQL("DROP TABLE sort_game")
                    db.execSQL("ALTER TABLE sort_game1 RENAME TO sort_game")
                    db.execSQL("CREATE TABLE IF NOT EXISTS sort_channel1 (id TEXT NOT NULL, videoSort TEXT, videoType TEXT, clipPeriod TEXT, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO sort_channel1 (id, videoSort, videoType, clipPeriod) SELECT id, videoSort, videoType, clipPeriod FROM sort_channel WHERE saveSort=1")
                    db.execSQL("DROP TABLE sort_channel")
                    db.execSQL("ALTER TABLE sort_channel1 RENAME TO sort_channel")
                },
                Migration(31, 32) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS filters (id INTEGER NOT NULL, gameId TEXT, gameSlug TEXT, gameName TEXT, tags TEXT, languages TEXT, PRIMARY KEY (id))")
                },
                Migration(32, 33) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS recent_search (id INTEGER NOT NULL, query TEXT NOT NULL, type TEXT NOT NULL, lastSearched INTEGER NOT NULL, PRIMARY KEY (id))")
                },
                Migration(33, 34) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS playback_states (type TEXT, streamId TEXT, videoId TEXT, clipId TEXT, offlineVideoId INTEGER, channelId TEXT, channelLogin TEXT, channelName TEXT, channelImage TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, thumbnail TEXT, createdAt TEXT, viewerCount INTEGER, durationSeconds INTEGER, videoType TEXT, videoOffsetSeconds INTEGER, videoAnimatedPreviewURL TEXT, position INTEGER, paused INTEGER NOT NULL, qualities TEXT, quality TEXT, previousQuality TEXT, restoreQuality INTEGER NOT NULL, playlistUrl TEXT, restorePlaylist INTEGER NOT NULL, useCustomProxy INTEGER NOT NULL, skipAccessToken INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                },
                Migration(34, 35) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, liveCommentsArrayStarted INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, liveCommentsArrayStarted, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, max_progress, downloadPath, fromTime, toTime, status, type, videoId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, 0, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                },
                Migration(35, 36) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS videos1 (url TEXT, source_url TEXT, source_start_position INTEGER, name TEXT, channel_id TEXT, channel_login TEXT, channel_name TEXT, channel_logo TEXT, thumbnail TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, duration INTEGER, upload_date INTEGER, download_date INTEGER, last_watch_position INTEGER, progress INTEGER NOT NULL, max_progress INTEGER NOT NULL, bytes INTEGER NOT NULL, downloadPath TEXT, fromTime INTEGER, toTime INTEGER, status INTEGER NOT NULL, type TEXT, videoId TEXT, videoCreatedAt TEXT, clipId TEXT, quality TEXT, downloadChat INTEGER NOT NULL, downloadChatEmotes INTEGER NOT NULL, chatProgress INTEGER NOT NULL, maxChatProgress INTEGER NOT NULL, chatBytes INTEGER NOT NULL, chatOffsetSeconds INTEGER NOT NULL, chatUrl TEXT, playlistToFile INTEGER NOT NULL, live INTEGER NOT NULL, lastSegmentUrl TEXT, liveCommentsArrayStarted INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO videos1 (url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, clipId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, liveCommentsArrayStarted, id) SELECT url, source_url, source_start_position, name, channel_id, channel_login, channel_name, channel_logo, thumbnail, gameId, gameSlug, gameName, duration, upload_date, download_date, last_watch_position, progress, max_progress, bytes, downloadPath, fromTime, toTime, status, type, videoId, clipId, quality, downloadChat, downloadChatEmotes, chatProgress, maxChatProgress, chatBytes, chatOffsetSeconds, chatUrl, playlistToFile, live, lastSegmentUrl, liveCommentsArrayStarted, id FROM videos")
                    db.execSQL("DROP TABLE videos")
                    db.execSQL("ALTER TABLE videos1 RENAME TO videos")
                    db.execSQL("CREATE TABLE IF NOT EXISTS playback_states1 (type TEXT, streamId TEXT, videoId TEXT, clipId TEXT, offlineVideoId INTEGER, channelId TEXT, channelLogin TEXT, channelName TEXT, channelImage TEXT, gameId TEXT, gameSlug TEXT, gameName TEXT, title TEXT, thumbnail TEXT, createdAt TEXT, viewerCount INTEGER, durationSeconds INTEGER, videoType TEXT, videoOffsetSeconds INTEGER, videoCreatedAt TEXT, videoAnimatedPreviewURL TEXT, position INTEGER, paused INTEGER NOT NULL, qualities TEXT, quality TEXT, previousQuality TEXT, restoreQuality INTEGER NOT NULL, playlistUrl TEXT, restorePlaylist INTEGER NOT NULL, useCustomProxy INTEGER NOT NULL, skipAccessToken INTEGER NOT NULL, id INTEGER NOT NULL, PRIMARY KEY (id))")
                    db.execSQL("INSERT INTO playback_states1 (type, streamId, videoId, clipId, offlineVideoId, channelId, channelLogin, channelName, channelImage, gameId, gameSlug, gameName, title, thumbnail, createdAt, viewerCount, durationSeconds, videoType, videoOffsetSeconds, videoAnimatedPreviewURL, position, paused, qualities, quality, previousQuality, restoreQuality, playlistUrl, restorePlaylist, useCustomProxy, skipAccessToken, id) SELECT type, streamId, videoId, clipId, offlineVideoId, channelId, channelLogin, channelName, channelImage, gameId, gameSlug, gameName, title, thumbnail, createdAt, viewerCount, durationSeconds, videoType, videoOffsetSeconds, videoAnimatedPreviewURL, position, paused, qualities, quality, previousQuality, restoreQuality, playlistUrl, restorePlaylist, useCustomProxy, skipAccessToken, id FROM playback_states")
                    db.execSQL("DROP TABLE playback_states")
                    db.execSQL("ALTER TABLE playback_states1 RENAME TO playback_states")
                },
            )
        }.build()
    }

    val authRepository by lazy {
        AuthRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json)
    }

    val bookmarksRepository by lazy {
        BookmarksRepository(database.bookmarks(), database.bookmarkIgnoredUsers(), database.offlineVideos())
    }

    val channelSortRepository by lazy {
        ChannelSortRepository(database.channelSort())
    }

    val gameSortRepository by lazy {
        GameSortRepository(database.gameSort())
    }

    val graphQLRepository by lazy {
        GraphQLRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json)
    }

    val helixRepository by lazy {
        HelixRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json)
    }

    val localChannelFollowsRepository by lazy {
        LocalChannelFollowsRepository(database.localChannelFollows(), database.offlineVideos(), database.bookmarks())
    }

    val localGameFollowsRepository by lazy {
        LocalGameFollowsRepository(database.localGameFollows())
    }

    val notificationsRepository by lazy {
        NotificationsRepository(database.shownNotifications(), database.notificationUsers(), graphQLRepository, helixRepository)
    }

    val offlineVideosRepository by lazy {
        OfflineVideosRepository(database.offlineVideos(), database.bookmarks())
    }

    val playerRepository by lazy {
        PlayerRepository(httpEngine, cronetEngine, cronetExecutor, okHttpClient, json, database.recentEmotes(), database.translatedChannels(), database.videoPositions(), database.playbackStates(), graphQLRepository, helixRepository)
    }

    val recentSearchesRepository by lazy {
        RecentSearchesRepository(database.recentSearches())
    }

    val savedFiltersRepository by lazy {
        SavedFiltersRepository(database.savedFilters())
    }
}