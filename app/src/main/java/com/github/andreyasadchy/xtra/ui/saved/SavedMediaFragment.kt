package com.github.andreyasadchy.xtra.ui.saved

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.saved.bookmarks.BookmarksFragment
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.saved.filters.FiltersFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SavedMediaFragment : Fragment(), Scrollable, FragmentHost {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SavedPagerViewModel by viewModels()
    private var folderResultLauncher: ActivityResultLauncher<Intent>? = null
    private var fileResultLauncher: ActivityResultLauncher<Intent>? = null

    private var previousItem = -1

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentById(R.id.fragmentContainer)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previousItem = savedInstanceState?.getInt("previousItem", -1) ?: -1
        folderResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let {
                    requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    viewModel.saveFolders(it.toString())
                }
            }
        }
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
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
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
                        folderResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        true
                    }
                    R.id.importFiles -> {
                        fileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        })
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
            if (tabs.size > 1) {
                spinner.visible()
            }
            (spinner.editText as? MaterialAutoCompleteTextView)?.apply {
                setSimpleItems(tabs.map {
                    when (it) {
                        "0" -> getString(R.string.bookmarks)
                        "1" -> getString(R.string.downloads)
                        "2" -> getString(R.string.filters)
                        else -> getString(R.string.bookmarks)
                    }
                }.toTypedArray().ifEmpty { arrayOf(getString(R.string.bookmarks)) })
                setOnItemClickListener { _, _, position, _ ->
                    if (position != previousItem) {
                        childFragmentManager.beginTransaction().replace(R.id.fragmentContainer, onSpinnerItemSelected(tabs, position)).commit()
                        previousItem = position
                    }
                }
                if (previousItem == -1) {
                    val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
                    val position = tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("1").takeIf { it != -1 } ?: 0
                    childFragmentManager.beginTransaction().replace(R.id.fragmentContainer, onSpinnerItemSelected(tabs, position)).commit()
                    previousItem = position
                }
                if (previousItem <= tabs.lastIndex) {
                    setText(adapter.getItem(previousItem).toString(), false)
                }
            }
            childFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                        f.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
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
                    (f as? Sortable)?.setupSortBar(sortBar) ?: sortBar.root.gone()
                    toolbar.menu.findItem(R.id.importFolders).isVisible = f is DownloadsFragment
                    toolbar.menu.findItem(R.id.importFiles).isVisible = f is DownloadsFragment
                }
            }, false)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }
    }

    private fun onSpinnerItemSelected(tabs: List<String>, position: Int): Fragment {
        return when (tabs.getOrNull(position)) {
            "0" -> BookmarksFragment()
            "1" -> DownloadsFragment()
            "2" -> FiltersFragment()
            else -> BookmarksFragment()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("previousItem", previousItem)
        super.onSaveInstanceState(outState)
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