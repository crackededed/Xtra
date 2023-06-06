package com.github.andreyasadchy.xtra.ui.follow

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.NotLoggedIn
import com.github.andreyasadchy.xtra.ui.common.MediaFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.follow.channels.FollowedChannelsFragment
import com.github.andreyasadchy.xtra.ui.follow.games.FollowedGamesFragment
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.streams.followed.FollowedStreamsFragment
import com.github.andreyasadchy.xtra.ui.videos.followed.FollowedVideosFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.nullIfEmpty
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible

class FollowMediaFragment : MediaFragment(), Scrollable {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            val account = Account.get(activity)
            search.setOnClickListener { findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment()) }
            menu.setOnClickListener { it ->
                PopupMenu(activity, it).apply {
                    inflate(R.menu.top_menu)
                    menu.findItem(R.id.login).title = if (account !is NotLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
                    setOnMenuItemClickListener {
                        when(it.itemId) {
                            R.id.settings -> { activity.startActivityFromFragment(this@FollowMediaFragment, Intent(activity, SettingsActivity::class.java), 3) }
                            R.id.login -> {
                                if (account is NotLoggedIn) {
                                    activity.startActivityForResult(Intent(activity, LoginActivity::class.java), 1)
                                } else {
                                    AlertDialog.Builder(activity).apply {
                                        setTitle(getString(R.string.logout_title))
                                        account.login?.nullIfEmpty()?.let { user -> setMessage(getString(R.string.logout_msg, user)) }
                                        setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.dismiss() }
                                        setPositiveButton(getString(R.string.yes)) { _, _ -> activity.startActivityForResult(Intent(activity, LoginActivity::class.java), 2) }
                                    }.show()
                                }
                            }
                        }
                        true
                    }
                    show()
                }
            }
            spinner.visible()
            spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                if (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()) {
                    resources.getStringArray(R.array.spinnerFollowedEntries)
                } else {
                    resources.getStringArray(R.array.spinnerFollowedEntriesNotLoggedIn)
                }
            )
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentFragment = if (position != previousItem) {
                        val newFragment = onSpinnerItemSelected(position)
                        childFragmentManager.beginTransaction().replace(R.id.fragmentContainer, newFragment).commit()
                        previousItem = position
                        newFragment
                    } else {
                        childFragmentManager.findFragmentById(R.id.fragmentContainer)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    override fun onSpinnerItemSelected(position: Int): Fragment {
        with(binding) {
            val loggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()
            if (firstLaunch) {
                val defaultItem = requireContext().prefs().getString(C.UI_FOLLOW_DEFAULT_PAGE, "0")?.toIntOrNull() ?: 0
                spinner.setSelection(
                    if (loggedIn) {
                        defaultItem
                    } else {
                        when (defaultItem) {
                            2 -> 1
                            3 -> 2
                            else -> 0
                        }
                    }
                )
                firstLaunch = false
            }
            return if (loggedIn) {
                when (position) {
                    0 -> FollowedStreamsFragment()
                    1 -> FollowedVideosFragment()
                    2 -> FollowedChannelsFragment()
                    else -> FollowedGamesFragment()
                }
            } else {
                when (position) {
                    0 -> FollowedStreamsFragment()
                    1 -> FollowedChannelsFragment()
                    else -> FollowedGamesFragment()
                }
            }
        }
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}