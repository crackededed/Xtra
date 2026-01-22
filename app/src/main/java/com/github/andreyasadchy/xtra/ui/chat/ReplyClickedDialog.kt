package com.github.andreyasadchy.xtra.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.DialogChatMessageClickBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.mlkit.nl.translate.TranslateLanguage
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class ReplyClickedDialog : BottomSheetDialogFragment() {

    interface OnButtonClickListener {
        fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter
        fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?)
        fun onCopyMessageClicked(message: String)
        fun onTranslateMessageClicked(chatMessage: ChatMessage, languageTag: String?)
    }

    companion object {
        private const val KEY_MESSAGING = "messaging"
        private var selectedLanguage: String? = null

        fun newInstance(messagingEnabled: Boolean): ReplyClickedDialog {
            return ReplyClickedDialog().apply {
                arguments = bundleOf(
                    KEY_MESSAGING to messagingEnabled,
                )
            }
        }
    }

    private var _binding: DialogChatMessageClickBinding? = null
    private val binding get() = _binding!!

    private lateinit var listener: OnButtonClickListener
    var adapter: ReplyClickedChatAdapter? = null
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
        with(binding) {
            adapter = listener.onCreateReplyClickedChatAdapter()
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
                adapter.selectedMessage?.let {
                    updateButtons(it)
                    adapter.messages?.indexOf(it)?.takeIf { it != -1 }?.let { binding.recyclerView.scrollToPosition(it) }
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
            if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && (chatMessage.message != null || chatMessage.systemMsg != null) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}