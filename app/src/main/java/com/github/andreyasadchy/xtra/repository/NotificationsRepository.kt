package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.NotificationUsersDao
import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class NotificationsRepository(
    private val shownNotificationsDao: ShownNotificationsDao,
    private val notificationUsersDao: NotificationUsersDao,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) {

    suspend fun getNewStreams(networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>): List<Stream> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Stream>()
        val notificationIds = notificationUsersDao.getAll().map { it.channelId }
        if (notificationIds.isNotEmpty() &&
            gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() &&
            helixHeaders[C.HEADER_TOKEN].isNullOrBlank()
        ) {
            return@withContext emptyList()
        }
        if (notificationIds.isNotEmpty()) {
            val localStreams = if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    gqlQueryLocal(networkLibrary, gqlHeaders, notificationIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (gqlException: Exception) {
                    if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        throw gqlException
                    }
                    helixLocal(networkLibrary, helixHeaders, notificationIds)
                }
            } else {
                helixLocal(networkLibrary, helixHeaders, notificationIds)
            }
            list.addAll(localStreams)
        }
        if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                gqlQueryLoad(networkLibrary, gqlHeaders)
                    .filterNot { item -> list.any { it.channelId == item.channelId } }
                    .let(list::addAll)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (list.isEmpty()) {
                    throw e
                }
            }
        }
        val liveList = list.mapNotNull { stream ->
            stream.channelId.takeUnless { it.isNullOrBlank() }?.let { channelId ->
                stream.createdAt.takeUnless { it.isNullOrBlank() }?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }?.let { createdAt ->
                    ShownNotification(channelId, createdAt)
                }
            }
        }
        val oldList = shownNotificationsDao.getAll()
        oldList.filter { item -> liveList.find { it.channelId == item.channelId } == null }.let {
            shownNotificationsDao.deleteList(it)
        }
        shownNotificationsDao.insertList(liveList)
        val newStreams = liveList.mapNotNull { item ->
            item.takeIf { oldList.find { it.channelId == item.channelId }.let { it == null || it.startedAt < item.startedAt } }?.channelId
        }
        list.filter { it.channelId in newStreams }
    }

    suspend fun syncNotificationUsers(networkLibrary: String?, gqlHeaders: Map<String, String>) = withContext(Dispatchers.IO) {
        if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            return@withContext false
        }
        val users = mutableListOf<NotificationUser>()
        var offset: String? = null
        do {
            val response = graphQLRepository.loadQueryUserFollowedUsers(networkLibrary, gqlHeaders, 100, offset)
            response.errors?.firstOrNull()?.let { throw Exception(it.message) }
            val data = response.data!!.user!!.follows!!
            val items = data.edges!!
            users.addAll(items.mapNotNull { item ->
                item?.node?.takeIf {
                    it.self?.follower?.notificationSettings?.isEnabled == true
                }?.id?.let(::NotificationUser)
            })
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!offset.isNullOrBlank() && data.pageInfo?.hasNextPage == true)
        notificationUsersDao.replaceAll(users)
        true
    }

    private suspend fun gqlQueryLoad(networkLibrary: String?, gqlHeaders: Map<String, String>): List<Stream> {
        val list = mutableListOf<Stream>()
        var offset: String? = null
        do {
            val response = graphQLRepository.loadQueryUserFollowedStreams(networkLibrary, gqlHeaders, 100, offset)
            val data = response.data!!.user!!.followedLiveUsers!!
            val items = data.edges!!
            items.mapNotNull { item ->
                item?.node?.let {
                    if (it.self?.follower?.notificationSettings?.isEnabled == true) {
                        Stream(
                            id = it.stream?.id,
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            channelImageURL = it.profileImageURL,
                            gameId = it.stream?.game?.id,
                            gameSlug = it.stream?.game?.slug,
                            gameName = it.stream?.game?.displayName,
                            title = it.stream?.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = it.stream?.previewImageURL,
                            createdAt = it.stream?.createdAt?.toString(),
                            viewerCount = it.stream?.viewersCount,
                            tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                        )
                    } else null
                }
            }.let { list.addAll(it) }
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!items.lastOrNull()?.cursor?.toString().isNullOrBlank() && data.pageInfo?.hasNextPage == true)
        return list
    }

    private suspend fun gqlQueryLocal(networkLibrary: String?, gqlHeaders: Map<String, String>, ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map { list ->
            graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders, list)
        }.flatMap { it.data!!.users!! }
        val list = items.mapNotNull { item ->
            item?.let {
                if (it.stream?.viewersCount != null) {
                    Stream(
                        id = it.stream.id,
                        channelId = it.id,
                        channelLogin = it.login,
                        channelName = it.displayName,
                        channelImageURL = it.profileImageURL,
                        gameId = it.stream.game?.id,
                        gameSlug = it.stream.game?.slug,
                        gameName = it.stream.game?.displayName,
                        title = it.stream.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = it.stream.previewImageURL,
                        createdAt = it.stream.createdAt?.toString(),
                        viewerCount = it.stream.viewersCount,
                        tags = it.stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                } else null
            }
        }
        return list
    }

    private suspend fun helixLocal(networkLibrary: String?, helixHeaders: Map<String, String>, ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map {
            helixRepository.getStreams(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                ids = it
            )
        }.flatMap { it.data }
        val users = items.mapNotNull { it.channelId }.chunked(100).map {
            helixRepository.getUsers(
                networkLibrary = networkLibrary,
                headers = helixHeaders,
                ids = it
            )
        }.flatMap { it.data }
        val list = items.mapNotNull {
            if (it.viewerCount != null) {
                Stream(
                    id = it.id,
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    channelImageURL = it.channelId?.let { id ->
                        users.find { user -> user.id == id }?.profileImageURL
                    },
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    thumbnailURL = it.thumbnailURL,
                    createdAt = it.startedAt,
                    viewerCount = it.viewerCount,
                    tags = it.tags,
                )
            } else null
        }
        return list
    }

    suspend fun saveList(list: List<ShownNotification>) = withContext(Dispatchers.IO) {
        shownNotificationsDao.insertList(list)
    }

    suspend fun getUserById(id: String) = withContext(Dispatchers.IO) {
        notificationUsersDao.getById(id)
    }

    suspend fun saveUser(item: NotificationUser) = withContext(Dispatchers.IO) {
        notificationUsersDao.insert(item)
    }

    suspend fun deleteUser(item: NotificationUser) = withContext(Dispatchers.IO) {
        notificationUsersDao.delete(item)
    }
}
