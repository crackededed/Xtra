package com.github.andreyasadchy.xtra.ui.saved

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaPagerBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SavedPagerFragment : Fragment(), Scrollable, FragmentHost {

    private var _binding: FragmentMediaPagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SavedPagerViewModel by viewModels()
    private var firstLaunch = true
    private var folderResultLauncher: ActivityResultLauncher<Intent>? = null
    private var fileResultLauncher: ActivityResultLauncher<Intent>? = null

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            folderResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let {
                        requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        viewModel.saveFolders(it.toString())
                    }
                }
            }
        } else {
            folderResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let {
                        val isShared = it.scheme == ContentResolver.SCHEME_CONTENT
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && isShared) {
                            val storage = DownloadUtils.getAvailableStorage(requireContext())
                            val uri = Uri.decode(it.path).substringAfter("/document/")
                            val storageName = uri.substringBefore(":")
                            val storagePath = if (storageName.equals("primary", true)) {
                                storage.firstOrNull()
                            } else {
                                if (storage.size >= 2) {
                                    storage.lastOrNull()
                                } else {
                                    storage.firstOrNull()
                                }
                            }?.path?.substringBefore("/Android/data") ?: "/storage/emulated/0"
                            val path = uri.substringAfter(":").substringBeforeLast("/")
                            val fullUri = "$storagePath/$path"
                            viewModel.saveFolders(fullUri)
                        } else {
                            it.path?.substringBeforeLast("/")?.let { uri -> viewModel.saveFolders(uri) }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            fileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val list = mutableListOf<String>()
                    result.data?.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            item.uri?.let {
                                requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                list.add(it.toString())
                            }
                        }
                    } ?: result.data?.data?.let {
                        requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        list.add(it.toString())
                    }
                    viewModel.saveVideos(list)
                }
            }
        } else {
            fileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val list = mutableListOf<String>()
                    result.data?.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            item.uri?.path?.let {
                                list.add(it)
                            }
                        }
                    } ?: result.data?.data?.path?.let {
                        list.add(it)
                    }
                    viewModel.saveVideos(list)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaPagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                        }
                        true
                    }
                    R.id.importFolders -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            folderResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        } else {
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                                folderResultLauncher?.launch(intent)
                            } else {
                                requireContext().toast(R.string.no_file_manager_found)
                            }
                        }
                        true
                    }
                    R.id.importFiles -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            fileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            })
                        } else {
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                            }
                            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                                fileResultLauncher?.launch(intent)
                            } else {
                                requireContext().toast(R.string.no_file_manager_found)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            val tabList = requireContext().prefs().getString(C.UI_SAVED_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_SAVED_TABS.split(',')
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
            }
            val adapter = SavedPagerAdapter(this@SavedPagerFragment, tabs)
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
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
                            toolbar.menu.findItem(R.id.importFolders).isVisible = fragment is DownloadsFragment
                            toolbar.menu.findItem(R.id.importFiles).isVisible = fragment is DownloadsFragment
                        }
                    }
                }
            })
            if (firstLaunch) {
                val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("1").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.offscreenPageLimit = adapter.itemCount
            viewPager.reduceDragSensitivity()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.bookmarks)
                    "1" -> getString(R.string.downloads)
                    else -> getString(R.string.bookmarks)
                }
            }.attach()
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
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
