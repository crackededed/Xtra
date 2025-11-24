package com.github.andreyasadchy.xtra.ui.channel.about

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentAboutBinding
import com.github.andreyasadchy.xtra.model.ui.RootAbout
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.common.ChannelPanelAdapter
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.team.TeamFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelAboutFragment : Fragment(), Scrollable {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelAboutViewModel by viewModels()
    private var isInitialized = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!isInitialized) {
                    initialize()
                }
            }
        }
        with (binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.rootAbout.collect { about ->
                        if (about != null) {
                            var hasContent = false

                            if (!about.description.isNullOrEmpty()) {
                                descriptionText.visible()
                                descriptionText.text = about.description
                                hasContent = true
                            }
                            if (!about.socialMedias.isNullOrEmpty()) {
                                socialMediaList.apply {
                                    visible()
                                    adapter = Adapter(context, about.socialMedias)
                                }
                                hasContent = true
                            }
                            if (about.primaryTeam != null) {
                                teamLayout.visible()
                                val spannableString = SpannableString(about.primaryTeam.displayName)
                                spannableString.setSpan(UnderlineSpan(), 0, about.primaryTeam.displayName.length, 0)
                                teamText.setOnClickListener {
                                    this@ChannelAboutFragment.findNavController().navigate(
                                        TeamFragmentDirections.actionGlobalTeamFragment(
                                            teamName = about.primaryTeam.name,
                                        )
                                    )
                                }
                                teamText.text = spannableString
                                hasContent = true
                            }
                            if (hasContent) {
                                aboutPanel.visible()
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.panelList.collectLatest { list ->
                        if (list != null && list.isNotEmpty()) {
                            panelList.visible()
                            panelList.adapter = ChannelPanelAdapter(this@ChannelAboutFragment, list)
                        }
                    }
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.panelList.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadRootAbout(
                args.channelLogin,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            )
            viewModel.loadPanelList(
                args.channelId,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext()),
                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            )
        }

        isInitialized = true
    }

    override fun scrollToTop() {
        binding.panelList.scrollToPosition(0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class Adapter internal constructor(context: Context?, data: List<RootAbout.SocialMedia>)  : RecyclerView.Adapter<Adapter.ViewHolder>() {
        private val mData: List<RootAbout.SocialMedia> = data
        private val mInflater: LayoutInflater = LayoutInflater.from(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Adapter.ViewHolder {
            val view = mInflater.inflate(R.layout.fragment_url_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = mData[position]
            with (holder) {
                val spannableString = SpannableString(item.title)
                spannableString.setSpan(object: ClickableSpan() {
                    override fun onClick(widget: View) {
                        val intent = Intent(Intent.ACTION_VIEW, item.url.toUri())
                        mInflater.context.startActivity(intent)
                    }
                }, 0, item.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                textView.text = spannableString
                textView.movementMethod = LinkMovementMethodCompat.getInstance()
            }
        }

        override fun getItemCount(): Int {
            return mData.size
        }

        inner class ViewHolder internal constructor(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView = itemView as TextView

        }
    }
}