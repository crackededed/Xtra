package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.VideoChatMessage
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class ChatReplayManagerLocal(
    private val createdAt: Long?,
    private val getCurrentPosition: () -> Long?,
    private val getCurrentSpeed: () -> Float?,
    private val coroutineScope: CoroutineScope,
    private val listener: ChatReplayManager.Listener,
) {
    private var liveMessages: List<ChatMessage>? = null
    private var messages: List<VideoChatMessage>? = null
    private var startTime = 0L
    private val liveList = mutableListOf<ChatMessage>()
    private val list = mutableListOf<VideoChatMessage>()
    private var started = false
    private var isLoading = false
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun setMessages(newLiveMessages: List<ChatMessage>, newMessages: List<VideoChatMessage>, newStartTime: Long) {
        if (newLiveMessages.isNotEmpty()) {
            liveMessages = newLiveMessages
            if (createdAt != null) {
                startTime = newStartTime - createdAt
            }
        } else {
            messages = newMessages
            startTime = newStartTime
        }
        if (started) {
            start()
        }
    }

    fun startLoad() {
        if (!started) {
            started = true
            if (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) {
                start()
            }
        }
    }

    fun start() {
        val currentPosition = getCurrentPosition() ?: 0
        lastCheckedPosition = currentPosition
        playbackSpeed = getCurrentSpeed()
        liveList.clear()
        list.clear()
        coroutineScope.launch {
            listener.clearMessages()
        }
        load(currentPosition + startTime)
    }

    fun stop() {
        loadJob?.cancel()
        messageJob?.cancel()
        isActive = false
    }

    private fun load(position: Long) {
        isLoading = true
        loadJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                messageJob?.cancel()
                if (!liveMessages.isNullOrEmpty()) {
                    liveMessages?.let { messages ->
                        liveList.addAll(
                            messages.filter { message ->
                                val messageOffset = if (createdAt != null && message.timestamp != null) {
                                    message.timestamp - createdAt
                                } else {
                                    null
                                }
                                messageOffset != null && messageOffset >= (max(position - 20000, 0))
                            }
                        )
                    }
                } else {
                    messages?.let { messages ->
                        list.addAll(
                            messages.filter { message ->
                                val messageOffset = if (createdAt != null && !message.createdAt.isNullOrBlank()) {
                                    TwitchApiHelper.parseIso8601DateUTC(message.createdAt)?.minus(createdAt)
                                } else {
                                    null
                                } ?: message.offsetSeconds?.times(1000L)
                                messageOffset != null && messageOffset >= (max(position - 20000, 0))
                            }
                        )
                    }
                }
                isLoading = false
                startJob()
            } catch (e: Exception) {

            }
        }
    }

    private fun startJob() {
        messageJob = coroutineScope.launch {
            while (isActive) {
                if (!liveMessages.isNullOrEmpty()) {
                    val message = liveList.firstOrNull() ?: break
                    val messageOffset = if (createdAt != null && message.timestamp != null) {
                        message.timestamp - createdAt
                    } else {
                        null
                    }
                    if (messageOffset != null) {
                        var currentPosition: Long
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
                        listener.onChatMessage(message)
                    } else {
                        if (!isActive) {
                            break
                        }
                    }
                    liveList.remove(message)
                } else {
                    val message = list.firstOrNull() ?: break
                    val messageOffset = if (createdAt != null && !message.createdAt.isNullOrBlank()) {
                        TwitchApiHelper.parseIso8601DateUTC(message.createdAt)?.minus(createdAt)
                    } else {
                        null
                    } ?: message.offsetSeconds?.times(1000L)
                    if (messageOffset != null) {
                        var currentPosition: Long
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
                        listener.onChatMessage(
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
                    } else {
                        if (!isActive) {
                            break
                        }
                    }
                    list.remove(message)
                }
            }
        }
    }

    fun updatePosition(position: Long) {
        if (started && (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) && lastCheckedPosition != position) {
            if (position - lastCheckedPosition !in 0..20000) {
                loadJob?.cancel()
                messageJob?.cancel()
                liveList.clear()
                list.clear()
                coroutineScope.launch {
                    listener.clearMessages()
                }
                load(position + startTime)
            } else {
                messageJob?.cancel()
                startJob()
            }
            lastCheckedPosition = position
        }
    }

    fun updateSpeed(speed: Float) {
        if (started && (!liveMessages.isNullOrEmpty() || !messages.isNullOrEmpty()) && playbackSpeed != speed) {
            playbackSpeed = speed
            messageJob?.cancel()
            startJob()
        }
    }
}