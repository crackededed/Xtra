package com.github.andreyasadchy.xtra.ui.chat

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.chat.*
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.common.BaseViewModel
import com.github.andreyasadchy.xtra.ui.player.ChatReplayManager
import com.github.andreyasadchy.xtra.ui.view.chat.ChatView
import com.github.andreyasadchy.xtra.ui.view.chat.MAX_ADAPTER_COUNT
import com.github.andreyasadchy.xtra.util.SingleLiveEvent
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.*
import com.github.andreyasadchy.xtra.util.nullIfEmpty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.collections.Collection
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.asReversed
import kotlin.collections.associateBy
import kotlin.collections.chunked
import kotlin.collections.contains
import kotlin.collections.containsKey
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.hashSetOf
import kotlin.collections.isNotEmpty
import kotlin.collections.isNullOrEmpty
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.sortedBy

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ApiRepository,
    private val playerRepository: PlayerRepository,
    private val okHttpClient: OkHttpClient) : BaseViewModel(), ChatView.MessageSenderCallback {

    val recentEmotes: LiveData<List<Emote>> by lazy {
        MediatorLiveData<List<Emote>>().apply {
            addSource(userEmotes) { twitch ->
                removeSource(userEmotes)
                addSource(_otherEmotes) { other ->
                    removeSource(_otherEmotes)
                    addSource(playerRepository.loadRecentEmotes()) { recent ->
                        value = recent.filter { (twitch.contains<Emote>(it) || other.contains(it)) }
                    }
                }
            }
        }
    }
    private val _otherEmotes = MutableLiveData<List<Emote>>()
    val otherEmotes: LiveData<List<Emote>>
        get() = _otherEmotes

    val recentMessages = MutableLiveData<List<LiveChatMessage>>()
    val globalBadges = MutableLiveData<List<TwitchBadge>?>()
    val channelBadges = MutableLiveData<List<TwitchBadge>>()
    val cheerEmotes = MutableLiveData<List<CheerEmote>>()
    var emoteSetsAdded = false
    val userEmotes = MutableLiveData<List<Emote>>()
    val reloadMessages = MutableLiveData<Boolean>()
    val roomState = MutableLiveData<RoomState>()
    val command = MutableLiveData<Command>()
    val reward = MutableLiveData<ChatMessage>()
    val pointsEarned = MutableLiveData<PointsEarned>()
    var showRaids = false
    val raid = MutableLiveData<Raid>()
    val raidClicked = MutableLiveData<Boolean>()
    var raidAutoSwitch = false
    var raidNewId = true
    var raidClosed = false
    val viewerCount = MutableLiveData<Int?>()
    var streamId: String? = null

    private val _chatMessages by lazy {
        MutableLiveData<MutableList<ChatMessage>>().apply { value = Collections.synchronizedList(ArrayList(MAX_ADAPTER_COUNT + 1)) }
    }
    val chatMessages: LiveData<MutableList<ChatMessage>>
        get() = _chatMessages
    private val _newMessage by lazy { MutableLiveData<ChatMessage>() }
    val newMessage: LiveData<ChatMessage>
        get() = _newMessage

    var chat: ChatController? = null

    private val _newChatter by lazy { SingleLiveEvent<Chatter>() }
    val newChatter: LiveData<Chatter>
        get() = _newChatter

    val chatters: Collection<Chatter>?
        get() = (chat as? LiveChatController)?.chatters?.values

    fun startLive(useSSl: Boolean, usePubSub: Boolean, account: Account, isLoggedIn: Boolean, helixClientId: String?, gqlClientId: String?, gqlClientId2: String?, channelId: String?, channelLogin: String?, channelName: String?, streamId: String?, emoteQuality: String, animateGifs: Boolean, showUserNotice: Boolean, showClearMsg: Boolean, showClearChat: Boolean, collectPoints: Boolean, notifyPoints: Boolean, showRaids: Boolean, autoSwitchRaids: Boolean, enableRecentMsg: Boolean? = false, recentMsgLimit: String? = null, useApiCommands: Boolean) {
        if (chat == null && channelLogin != null) {
            this.streamId = streamId
            this.showRaids = showRaids
            raidAutoSwitch = autoSwitchRaids
            chat = LiveChatController(
                useSSl = useSSl,
                usePubSub = usePubSub,
                account = account,
                isLoggedIn = isLoggedIn,
                helixClientId = helixClientId,
                gqlClientId = gqlClientId,
                gqlClientId2 = gqlClientId2,
                channelId = channelId,
                channelLogin = channelLogin,
                displayName = channelName,
                animateGifs = animateGifs,
                showUserNotice = showUserNotice,
                showClearMsg = showClearMsg,
                showClearChat = showClearChat,
                collectPoints = collectPoints,
                notifyPoints = notifyPoints,
                useApiCommands = useApiCommands
            )
            init(
                helixClientId = helixClientId,
                helixToken = account.helixToken,
                gqlClientId = gqlClientId,
                channelId = channelId,
                channelLogin = channelLogin,
                emoteQuality = emoteQuality,
                animateGifs = animateGifs,
                enableRecentMsg = enableRecentMsg,
                recentMsgLimit = recentMsgLimit
            )
        }
    }

    fun startReplay(account: Account, helixClientId: String?, gqlClientId: String?, channelId: String?, channelLogin: String?, videoId: String, startTime: Double, getCurrentPosition: () -> Double, emoteQuality: String, animateGifs: Boolean) {
        if (chat == null) {
            chat = VideoChatController(
                clientId = gqlClientId,
                videoId = videoId,
                startTime = startTime,
                getCurrentPosition = getCurrentPosition
            )
            init(
                helixClientId = helixClientId,
                helixToken = account.helixToken,
                gqlClientId = gqlClientId,
                channelId = channelId,
                channelLogin = channelLogin,
                emoteQuality = emoteQuality,
                animateGifs = animateGifs
            )
        }
    }

    fun start() {
        chat?.start()
    }

    fun stop() {
        chat?.pause()
    }

    override fun send(message: CharSequence) {
        chat?.send(message)
    }

    override fun onCleared() {
        chat?.stop()
        super.onCleared()
    }

    private fun init(helixClientId: String?, helixToken: String?, gqlClientId: String?, channelId: String?, channelLogin: String?, emoteQuality: String, animateGifs: Boolean, enableRecentMsg: Boolean? = false, recentMsgLimit: String? = null) {
        chat?.start()
        loadEmotes(helixClientId, helixToken, gqlClientId, channelId, channelLogin, emoteQuality, animateGifs)
        if (channelLogin != null && enableRecentMsg == true) {
            loadRecentMessages(channelLogin, recentMsgLimit)
        }
    }

    private fun loadEmotes(helixClientId: String?, helixToken: String?, gqlClientId: String?, channelId: String?, channelLogin: String?, emoteQuality: String, animateGifs: Boolean) {
        val list = mutableListOf<Emote>()
        savedGlobalBadges.also {
            if (!it.isNullOrEmpty()) {
                globalBadges.value = it
                reloadMessages.value = true
            } else {
                viewModelScope.launch {
                    try {
                        repository.loadGlobalBadges(helixClientId, helixToken, gqlClientId, emoteQuality).let { badges ->
                            if (badges.isNotEmpty()) {
                                savedGlobalBadges = badges
                                globalBadges.value = badges
                                reloadMessages.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global badges", e)
                    }
                }
            }
        }
        globalStvEmotes.also {
            if (!it.isNullOrEmpty()) {
                (chat as? LiveChatController)?.addEmotes(it)
                list.addAll(it)
                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                reloadMessages.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadGlobalStvEmotes().body()?.emotes?.let { emotes ->
                            if (emotes.isNotEmpty()) {
                                globalStvEmotes = emotes
                                (chat as? LiveChatController)?.addEmotes(emotes)
                                list.addAll(emotes)
                                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                                reloadMessages.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global 7tv emotes", e)
                    }
                }
            }
        }
        globalBttvEmotes.also {
            if (!it.isNullOrEmpty()) {
                (chat as? LiveChatController)?.addEmotes(it)
                list.addAll(it)
                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                reloadMessages.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadGlobalBttvEmotes().body()?.emotes?.let { emotes ->
                            if (emotes.isNotEmpty()) {
                                globalBttvEmotes = emotes
                                (chat as? LiveChatController)?.addEmotes(emotes)
                                list.addAll(emotes)
                                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                                reloadMessages.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global BTTV emotes", e)
                    }
                }
            }
        }
        globalFfzEmotes.also {
            if (!it.isNullOrEmpty()) {
                (chat as? LiveChatController)?.addEmotes(it)
                list.addAll(it)
                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                reloadMessages.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadBttvGlobalFfzEmotes().body()?.emotes?.let { emotes ->
                            if (emotes.isNotEmpty()) {
                                globalFfzEmotes = emotes
                                (chat as? LiveChatController)?.addEmotes(emotes)
                                list.addAll(emotes)
                                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                                reloadMessages.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global FFZ emotes", e)
                    }
                }
            }
        }
        if (!channelId.isNullOrBlank() || !channelLogin.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    repository.loadChannelBadges(helixClientId, helixToken, gqlClientId, channelId, channelLogin, emoteQuality).let {
                        if (it.isNotEmpty()) {
                            channelBadges.postValue(it)
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load badges for channel $channelId", e)
                }
            }
            viewModelScope.launch {
                try {
                    repository.loadCheerEmotes(helixClientId, helixToken, gqlClientId, channelId, channelLogin, animateGifs).let {
                        if (it.isNotEmpty()) {
                            cheerEmotes.postValue(it)
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cheermotes for channel $channelId", e)
                }
            }
        }
        if (!channelId.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    playerRepository.loadStvEmotes(channelId).body()?.emotes?.let {
                        if (it.isNotEmpty()) {
                            (chat as? LiveChatController)?.addEmotes(it)
                            list.addAll(it)
                            _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load 7tv emotes for channel $channelId", e)
                }
            }
            viewModelScope.launch {
                try {
                    playerRepository.loadBttvEmotes(channelId).body()?.emotes?.let {
                        if (it.isNotEmpty()) {
                            (chat as? LiveChatController)?.addEmotes(it)
                            list.addAll(it)
                            _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load BTTV emotes for channel $channelId", e)
                }
            }
            viewModelScope.launch {
                try {
                    playerRepository.loadBttvFfzEmotes(channelId).body()?.emotes?.let {
                        if (it.isNotEmpty()) {
                            (chat as? LiveChatController)?.addEmotes(it)
                            list.addAll(it)
                            _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                            reloadMessages.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load FFZ emotes for channel $channelId", e)
                }
            }
        }
    }

    fun loadRecentMessages(channelLogin: String, recentMsgLimit: String?) {
        viewModelScope.launch {
            try {
                playerRepository.loadRecentMessages(channelLogin, (recentMsgLimit ?: "100")).body()?.messages?.let {
                    if (it.isNotEmpty()) {
                        recentMessages.postValue(it)
                        reloadMessages.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load recent messages for channel $channelLogin", e)
            }
        }
    }

    fun reloadEmotes(helixClientId: String?, helixToken: String?, gqlClientId: String?, channelId: String?, channelLogin: String?, emoteQuality: String, animateGifs: Boolean) {
        savedGlobalBadges = null
        globalStvEmotes = null
        globalBttvEmotes = null
        globalFfzEmotes = null
        loadEmotes(helixClientId, helixToken, gqlClientId, channelId, channelLogin, emoteQuality, animateGifs)
    }

    inner class LiveChatController(
        private val useSSl: Boolean,
        private val usePubSub: Boolean,
        private val account: Account,
        private val isLoggedIn: Boolean,
        private val helixClientId: String?,
        private val gqlClientId: String?,
        private val gqlClientId2: String?,
        private val channelId: String?,
        private val channelLogin: String,
        displayName: String?,
        private val animateGifs: Boolean,
        private val showUserNotice: Boolean,
        private val showClearMsg: Boolean,
        private val showClearChat: Boolean,
        private val collectPoints: Boolean,
        private val notifyPoints: Boolean,
        private val useApiCommands: Boolean) : ChatController(), LiveChatListener, PubSubListener, ChatView.RaidCallback {

        private var chat: LiveChatThread? = null
        private var loggedInChat: LoggedInChatThread? = null
        private var pubSub: PubSubWebSocket? = null
        private val allEmotesMap = mutableMapOf<String, Emote>()
        private var usedRaidId: String? = null

        val chatters = ConcurrentHashMap<String?, Chatter>()

        init {
            displayName?.let { chatters[it] = Chatter(it) }
        }

        override fun send(message: CharSequence) {
            if (useApiCommands) {
                if (message.toString().startsWith("/")) {
                    sendCommand(message)
                } else {
                    sendMessage(message)
                }
            } else {
                if (message.toString() == "/dc" || message.toString() == "/disconnect") {
                    if (chat?.isActive == true) {
                        disconnect()
                    }
                } else {
                    loggedInChat?.send(message)
                    val usedEmotes = hashSetOf<RecentEmote>()
                    val currentTime = System.currentTimeMillis()
                    message.split(' ').forEach { word ->
                        allEmotesMap[word]?.let { usedEmotes.add(RecentEmote(word, it.url1x, it.url2x, it.url3x, it.url4x, currentTime)) }
                    }
                    if (usedEmotes.isNotEmpty()) {
                        playerRepository.insertRecentEmotes(usedEmotes)
                    }
                }
            }
        }

        override fun start() {
            pause()
            chat = TwitchApiHelper.startChat(useSSl, isLoggedIn, channelLogin, showUserNotice, showClearMsg, showClearChat, usePubSub, this, this)
            if (isLoggedIn) {
                loggedInChat = TwitchApiHelper.startLoggedInChat(useSSl, account.login, account.gqlToken?.nullIfEmpty() ?: account.helixToken, channelLogin, showUserNotice, showClearMsg, showClearChat, usePubSub, this, this)
            }
            if (usePubSub && !channelId.isNullOrBlank()) {
                pubSub = TwitchApiHelper.startPubSub(channelId, account.id, account.gqlToken, collectPoints, notifyPoints, showRaids, okHttpClient, viewModelScope, this, this)
            }
        }

        override fun pause() {
            chat?.disconnect()
            loggedInChat?.disconnect()
            pubSub?.disconnect()
        }

        override fun stop() {
            pause()
        }

        override fun onMessage(message: ChatMessage) {
            super.onMessage(message)
            if (message.userName != null && !chatters.containsKey(message.userName)) {
                val chatter = Chatter(message.userName)
                chatters[message.userName] = chatter
                _newChatter.postValue(chatter)
            }
        }

        override fun onCommand(list: Command) {
            command.postValue(list)
        }

        override fun onRoomState(list: RoomState) {
            roomState.postValue(list)
        }

        override fun onUserState(emoteSets: List<String>?) {
            if (savedEmoteSets != emoteSets) {
                viewModelScope.launch {
                    val emotes = mutableListOf<TwitchEmote>()
                    try {
                        if (!helixClientId.isNullOrBlank() && !account.helixToken.isNullOrBlank()) {
                            emoteSets?.asReversed()?.chunked(25)?.forEach { list ->
                                repository.loadEmotesFromSet(helixClientId, account.helixToken, list, animateGifs).let { emotes.addAll(it) }
                            }
                        } else if (!gqlClientId.isNullOrBlank() && !account.gqlToken.isNullOrBlank()) {
                            repository.loadUserEmotes(gqlClientId, account.gqlToken, channelId).let { emotes.addAll(it) }
                        }
                    } catch (e: Exception) {
                    }
                    if (emotes.isNotEmpty()) {
                        savedEmoteSets = emoteSets
                        savedUserEmotes = emotes
                        emoteSetsAdded = true
                        val items = emotes.filter { it.ownerId == channelId }
                        for (item in items.asReversed()) {
                            emotes.add(0, item)
                        }
                        addEmotes(emotes)
                        userEmotes.value = emotes
                    }
                }
            } else {
                if (!emoteSetsAdded) {
                    val emotes = mutableListOf<TwitchEmote>()
                    savedUserEmotes?.let { emotes.addAll(it) }
                    if (emotes.isNotEmpty()) {
                        emoteSetsAdded = true
                        val items = emotes.filter { it.ownerId == channelId }
                        for (item in items.asReversed()) {
                            emotes.add(0, item)
                        }
                        addEmotes(emotes)
                        viewModelScope.launch {
                            try {
                                userEmotes.value = emotes
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
            }
        }

        override fun onPlaybackMessage(live: Boolean?, viewers: Int?) {
            live?.let {
                if (it) {
                    command.postValue(Command(duration = channelLogin, type = "stream_live"))
                } else {
                    command.postValue(Command(duration = channelLogin, type = "stream_offline"))
                }
            }
            viewerCount.postValue(viewers)
        }

        override fun onRewardMessage(message: ChatMessage) {
            reward.postValue(message)
        }

        override fun onPointsEarned(message: PointsEarned) {
            pointsEarned.postValue(message)
        }

        override fun onClaimAvailable() {
            if (!gqlClientId2.isNullOrBlank() && !account.gqlToken2.isNullOrBlank()) {
                viewModelScope.launch {
                    repository.loadClaimPoints(gqlClientId2, account.gqlToken2, channelId, channelLogin)
                }
            }
        }

        override fun onMinuteWatched() {
            if (!streamId.isNullOrBlank()) {
                viewModelScope.launch {
                    repository.loadMinuteWatched(account.id, streamId, channelId, channelLogin)
                }
            }
        }

        override fun onRaidUpdate(message: Raid) {
            raidNewId = message.raidId != usedRaidId
            raid.postValue(message)
            if (raidNewId) {
                usedRaidId = message.raidId
                if (collectPoints && !gqlClientId2.isNullOrBlank() && !account.gqlToken2.isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.loadJoinRaid(gqlClientId2, account.gqlToken2, message.raidId)
                    }
                }
            }
        }

        override fun onRaidClicked() {
            raidAutoSwitch = false
            raidClicked.postValue(true)
        }

        override fun onRaidClose() {
            raidAutoSwitch = false
            raidClosed = true
        }

        fun addEmotes(list: List<Emote>) {
            allEmotesMap.putAll(list.associateBy { it.name })
        }

        fun isActive(): Boolean? {
            return chat?.isActive
        }

        fun disconnect() {
            if (chat?.isActive == true) {
                chat?.disconnect()
                loggedInChat?.disconnect()
                pubSub?.disconnect()
                usedRaidId = null
                command.postValue(Command(type = "disconnect_command"))
            }
        }

        private fun sendMessage(message: CharSequence) {
            loggedInChat?.send(message)
            val usedEmotes = hashSetOf<RecentEmote>()
            val currentTime = System.currentTimeMillis()
            message.split(' ').forEach { word ->
                allEmotesMap[word]?.let { usedEmotes.add(RecentEmote(word, it.url1x, it.url2x, it.url3x, it.url4x, currentTime)) }
            }
            if (usedEmotes.isNotEmpty()) {
                playerRepository.insertRecentEmotes(usedEmotes)
            }
        }

        private fun sendCommand(message: CharSequence) {
            val command = message.toString().substringBefore(" ")
            when {
                command.startsWith("/announce", true) -> {
                    val splits = message.split(" ", limit = 2)
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.sendAnnouncement(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                message = splits[1],
                                color = splits[0].substringAfter("/announce", "").ifBlank { null }
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/ban", true) -> {
                    val splits = message.split(" ", limit = 3)
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.banUser(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1],
                                reason = if (splits.size >= 3) splits[2] else null
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/unban", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.unbanUser(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/clear", true) -> {
                    viewModelScope.launch {
                        repository.deleteMessages(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/color", true) -> {
                    val splits = message.split(" ")
                    viewModelScope.launch {
                        if (splits.size >= 2) {
                            repository.updateChatColor(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                color = splits[1]
                            )
                        } else {
                            repository.getChatColor(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id
                            )
                        }?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/commercial", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.startCommercial(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                channelId = channelId,
                                length = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/delete", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.deleteMessages(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                channelId = channelId,
                                userId = account.id,
                                messageId = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/disconnect", true) -> disconnect()
                command.equals("/emoteonly", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            emote = true
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/emoteonlyoff", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            emote = false
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/followers", true) -> {
                    val splits = message.split(" ")
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            followers = true,
                            followersDuration = if (splits.size >= 2) splits[1] else null
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/followersoff", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            followers = false
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/marker", true) -> {
                    val splits = message.split(" ", limit = 2)
                    viewModelScope.launch {
                        repository.createStreamMarker(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            gqlClientId = gqlClientId2,
                            gqlToken = account.gqlToken2,
                            channelLogin = channelLogin,
                            description = if (splits.size >= 2) splits[1] else null
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/mod", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.addModerator(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/unmod", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.removeModerator(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/mods", true) -> {
                    viewModelScope.launch {
                        repository.getModerators(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            gqlClientId = gqlClientId,
                            channelLogin = channelLogin,
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/raid", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.startRaid(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/unraid", true) -> {
                    viewModelScope.launch {
                        repository.cancelRaid(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            gqlClientId = gqlClientId2,
                            gqlToken = account.gqlToken2,
                            channelId = channelId
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/slow", true) -> {
                    val splits = message.split(" ")
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            slow = true,
                            slowDuration = if (splits.size >= 2) splits[1].toIntOrNull() else null
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/slowoff", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            slow = false
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/subscribers", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            subs = true,
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/subscribersoff", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            subs = false,
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/timeout", true) -> {
                    val splits = message.split(" ", limit = 4)
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.banUser(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1],
                                duration = if (splits.size >= 3) splits[2] else null ?: if (!account.gqlToken2.isNullOrBlank()) "10m" else "600",
                                reason = if (splits.size >= 4) splits[3] else null,
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/untimeout", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.unbanUser(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/uniquechat", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            unique = true,
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/uniquechatoff", true) -> {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            userId = account.id,
                            unique = false,
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/vip", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.addVip(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/unvip", true) -> {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.removeVip(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                gqlClientId = gqlClientId2,
                                gqlToken = account.gqlToken2,
                                channelId = channelId,
                                targetLogin = splits[1]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                command.equals("/vips", true) -> {
                    viewModelScope.launch {
                        repository.getVips(
                            helixClientId = helixClientId,
                            helixToken = account.helixToken,
                            channelId = channelId,
                            gqlClientId = gqlClientId,
                            channelLogin = channelLogin,
                        )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                    }
                }
                command.equals("/w", true) -> {
                    val splits = message.split(" ", limit = 3)
                    if (splits.size >= 3) {
                        viewModelScope.launch {
                            repository.sendWhisper(
                                helixClientId = helixClientId,
                                helixToken = account.helixToken,
                                userId = account.id,
                                targetLogin = splits[1],
                                message = splits[2]
                            )?.let { onMessage(LiveChatMessage(message = it, color = "#999999", isAction = true)) }
                        }
                    }
                }
                else -> sendMessage(message)
            }
        }
    }

    private inner class VideoChatController(
            private val clientId: String?,
            private val videoId: String,
            private val startTime: Double,
            private val getCurrentPosition: () -> Double) : ChatController() {

        private var chatReplayManager: ChatReplayManager? = null

        override fun send(message: CharSequence) {

        }

        override fun start() {
            stop()
            chatReplayManager = ChatReplayManager(clientId, repository, videoId, startTime, getCurrentPosition, this, { _chatMessages.postValue(ArrayList()) }, viewModelScope)
        }

        override fun pause() {
            chatReplayManager?.stop()
        }

        override fun stop() {
            chatReplayManager?.stop()
        }
    }

    abstract inner class ChatController : OnChatMessageReceivedListener {
        abstract fun send(message: CharSequence)
        abstract fun start()
        abstract fun pause()
        abstract fun stop()

        override fun onMessage(message: ChatMessage) {
            _chatMessages.value!!.add(message)
            _newMessage.postValue(message)
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        private var savedEmoteSets: List<String>? = null
        private var savedUserEmotes: List<TwitchEmote>? = null
        private var savedGlobalBadges: List<TwitchBadge>? = null
        private var globalStvEmotes: List<StvEmote>? = null
        private var globalBttvEmotes: List<BttvEmote>? = null
        private var globalFfzEmotes: List<FfzEmote>? = null
    }
}