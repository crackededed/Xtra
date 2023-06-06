package com.github.andreyasadchy.xtra.ui.follow

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaPagerToolbarBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.NotLoggedIn
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.*
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FollowPagerFragment : Fragment(), Scrollable {

    private var _binding: FragmentMediaPagerToolbarBinding? = null
    private val binding get() = _binding!!
    private var firstLaunch = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaPagerToolbarBinding.inflate(inflater, container, false)
        return binding.root
    }

    val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.pagerLayout.viewPager.currentItem}")

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
                            R.id.settings -> { activity.startActivityFromFragment(this@FollowPagerFragment, Intent(activity, SettingsActivity::class.java), 3) }
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
        }
        with(binding.pagerLayout) {
            val loggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()
            val adapter = FollowPagerAdapter(this@FollowPagerFragment, loggedIn)
            viewPager.adapter = adapter
            if (firstLaunch) {
                val defaultItem = requireContext().prefs().getString(C.UI_FOLLOW_DEFAULT_PAGE, "0")?.toInt()
                viewPager.setCurrentItem(if (loggedIn) {
                    when (defaultItem) {
                        1 -> 2
                        2 -> 3
                        3 -> 0
                        else -> 1
                    }
                } else {
                    when (defaultItem) {
                        2 -> 2
                        3 -> 0
                        else -> 1
                    }
                }, false)
                firstLaunch = false
            }
            viewPager.offscreenPageLimit = adapter.itemCount
            viewPager.reduceDragSensitivity()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = if (loggedIn) {
                    when (position) {
                        0 -> getString(R.string.games)
                        1 -> getString(R.string.live)
                        2 -> getString(R.string.videos)
                        else -> getString(R.string.channels)
                    }
                } else {
                    when (position) {
                        0 -> getString(R.string.games)
                        1 -> getString(R.string.live)
                        else -> getString(R.string.channels)
                    }
                }
            }.attach()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode == Activity.RESULT_OK) {
            requireActivity().recreate()
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
