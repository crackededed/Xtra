package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

class ChatReplayManager @Inject constructor(
    private val networkLibrary: String?,
    private val gqlHeaders: Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val enableIntegrity: Boolean,
    private val videoId: String,
    private val startTime: Long,
    private val getCurrentPosition: () -> Long?,
    private val getCurrentSpeed: () -> Float?,
    private val onMessage: (ChatMessage) -> Unit,
    private val clearMessages: () -> Unit,
    private val getIntegrityToken: () -> Unit,
    private val coroutineScope: CoroutineScope,
) {

    private var cursor: String? = null
    private val list = mutableListOf<VideoChatMessage>()
    private var started = false
    private var isLoading = false
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun start() {
        if (!started) {
            started = true
            val currentPosition = getCurrentPosition() ?: 0
            lastCheckedPosition = currentPosition
            playbackSpeed = getCurrentSpeed()
            list.clear()
            clearMessages()
            load(currentPosition + startTime)
        }
    }

    fun stop() {
        loadJob?.cancel()
        messageJob?.cancel()
        isActive = false
    }

    private fun load(position: Long? = null) {
        isLoading = true
        loadJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val response = if (position != null) {
                    graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, offset = position.div(1000).toInt())
                } else {
                    graphQLRepository.loadVideoMessages(networkLibrary, gqlHeaders, videoId, cursor = cursor)
                }
                if (enableIntegrity) {
                    response.errors?.find { it.message == "failed integrity check" }?.let {
                        getIntegrityToken()
                        isLoading = false
                        return@launch
                    }
                }
                val comments = response.data!!.video.comments
                val messages = comments.edges.mapNotNull { comment ->
                    comment.node.let { item ->
                        item.message?.let { message ->
                            val chatMessage = StringBuilder()
                            val emotes = message.fragments?.mapNotNull { fragment ->
                                fragment.text?.let { text ->
                                    fragment.emote?.emoteID?.let { id ->
                                        TwitchEmote(
                                            id = id,
                                            begin = chatMessage.codePointCount(0, chatMessage.length),
                                            end = chatMessage.codePointCount(0, chatMessage.length) + text.lastIndex
                                        )
                                    }.also { chatMessage.append(text) }
                                }
                            }
                            val badges = message.userBadges?.mapNotNull { badge ->
                                badge.setID?.let { setId ->
                                    badge.version?.let { version ->
                                        Badge(
                                            setId = setId,
                                            version = version,
                                        )
                                    }
                                }
                            }
                            VideoChatMessage(
                                id = item.id,
                                offsetSeconds = item.contentOffsetSeconds,
                                userId = item.commenter?.id,
                                userLogin = item.commenter?.login,
                                userName = item.commenter?.displayName,
                                message = chatMessage.toString(),
                                color = message.userColor,
                                emotes = emotes,
                                badges = badges,
                                fullMsg = item.toString()
                            )
                        }
                    }
                }
                messageJob?.cancel()
                list.addAll(messages)
                cursor = if (comments.pageInfo?.hasNextPage != false) comments.edges.lastOrNull()?.cursor else null
                isLoading = false
                startJob()
            } catch (e: Exception) {

            }
        }
    }

    private fun startJob() {
        messageJob = coroutineScope.launch {
            while (isActive) {
                val message = list.firstOrNull() ?: break
                if (message.offsetSeconds != null) {
                    var currentPosition: Long
                    val messageOffset = message.offsetSeconds.times(1000)
                    while (
                        (getCurrentPosition() ?: 0).let { position ->
                            lastCheckedPosition = position
                            currentPosition = position + startTime
                            currentPosition < messageOffset
                        }
                    ) {
                        delay(max((messageOffset - currentPosition).div(playbackSpeed ?: 1f).toLong(), 0))
                    }
                    if (!isActive) {
                        break
                    }
                    onMessage(
                        ChatMessage(
                            id = message.id,
                            userId = message.userId,
                            userLogin = message.userLogin,
                            userName = message.userName,
                            message = message.message,
                            color = message.color,
                            emotes = message.emotes,
                            badges = message.badges,
                            bits = 0,
                            fullMsg = message.fullMsg
                        )
                    )
                    if (list.size <= 25 && !cursor.isNullOrBlank() && !isLoading) {
                        load()
                    }
                } else if (!isActive) break
                list.remove(message)
            }
        }
    }

    fun updatePosition(position: Long) {
        if (started && lastCheckedPosition != position) {
            if (position - lastCheckedPosition !in 0..20000) {
                loadJob?.cancel()
                messageJob?.cancel()
                list.clear()
                clearMessages()
                load(position + startTime)
            } else {
                messageJob?.cancel()
                startJob()
            }
            lastCheckedPosition = position
        }
    }

    fun updateSpeed(speed: Float) {
        if (started && playbackSpeed != speed) {
            playbackSpeed = speed
            messageJob?.cancel()
            startJob()
        }
    }
}