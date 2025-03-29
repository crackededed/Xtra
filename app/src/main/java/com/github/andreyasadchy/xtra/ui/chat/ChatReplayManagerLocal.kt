package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.math.max

class ChatReplayManagerLocal @Inject constructor(
    private val getCurrentPosition: () -> Long?,
    private val getCurrentSpeed: () -> Float?,
    private val onMessage: (ChatMessage) -> Unit,
    private val clearMessages: () -> Unit,
    private val coroutineScope: CoroutineScope,
) {

    private var messages: List<ChatMessage> = emptyList()
    private var startTime = 0L
    private val list = mutableListOf<ChatMessage>()
    private var isLoading = false
    private var loadJob: Job? = null
    private var messageJob: Job? = null
    private var lastCheckedPosition = 0L
    private var playbackSpeed: Float? = null
    var isActive = true

    fun start(newMessages: List<ChatMessage>, newStartTime: Long) {
        messages = newMessages
        startTime = newStartTime
        val currentPosition = getCurrentPosition() ?: 0
        lastCheckedPosition = currentPosition
        playbackSpeed = getCurrentSpeed()
        list.clear()
        clearMessages()
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
                list.addAll(messages.filter { it.timestamp != null && it.timestamp >= (max(position - 20000, 0)) })
                isLoading = false
                startJob()
            } catch (e: Exception) {

            }
        }
    }

    private fun startJob() {
        messageJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val message = list.firstOrNull() ?: break
                if (message.timestamp != null) {
                    var currentPosition: Long
                    val messageOffset = message.timestamp
                    while (((runBlocking(Dispatchers.Main) { getCurrentPosition() } ?: 0).also { lastCheckedPosition = it } + startTime).also { currentPosition = it } < messageOffset) {
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
                            isAction = message.isAction,
                            isFirst = message.isFirst,
                            bits = message.bits,
                            systemMsg = message.systemMsg,
                            msgId = message.msgId,
                            reward = message.reward,
                            fullMsg = message.fullMsg
                        )
                    )
                } else if (!isActive) break
                list.remove(message)
            }
        }
    }

    fun updatePosition(position: Long) {
        if (lastCheckedPosition != position) {
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
        if (playbackSpeed != speed) {
            playbackSpeed = speed
            messageJob?.cancel()
            startJob()
        }
    }
}