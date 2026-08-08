package com.github.andreyasadchy.xtra.ui.multiview

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentCombinedChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.google.android.material.color.MaterialColors

class CombinedChatFragment : Fragment(R.layout.fragment_combined_chat) {

    private var _binding: FragmentCombinedChatBinding? = null
    private val binding get() = _binding!!
    private val messages = mutableListOf<CombinedMessage>()
    private val sourceTags = mutableListOf<String>()
    private var sequence = 0L
    private lateinit var adapter: CombinedChatAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCombinedChatBinding.bind(view)
        adapter = CombinedChatAdapter(messages)
        binding.combinedChatRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.combinedChatRecyclerView.adapter = adapter

        requireArguments()
            .parcelableArrayList<Stream>(ARG_STREAMS)
            .orEmpty()
            .forEach(::attachSource)
        updateEmptyState()
    }

    private fun attachSource(stream: Stream) {
        val login = stream.channelLogin?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        val tag = "$SOURCE_TAG_PREFIX$login"
        sourceTags += tag
        val source = (childFragmentManager.findFragmentByTag(tag) as? ChatFragment)
            ?: ChatFragment.newInstance(stream.channelId, login, displayName(stream), stream.id).also {
                childFragmentManager.beginTransaction()
                    .add(R.id.combinedChatSourceHost, it, tag)
                    .hide(it)
                    .commit()
            }
        source.chatMessageListener = { message -> addMessage(login, displayName(stream), message) }
        source.chatHistoryListener = { history -> addHistory(login, displayName(stream), history) }
    }

    private fun addMessage(login: String, channelName: String, message: ChatMessage) {
        if (message.id != null && messages.any { it.login == login && it.message.id == message.id }) return
        val wasAtBottom = isAtBottom()
        messages += CombinedMessage(login, channelName, message, sequence++)
        val removeCount = (messages.size - MAX_MESSAGES).coerceAtLeast(0)
        if (removeCount > 0) repeat(removeCount) { messages.removeAt(0) }
        adapter.notifyDataSetChanged()
        updateEmptyState()
        if (wasAtBottom || messages.size == 1) scrollToBottom()
    }

    private fun addHistory(login: String, channelName: String, history: List<ChatMessage>) {
        val additions = history.filter { message ->
            message.id == null || messages.none { it.login == login && it.message.id == message.id }
        }
        if (additions.isEmpty()) return
        additions.forEach { message ->
            messages += CombinedMessage(login, channelName, message, sequence++)
        }
        messages.sortWith(compareBy<CombinedMessage> { it.message.timestamp ?: Long.MAX_VALUE }.thenBy { it.sequence })
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        adapter.notifyDataSetChanged()
        updateEmptyState()
        scrollToBottom()
    }

    private fun updateEmptyState() {
        binding.combinedChatEmpty.isVisible = messages.isEmpty()
    }

    private fun isAtBottom(): Boolean {
        val layoutManager = binding.combinedChatRecyclerView.layoutManager as? LinearLayoutManager ?: return true
        return layoutManager.findLastCompletelyVisibleItemPosition() >= messages.lastIndex - 1
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            binding.combinedChatRecyclerView.scrollToPosition(messages.lastIndex)
        }
    }

    private fun displayName(stream: Stream): String {
        return stream.channelName?.takeIf { it.isNotBlank() } ?: stream.channelLogin.orEmpty()
    }

    override fun onDestroyView() {
        sourceTags.forEach { tag ->
            (childFragmentManager.findFragmentByTag(tag) as? ChatFragment)?.apply {
                chatMessageListener = null
                chatHistoryListener = null
            }
        }
        sourceTags.clear()
        _binding = null
        super.onDestroyView()
    }

    private data class CombinedMessage(
        val login: String,
        val channelName: String,
        val message: ChatMessage,
        val sequence: Long,
    )

    private class CombinedChatAdapter(
        private val messages: List<CombinedMessage>,
    ) : RecyclerView.Adapter<CombinedChatAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.combined_chat_list_item, parent, false) as TextView,
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = messages[position]
            val channelStart = 0
            val channelLabel = "[${item.channelName}] "
            val builder = SpannableStringBuilder(channelLabel)
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                channelStart,
                channelLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                ForegroundColorSpan(MaterialColors.getColor(holder.textView, androidx.appcompat.R.attr.colorControlNormal)),
                channelStart,
                channelLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )

            val author = item.message.userName ?: item.message.userLogin
            if (!author.isNullOrBlank()) {
                val authorStart = builder.length
                builder.append(author).append(": ")
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    authorStart,
                    authorStart + author.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            builder.append(
                item.message.message
                    ?: item.message.systemMsg
                    ?: item.message.replyParent?.message
                    ?: item.message.reward?.title
                    ?: "",
            )
            holder.textView.text = builder
        }

        override fun getItemCount(): Int = messages.size

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }

    companion object {
        private const val ARG_STREAMS = "combined_chat_streams"
        private const val SOURCE_TAG_PREFIX = "combined_chat_source_"
        private const val MAX_MESSAGES = 500

        fun newInstance(streams: List<Stream>): CombinedChatFragment {
            return CombinedChatFragment().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_STREAMS, ArrayList(streams))
                }
            }
        }
    }
}

private inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}
