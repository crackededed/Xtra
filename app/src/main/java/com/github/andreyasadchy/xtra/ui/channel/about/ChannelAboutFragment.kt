package com.github.andreyasadchy.xtra.ui.channel.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentAboutBinding
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.team.TeamFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelAboutFragment : BaseNetworkFragment(), IntegrityDialog.CallbackListener {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelAboutViewModel by viewModels()
    private var panelAdapter: ChannelPanelAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panelAdapter = ChannelPanelAdapter(this@ChannelAboutFragment)
        binding.recyclerView.adapter = panelAdapter
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.recyclerView.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.description.collectLatest {
                        if (!it.isNullOrBlank()) {
                            description.visibility = View.VISIBLE
                            description.text = it
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.socialMedias.collectLatest { result ->
                        if (result != null) {
                            socialMediaList.visibility = View.VISIBLE
                            socialMediaList.removeAllViews()
                            result.forEach {
                                val title = it.first
                                val url = it.second
                                if (!title.isNullOrBlank()) {
                                    socialMediaList.addView(
                                        TextView(requireContext()).apply {
                                            val spannableString = SpannableString(title)
                                            spannableString.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                            if (url != null) {
                                                spannableString.setSpan(object : ClickableSpan() {
                                                    override fun onClick(widget: View) {
                                                        try {
                                                            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                                                addCategory(Intent.CATEGORY_BROWSABLE)
                                                            }
                                                            startActivity(intent)
                                                        } catch (e: ActivityNotFoundException) {
                                                            Toast.makeText(requireContext(), R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }, 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                                movementMethod = LinkMovementMethod.getInstance()
                                            }
                                            text = spannableString
                                            layoutParams = LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT
                                            )
                                            context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                                                TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                                            }
                                            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, resources.displayMetrics).toInt()
                                            setPadding(0, 0, 0, padding)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.team.collectLatest { result ->
                        if (result != null) {
                            val name = result.first
                            val displayName = result.second
                            if (!displayName.isNullOrBlank()) {
                                team.visibility = View.VISIBLE
                                val string = getString(R.string.team, displayName)
                                val index = string.indexOf(displayName)
                                val spannableString = SpannableString(string)
                                spannableString.setSpan(StyleSpan(Typeface.BOLD), index, index + displayName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                if (name != null) {
                                    spannableString.setSpan(object : ClickableSpan() {
                                        override fun onClick(widget: View) {
                                            findNavController().navigate(
                                                TeamFragmentDirections.actionGlobalTeamFragment(
                                                    teamName = name,
                                                )
                                            )
                                        }
                                    }, index, index + displayName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    team.movementMethod = LinkMovementMethod.getInstance()
                                }
                                team.text = spannableString
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.originalName.collectLatest {
                        if (!it.isNullOrBlank()) {
                            originalName.visibility = View.VISIBLE
                            originalName.text = getString(R.string.old_username, it)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.panels.collectLatest { result ->
                        panelAdapter?.submitList(result)
                    }
                }
            }
        }
        viewModel.loadAbout(
            channelId = args.channelId,
            channelLogin = args.channelLogin,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    override fun onNetworkRestored() {
        viewModel.loadAbout(
            channelId = args.channelId,
            channelLogin = args.channelLogin,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
        )
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        (parentFragment as? IntegrityDialog.CallbackListener)?.onIntegrityDialogCallback("refresh")
        if (callback == "refresh") {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.loadAbout(
                        channelId = args.channelId,
                        channelLogin = args.channelLogin,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                        enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}