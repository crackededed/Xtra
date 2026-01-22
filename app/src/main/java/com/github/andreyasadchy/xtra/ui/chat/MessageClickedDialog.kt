package com.github.andreyasadchy.xtra.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChatMessageClickBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.mlkit.nl.translate.TranslateLanguage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class MessageClickedDialog : BottomSheetDialogFragment(), IntegrityDialog.CallbackListener {

    interface OnButtonClickListener {
        fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter
        fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?)
        fun onCopyMessageClicked(message: String)
        fun onViewProfileClicked(id: String?, login: String?, name: String?, channelLogo: String?)
        fun onTranslateMessageClicked(chatMessage: ChatMessage, languageTag: String?)
    }

    companion object {
        private const val KEY_MESSAGING = "messaging"
        private const val KEY_CHANNEL_ID = "channelId"
        private val savedUsers = mutableListOf<Pair<User, String?>>()
        private var selectedLanguage: String? = null

        fun newInstance(messagingEnabled: Boolean, channelId: String?): MessageClickedDialog {
            return MessageClickedDialog().apply {
                arguments = bundleOf(
                    KEY_MESSAGING to messagingEnabled,
                    KEY_CHANNEL_ID to channelId
                )
            }
        }
    }

    private var _binding: DialogChatMessageClickBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MessageClickedViewModel by viewModels()

    private lateinit var listener: OnButtonClickListener
    var adapter: MessageClickedChatAdapter? = null
    private var isChatTouched = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnButtonClickListener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogChatMessageClickBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collectLatest {
                    if (it != null &&
                        it != "done" &&
                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                        requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                    ) {
                        IntegrityDialog.show(childFragmentManager, it)
                        viewModel.integrity.value = "done"
                    }
                }
            }
        }
        with(binding) {
            adapter = listener.onCreateMessageClickedChatAdapter()
            recyclerView.let {
                it.adapter = adapter
                it.itemAnimator = null
                it.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                it.setOnTouchListener(object : View.OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> behavior.isDraggable = false
                            MotionEvent.ACTION_UP -> behavior.isDraggable = true
                        }
                        return false
                    }
                })
                it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        isChatTouched = newState == RecyclerView.SCROLL_STATE_DRAGGING
                    }
                })
            }
            adapter?.let { adapter ->
                adapter.messageClickListener = { selectedMessage, previousSelectedMessage ->
                    updateButtons(selectedMessage)
                    previousSelectedMessage?.let {
                        adapter.messages?.indexOf(it)?.takeIf { it != -1 }?.let {
                            (recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                adapter.updateBackground(previousSelectedMessage, it)
                            } ?: adapter.notifyItemChanged(it)
                        }
                    }
                }
                adapter.selectedMessage?.let { selectedMessage ->
                    updateButtons(selectedMessage)
                    adapter.messages?.indexOf(selectedMessage)?.takeIf { it != -1 }?.let { binding.recyclerView.scrollToPosition(it) }
                    if (selectedMessage.userId != null || selectedMessage.userLogin != null) {
                        val targetId = requireArguments().getString(KEY_CHANNEL_ID)
                        val item = selectedMessage.userId?.let { savedUsers.find { it.first.channelId == selectedMessage.userId && it.second == targetId } }
                        if (item != null) {
                            updateUserLayout(item.first)
                            item.first.channelName?.let { channelName ->
                                if (requireArguments().getBoolean(KEY_MESSAGING) &&
                                    !selectedMessage.id.isNullOrBlank() &&
                                    selectedMessage.userName.isNullOrBlank() &&
                                    channelName.isNotBlank()
                                ) {
                                    reply.visibility = View.VISIBLE
                                    reply.setOnClickListener {
                                        listener.onReplyClicked(
                                            selectedMessage.id,
                                            selectedMessage.userLogin,
                                            channelName,
                                            selectedMessage.message
                                        )
                                        dismiss()
                                    }
                                }
                            }
                        } else {
                            viewModel.loadUser(
                                channelId = selectedMessage.userId,
                                channelLogin = selectedMessage.userLogin,
                                targetId = if (selectedMessage.userId != targetId) targetId else null,
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                                enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            viewLifecycleOwner.lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.user.collectLatest { pair ->
                                        if (pair != null) {
                                            val user = pair.first
                                            val error = pair.second
                                            if (user != null) {
                                                savedUsers.add(Pair(user, targetId))
                                                updateUserLayout(user)
                                                adapter.selectedMessage?.let { selectedMessage ->
                                                    if (requireArguments().getBoolean(KEY_MESSAGING) &&
                                                        !selectedMessage.id.isNullOrBlank() &&
                                                        selectedMessage.userName.isNullOrBlank() &&
                                                        !user.channelName.isNullOrBlank()
                                                    ) {
                                                        reply.visibility = View.VISIBLE
                                                        reply.setOnClickListener {
                                                            listener.onReplyClicked(
                                                                selectedMessage.id,
                                                                selectedMessage.userLogin,
                                                                user.channelName,
                                                                selectedMessage.message
                                                            )
                                                            dismiss()
                                                        }
                                                    }
                                                }
                                                viewModel.user.value = Pair(null, false)
                                            } else {
                                                if (error == true) {
                                                    viewProfile.visibility = View.VISIBLE
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        viewProfile.setOnClickListener {
                            listener.onViewProfileClicked(selectedMessage.userId, selectedMessage.userLogin, selectedMessage.userName, null)
                            dismiss()
                        }
                    }
                }
            }
            if (requireContext().prefs().getBoolean(C.DEBUG_CHAT_FULLMSG, false)) {
                copyFullMsg.visibility = View.VISIBLE
            }
        }
    }

    private fun updateButtons(chatMessage: ChatMessage) {
        with(binding) {
            if (requireArguments().getBoolean(KEY_MESSAGING) && (!chatMessage.userId.isNullOrBlank() || !chatMessage.userLogin.isNullOrBlank())) {
                if (!chatMessage.id.isNullOrBlank()) {
                    reply.visibility = View.VISIBLE
                    reply.setOnClickListener {
                        listener.onReplyClicked(chatMessage.id, chatMessage.userLogin, chatMessage.userName, chatMessage.message)
                        dismiss()
                    }
                } else {
                    reply.visibility = View.GONE
                }
                if (!chatMessage.message.isNullOrBlank()) {
                    copyMessage.visibility = View.VISIBLE
                    copyMessage.setOnClickListener {
                        listener.onCopyMessageClicked(chatMessage.message)
                        dismiss()
                    }
                } else {
                    copyMessage.visibility = View.GONE
                }
            }
            val clipboard = getSystemService(requireContext(), ClipboardManager::class.java)
            copyClip.setOnClickListener {
                clipboard?.setPrimaryClip(ClipData.newPlainText("label", chatMessage.message))
                dismiss()
            }
            copyFullMsg.setOnClickListener {
                clipboard?.setPrimaryClip(ClipData.newPlainText("label", chatMessage.fullMsg))
                dismiss()
            }
            if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && (chatMessage.message != null || chatMessage.systemMsg != null) && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                translateMessage.visibility = View.VISIBLE
                translateMessage.setOnClickListener {
                    listener.onTranslateMessageClicked(chatMessage, null)
                }
                translateMessageSelectLanguage.visibility = View.VISIBLE
                translateMessageSelectLanguage.setOnClickListener {
                    val languages = TranslateLanguage.getAllLanguages()
                    val names = languages.map { Locale.forLanguageTag(it).displayName }.toTypedArray()
                    requireContext().getAlertDialogBuilder()
                        .setSingleChoiceItems(names, languages.indexOf(selectedLanguage)) { _, which ->
                            languages.getOrNull(which)?.let { language ->
                                selectedLanguage = language
                            }
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            selectedLanguage?.let {
                                listener.onTranslateMessageClicked(chatMessage, it)
                            }
                        }
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show()
                }
            } else {
                translateMessage.visibility = View.GONE
                translateMessageSelectLanguage.visibility = View.GONE
            }
        }
    }

    private fun updateUserLayout(user: User) {
        with(binding) {
            if (user.bannerImageURL != null) {
                userLayout.visibility = View.VISIBLE
                bannerImage.visibility = View.VISIBLE
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext()).apply {
                        data(user.bannerImageURL)
                        crossfade(true)
                        target(bannerImage)
                    }.build()
                )
            } else {
                bannerImage.visibility = View.GONE
            }
            if (user.channelLogo != null) {
                userLayout.visibility = View.VISIBLE
                userImage.visibility = View.VISIBLE
                requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(requireContext()).apply {
                        data(user.channelLogo)
                        if (requireContext().prefs().getBoolean(C.UI_ROUNDUSERIMAGE, true)) {
                            transformations(CircleCropTransformation())
                        }
                        crossfade(true)
                        target(userImage)
                    }.build()
                )
                userImage.setOnClickListener {
                    listener.onViewProfileClicked(user.channelId, user.channelLogin, user.channelName, user.channelLogo)
                    dismiss()
                }
            } else {
                userImage.visibility = View.GONE
            }
            if (user.channelName != null) {
                userLayout.visibility = View.VISIBLE
                userName.visibility = View.VISIBLE
                userName.text = if (user.channelLogin != null && !user.channelLogin.equals(user.channelName, true)) {
                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "${user.channelName}(${user.channelLogin})"
                        "1" -> user.channelName
                        else -> user.channelLogin
                    }
                } else {
                    user.channelName
                }
                userName.setOnClickListener {
                    listener.onViewProfileClicked(user.channelId, user.channelLogin, user.channelName, user.channelLogo)
                    dismiss()
                }
                if (user.bannerImageURL != null) {
                    userName.setTextColor(Color.WHITE)
                    userName.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userName.visibility = View.GONE
            }
            if (user.createdAt != null) {
                userLayout.visibility = View.VISIBLE
                userCreated.visibility = View.VISIBLE
                userCreated.text = getString(R.string.created_at, TwitchApiHelper.formatTimeString(requireContext(), user.createdAt))
                if (user.bannerImageURL != null) {
                    userCreated.setTextColor(Color.LTGRAY)
                    userCreated.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userCreated.visibility = View.GONE
            }
            if (user.followedAt != null) {
                userLayout.visibility = View.VISIBLE
                userFollowed.visibility = View.VISIBLE
                userFollowed.text = getString(R.string.followed_at, TwitchApiHelper.formatTimeString(requireContext(), user.followedAt!!))
                if (user.bannerImageURL != null) {
                    userFollowed.setTextColor(Color.LTGRAY)
                    userFollowed.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                }
            } else {
                userFollowed.visibility = View.GONE
            }
            if (!userImage.isVisible && !userName.isVisible) {
                viewProfile.visibility = View.VISIBLE
            }
        }
    }

    fun updateUserMessages(userId: String) {
        adapter?.let { adapter ->
            adapter.messages?.toList()?.let { messages ->
                messages.filter { it.userId != null && it.userId == userId }.forEach { message ->
                    messages.indexOf(message).takeIf { it != -1 }?.let {
                        adapter.notifyItemChanged(it)
                    }
                }
            }
        }
    }

    fun updateTranslation(chatMessage: ChatMessage, previousTranslation: String?) {
        adapter?.let { adapter ->
            adapter.messages?.toList()?.indexOf(chatMessage)?.takeIf { it != -1 }?.let {
                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                } ?: adapter.notifyItemChanged(it)
            }
        }
    }

    fun scrollToLastPosition() {
        if (!isChatTouched && !shouldShowButton()) {
            adapter?.messages?.let { binding.recyclerView.scrollToPosition(it.lastIndex) }
        }
    }

    private fun shouldShowButton(): Boolean {
        with(binding) {
            val offset = recyclerView.computeVerticalScrollOffset()
            if (offset < 0) {
                return false
            }
            val extent = recyclerView.computeVerticalScrollExtent()
            val range = recyclerView.computeVerticalScrollRange()
            val percentage = (100f * offset / (range - extent).toFloat())
            return percentage < 100f
        }
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback == "refresh") {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val userId = adapter?.selectedMessage?.userId
                    val userLogin = adapter?.selectedMessage?.userLogin
                    if (userId != null || userLogin != null) {
                        val targetId = requireArguments().getString(KEY_CHANNEL_ID)
                        viewModel.loadUser(
                            channelId = userId,
                            channelLogin = userLogin,
                            targetId = if (userId != targetId) targetId else null,
                            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                            helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}