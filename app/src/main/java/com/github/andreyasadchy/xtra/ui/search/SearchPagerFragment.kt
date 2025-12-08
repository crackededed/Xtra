package com.github.andreyasadchy.xtra.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogUserResultBinding
import com.github.andreyasadchy.xtra.databinding.FragmentSearchBinding
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchPagerFragment : BaseNetworkFragment(), FragmentHost {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchPagerViewModel by viewModels()
    private var firstLaunch = true

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
            val tabList = requireContext().prefs().getString(C.UI_SEARCH_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_SEARCH_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[2] != "0"
                if (enabled) {
                    key
                } else {
                    null
                }
            }
            if (tabs.size <= 1) {
                tabLayout.gone()
            } else {
                if (tabs.size >= 5) {
                    tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                    tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                }
            }
            val adapter = SearchPagerAdapter(this@SearchPagerFragment, tabs)
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    (currentFragment as? Searchable)?.search(binding.searchView.query.toString())
                    viewPager.doOnLayout {
                        childFragmentManager.findFragmentByTag("f${position}")?.let { fragment ->
                            if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                                fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
                                    appBar.setLiftOnScrollTargetView(it)
                                    it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                            super.onScrolled(recyclerView, dx, dy)
                                            appBar.isLifted = recyclerView.canScrollVertically(-1)
                                        }
                                    })
                                    it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                        appBar.isLifted = it.canScrollVertically(-1)
                                    }
                                }
                            } else {
                                appBar.setLiftable(false)
                                appBar.background = null
                            }
                            (fragment as? Sortable)?.setupSortBar(sortBar) ?: sortBar.root.gone()
                        }
                    }
                }
            })
            if (firstLaunch) {
                val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "2"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("2").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.offscreenPageLimit = adapter.itemCount
            viewPager.reduceDragSensitivity()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.videos)
                    "1" -> getString(R.string.streams)
                    "2" -> getString(R.string.channels)
                    "3" -> getString(R.string.games)
                    else -> getString(R.string.channels)
                }
            }.attach()
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.searchUser -> {
                        val binding = DialogUserResultBinding.inflate(layoutInflater)
                        requireContext().getAlertDialogBuilder().apply {
                            setView(binding.root)
                            setNegativeButton(getString(android.R.string.cancel), null)
                            setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                                val result = binding.editText.editText?.text?.toString()
                                val checkedId = if (binding.radioButton.isChecked) 0 else 1
                                if (!result.isNullOrBlank()) {
                                    userResult = Pair(checkedId, result)
                                    viewModel.loadUserResult(
                                        checkedId = checkedId,
                                        result = result,
                                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                        enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                    )
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                                            viewModel.userResult.collectLatest {
                                                if (it != null) {
                                                    if (!it.first.isNullOrBlank()) {
                                                        requireContext().getAlertDialogBuilder().apply {
                                                            setTitle(it.first)
                                                            setMessage(it.second)
                                                            setNegativeButton(getString(android.R.string.cancel), null)
                                                            setPositiveButton(getString(R.string.view_profile)) { _, _ -> viewUserResult() }
                                                        }.show()
                                                    } else {
                                                        viewUserResult()
                                                    }
                                                    viewModel.userResult.value = null
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            setNeutralButton(getString(R.string.view_profile)) { _, _ ->
                                val result = binding.editText.editText?.text?.toString()
                                val checkedId = if (binding.radioButton.isChecked) 0 else 1
                                if (!result.isNullOrBlank()) {
                                    userResult = Pair(checkedId, result)
                                    viewUserResult()
                                }
                            }
                        }.show()
                        true
                    }
                    else -> false
                }
            }
            searchView.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, searchView).show(WindowInsetsCompat.Type.ime())
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }
    }

    override fun initialize() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var job: Job? = null

            override fun onQueryTextSubmit(query: String): Boolean {
                (currentFragment as? Searchable)?.search(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                job?.cancel()
                if (newText.isNotEmpty()) {
                    job = lifecycleScope.launch {
                        delay(750)
                        withResumed {
                            (currentFragment as? Searchable)?.search(newText)
                        }
                    }
                } else {
                    (currentFragment as? Searchable)?.search(newText) //might be null on rotation, so as?
                }
                return false
            }
        })
    }

    fun setQuery(query: String?) {
        binding.searchView.setQuery(query, true)
    }

    private var userResult: Pair<Int?, String?>? = null

    private fun viewUserResult() {
        userResult?.let {
            when (it.first) {
                0 -> findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelId = it.second
                    )
                )
                1 -> findNavController().navigate(
                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                        channelLogin = it.second
                    )
                )
                else -> {}
            }
        }
    }

    override fun onNetworkRestored() {
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
