package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.SettingsNavGraphDirections
import com.github.andreyasadchy.xtra.databinding.ActivitySettingsBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.model.ui.SettingsSearchItem
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.util.AdminReceiver
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DisplayUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.convertDpToPixels
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.appbar.AppBarLayout
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.chromium.net.CronetProvider
import java.util.Collections
import java.util.Locale

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var changed = false
    var searchItem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean(KEY_CHANGED) == true) {
            setResult()
        }
        applyTheme()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val ignoreCutouts = prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            val cutoutInsets = if (ignoreCutouts) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            } else {
                insets
            }
            binding.appBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            windowInsets
        }
        val navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        val appBarConfiguration = AppBarConfiguration(setOf(), fallbackOnNavigateUpListener = {
            onBackPressedDispatcher.onBackPressed()
            true
        })
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> {
                    navController.navigate(SettingsNavGraphDirections.actionGlobalSettingsSearchFragment())
                    true
                }
                else -> false
            }
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var job: Job? = null

            override fun onQueryTextSubmit(query: String): Boolean {
                (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                job?.cancel()
                if (newText.isNotEmpty()) {
                    job = lifecycleScope.launch {
                        delay(750)
                        withResumed {
                            (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                        }
                    }
                } else {
                    (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                }
                return false
            }
        })
    }

    fun showDragListDialog(list: List<SettingsDragListItem>, prefKey: String, title: CharSequence?) {
        val listAdapter = SettingsDragListAdapter()
        val itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    Collections.swap(list, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled(): Boolean {
                    return false
                }
            }
        )
        listAdapter.itemTouchHelper = itemTouchHelper
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = listAdapter
            val padding = convertDpToPixels(10f)
            setPadding(0, padding, 0, 0)
        }
        listAdapter.setDefault = { item ->
            list.find { it.default }?.let { previous ->
                previous.default = false
                recyclerView.findViewHolderForAdapterPosition(
                    list.indexOf(previous)
                )?.itemView?.findViewById<ImageButton>(R.id.setAsDefault)?.let {
                    it.setImageResource(R.drawable.outline_home_black_24)
                    it.isClickable = true
                }
            }
            item.default = true
        }
        itemTouchHelper.attachToRecyclerView(recyclerView)
        listAdapter.submitList(list)
        getAlertDialogBuilder()
            .setTitle(title)
            .setView(recyclerView)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                prefs().edit {
                    putString(prefKey, listAdapter.currentList.joinToString(",") {
                        "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                    })
                    setResult()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showSearchView(showSearch: Boolean) {
        with(binding) {
            if (showSearch) {
                toolbar.menu.findItem(R.id.search).isVisible = false
                searchView.visible()
            } else {
                toolbar.menu.findItem(R.id.search).isVisible = true
                searchView.setQuery(null, false)
                searchView.gone()
            }
        }
    }

    private fun getSelectedSearchItem(): String? {
        return searchItem?.also {
            searchItem = null
        }
    }

    private fun setResult() {
        if (!changed) {
            changed = true
            setResult(RESULT_OK)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CHANGED, changed)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val KEY_CHANGED = "changed"
    }

    class SettingsFragment : MaterialPreferenceFragment() {

        private val viewModel: SettingsViewModel by activityViewModels()
        private var backupResultLauncher: ActivityResultLauncher<Intent>? = null
        private var restoreResultLauncher: ActivityResultLauncher<Intent>? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            backupResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let {
                        viewModel.backupSettings(it.toString())
                    }
                }
            }
            restoreResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val list = mutableListOf<String>()
                    result.data?.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            val item = clipData.getItemAt(i)
                            item.uri?.let {
                                list.add(it.toString())
                            }
                        }
                    } ?: result.data?.data?.let {
                        list.add(it.toString())
                    }
                    viewModel.restoreSettings(
                        list = list,
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                        helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
                    )
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            findPreference<ListPreference>(C.UI_LANGUAGE)?.apply {
                val lang = AppCompatDelegate.getApplicationLocales()
                if (lang.isEmpty) {
                    setValueIndex(findIndexOfValue("auto"))
                } else {
                    try {
                        setValueIndex(findIndexOfValue(lang.toLanguageTags()))
                    } catch (e: Exception) {
                        try {
                            setValueIndex(findIndexOfValue(
                                lang.toLanguageTags().substringBefore("-").let {
                                    when (it) {
                                        "id" -> "in"
                                        "pt" -> "pt-BR"
                                        "zh" -> "zh-TW"
                                        else -> it
                                    }
                                }
                            ))
                        } catch (e: Exception) {
                            setValueIndex(findIndexOfValue("en"))
                        }
                    }
                }
                setOnPreferenceChangeListener { _, value ->
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            if (value.toString() == "auto") {
                                null
                            } else {
                                value.toString()
                            }
                        )
                    )
                    true
                }
            }
            findPreference<SwitchPreferenceCompat>(C.UI_DRAW_BEHIND_CUTOUTS)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setOnPreferenceChangeListener { _, _ ->
                        (requireActivity() as? SettingsActivity)?.changed = true
                        requireActivity().recreate()
                        true
                    }
                } else {
                    isVisible = false
                }
            }
            findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                viewModel.toggleNotifications(
                    enabled = newValue as Boolean,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
                )
                true
            }
            findPreference<Preference>("theme_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalThemeSettingsFragment())
                true
            }
            findPreference<Preference>("ui_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalUiSettingsFragment())
                true
            }
            findPreference<Preference>("chat_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalChatSettingsFragment())
                true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_PICTURE_IN_PICTURE)?.isVisible = false
            }
            findPreference<Preference>("player_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment())
                true
            }
            findPreference<Preference>("player_button_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment())
                true
            }
            findPreference<Preference>("buffer_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalBufferSettingsFragment())
                true
            }
            findPreference<Preference>("proxy_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalProxySettingsFragment())
                true
            }
            findPreference<Preference>("playback_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlaybackSettingsFragment())
                true
            }
            findPreference<Preference>("api_token_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalApiTokenSettingsFragment())
                true
            }
            findPreference<Preference>("download_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment())
                true
            }
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
                viewModel.checkUpdates(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    requireContext().prefs().getString(C.UPDATE_URL, null) ?: "https://api.github.com/repos/crackededed/xtra/releases/tags/latest",
                    requireContext().tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0)
                )
                true
            }
            findPreference<Preference>("update_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment())
                true
            }
            findPreference<Preference>("backup_settings")?.setOnPreferenceClickListener {
                backupResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                true
            }
            findPreference<Preference>("restore_settings")?.setOnPreferenceClickListener {
                restoreResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                })
                true
            }
            findPreference<Preference>("debug_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalDebugSettingsFragment())
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateUrl.collectLatest {
                        if (it != null) {
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                                !requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false) &&
                                !requireContext().packageManager.canRequestPackageInstalls()
                            ) {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${requireContext().packageName}")
                                    )
                                    startActivity(intent)
                                } catch (e: ActivityNotFoundException) {

                                }
                            }
                            requireActivity().getAlertDialogBuilder()
                                .setTitle(getString(R.string.update_available))
                                .setMessage(getString(R.string.update_message))
                                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                    if (requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false)) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, it.toUri()).apply {
                                                addCategory(Intent.CATEGORY_BROWSABLE)
                                            }
                                            requireContext().startActivity(intent)
                                            requireContext().tokenPrefs().edit {
                                                putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                            }
                                        } catch (e: ActivityNotFoundException) {
                                            requireContext().toast(R.string.no_browser_found)
                                        }
                                    } else {
                                        viewModel.downloadUpdate(requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"), it)
                                    }
                                }
                                .setNegativeButton(getString(R.string.no), null)
                                .show()
                        } else {
                            requireContext().toast(R.string.no_updates_found)
                        }
                    }
                }
            }
        }
    }

    class ThemeSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.theme_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                findPreference<ListPreference>(C.THEME)?.apply {
                    setEntries(R.array.themeNoDynamicEntries)
                    setEntryValues(R.array.themeNoDynamicValues)
                }
                findPreference<ListPreference>(C.UI_THEME_DARK_ON)?.apply {
                    setEntries(R.array.themeNoDynamicEntries)
                    setEntryValues(R.array.themeNoDynamicValues)
                }
                findPreference<ListPreference>(C.UI_THEME_DARK_OFF)?.apply {
                    setEntries(R.array.themeNoDynamicEntries)
                    setEntryValues(R.array.themeNoDynamicValues)
                }
            }
            val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
                (requireActivity() as? SettingsActivity)?.changed = true
                requireActivity().recreate()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_ROUNDUSERIMAGE)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.THEME)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_ROUNDED_CORNERS)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_FOLLOW_SYSTEM)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.UI_THEME_DARK_ON)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.UI_THEME_DARK_OFF)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_APPBAR_LIFT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_BOTTOM_NAV_COLOR)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_MATERIAL3)?.onPreferenceChangeListener = changeListener
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class UiSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.ui_preferences, rootKey)
            val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
                (requireActivity() as? SettingsActivity)?.setResult()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_ROUNDUSERIMAGE)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_TRUNCATEVIEWCOUNT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_UPTIME)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_TAGS)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_BROADCASTERSCOUNT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_BOOKMARK_TIME_LEFT)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.UI_SCROLLTOP)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.PORTRAIT_COLUMN_COUNT)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.LANDSCAPE_COLUMN_COUNT)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.COMPACT_STREAMS)?.onPreferenceChangeListener = changeListener
            findPreference<Preference>("ui_navigation_tab_list_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_NAVIGATION_TAB_LIST.split(',')
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
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.games)
                            "1" -> getString(R.string.popular)
                            "2" -> getString(R.string.following)
                            "3" -> getString(R.string.saved)
                            else -> getString(R.string.popular)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_NAVIGATION_TAB_LIST, preference.title)
                true
            }
            findPreference<Preference>("ui_following_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_FOLLOWING_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_FOLLOWING_TABS.split(',')
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
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.games)
                            "1" -> getString(R.string.live)
                            "2" -> getString(R.string.videos)
                            "3" -> getString(R.string.channels)
                            else -> getString(R.string.live)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_FOLLOWING_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_saved_tabs_dialog")?.setOnPreferenceClickListener { preference ->
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
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.bookmarks)
                            "1" -> getString(R.string.downloads)
                            "2" -> getString(R.string.filters)
                            else -> getString(R.string.downloads)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_SAVED_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_channel_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_CHANNEL_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_CHANNEL_TABS.split(',')
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
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.suggested)
                            "1" -> getString(R.string.videos)
                            "2" -> getString(R.string.clips)
                            "3" -> getString(R.string.chat)
                            "4" -> getString(R.string.about)
                            else -> getString(R.string.videos)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_CHANNEL_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_game_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_GAME_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_GAME_TABS.split(',')
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
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.videos)
                            "1" -> getString(R.string.live)
                            "2" -> getString(R.string.clips)
                            else -> getString(R.string.live)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_GAME_TABS, preference.title)
                true
            }
            findPreference<Preference>("ui_search_tabs_dialog")?.setOnPreferenceClickListener { preference ->
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
                val tabs = tabList.map {
                    val split = it.split(':')
                    SettingsDragListItem(
                        key = split[0],
                        text = when (split[0]) {
                            "0" -> getString(R.string.videos)
                            "1" -> getString(R.string.streams)
                            "2" -> getString(R.string.channels)
                            "3" -> getString(R.string.games)
                            else -> getString(R.string.channels)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_SEARCH_TABS, preference.title)
                true
            }
            findPreference<Preference>("delete_recent_searches")?.setOnPreferenceClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_recent_searches_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        viewModel.deleteRecentSearches()
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class ChatSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.chat_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                findPreference<ListPreference>(C.CHAT_IMAGE_LIBRARY)?.apply {
                    setEntries(R.array.imageLibraryEntriesNoWebp)
                    setEntryValues(R.array.imageLibraryValuesNoWebp)
                }
            }
            findPreference<SeekBarPreference>("chatWidth")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    (requireActivity() as? SettingsActivity)?.setResult()
                    val chatWidth = DisplayUtils.calculateLandscapeWidthByPercent(requireActivity(), newValue as Int)
                    requireContext().prefs().edit { putInt(C.LANDSCAPE_CHAT_WIDTH, chatWidth) }
                    true
                }
            }
            if (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                val languages = TranslateLanguage.getAllLanguages()
                val names = languages.map { Locale.forLanguageTag(it).displayLanguage }.toTypedArray()
                findPreference<Preference>("downloaded_languages")?.setOnPreferenceClickListener {
                    val modelManager = RemoteModelManager.getInstance()
                    modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                        .addOnSuccessListener { models ->
                            val downloaded = models.map { it.language }
                            val checked = languages.map { downloaded.contains(it) }.toBooleanArray()
                            val selectedItems = downloaded.toMutableList()
                            requireActivity().getAlertDialogBuilder()
                                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                                    languages.getOrNull(which)?.let { language ->
                                        if (isChecked) {
                                            if (!selectedItems.contains(language)) {
                                                selectedItems.add(language)
                                            }
                                        } else {
                                            selectedItems.remove(language)
                                        }
                                    }
                                }
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    downloaded.filter { !selectedItems.contains(it) }.forEach {
                                        modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build())
                                    }
                                    selectedItems.filter { !downloaded.contains(it) }.forEach {
                                        modelManager.download(
                                            TranslateRemoteModel.Builder(it).build(),
                                            DownloadConditions.Builder().build()
                                        )
                                    }
                                }
                                .setNegativeButton(getString(android.R.string.cancel), null)
                                .show()
                        }
                    true
                }
                findPreference<ListPreference>("chat_translate_target")?.apply {
                    entries = names
                    entryValues = languages.toTypedArray()
                }
            } else {
                findPreference<SwitchPreferenceCompat>("chat_translate")?.isVisible = false
                findPreference<Preference>("downloaded_languages")?.isVisible = false
                findPreference<ListPreference>("chat_translate_target")?.isVisible = false
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlayerSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED)?.isVisible = false
                findPreference<SwitchPreferenceCompat>(C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED)?.isVisible = false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_ROUNDED_CORNER_PADDING)?.isVisible = false
            }
            findPreference<Preference>("delete_video_positions")?.setOnPreferenceClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_video_positions_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        viewModel.deletePositions()
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlayerButtonSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_button_preferences, rootKey)
            findPreference<SwitchPreferenceCompat>("sleep_timer_lock")?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && !(requireContext().getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(ComponentName(requireContext(), AdminReceiver::class.java))) {
                    requireContext().startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(requireContext(), AdminReceiver::class.java))
                        }
                    )
                }
                true
            }
            findPreference<Preference>("admin_settings")?.setOnPreferenceClickListener {
                startActivity(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")))
                true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_AUDIO_COMPRESSOR_BUTTON)?.isVisible = false
            }
            findPreference<Preference>("player_menu_settings")?.setOnPreferenceClickListener {
                requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
                findNavController().navigate(SettingsNavGraphDirections.actionGlobalPlayerMenuSettingsFragment())
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlayerMenuSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_menu_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class BufferSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.buffer_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class ProxySettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.proxy_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class PlaybackSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.playback_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class ApiTokenSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.api_token_preferences, rootKey)
            findPreference<EditTextPreference>("user_id")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.USER_ID, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.USER_ID, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("username")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.USERNAME, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.USERNAME, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("token")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.TOKEN, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.TOKEN, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("gql_token2")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_TOKEN2, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.GQL_TOKEN2, newValue.toString())
                    }
                    true
                }
            }
            findPreference<EditTextPreference>("gql_token_web")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.GQL_TOKEN_WEB, newValue.toString())
                    }
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class DownloadSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.download_preferences, rootKey)
            findPreference<Preference>("import_app_downloads")?.setOnPreferenceClickListener {
                viewModel.importDownloads()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class UpdateSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.update_preferences, rootKey)
            findPreference<SwitchPreferenceCompat>("update_check_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    newValue == true &&
                    !requireContext().prefs().getBoolean(C.UPDATE_USE_BROWSER, false) &&
                    !requireContext().packageManager.canRequestPackageInstalls()
                ) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {

                    }
                }
                true
            }
            findPreference<EditTextPreference>("update_check_frequency")?.apply {
                summary = getString(R.string.update_check_frequency_summary, text)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = getString(R.string.update_check_frequency_summary, newValue)
                    true
                }
            }
            findPreference<SwitchPreferenceCompat>("update_use_browser")?.setOnPreferenceChangeListener { _, newValue ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    newValue == false &&
                    requireContext().prefs().getBoolean(C.UPDATE_CHECK_ENABLED, false) &&
                    !requireContext().packageManager.canRequestPackageInstalls()
                ) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {

                    }
                }
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class DebugSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.debug_preferences, rootKey)
            findPreference<Preference>("api_settings")?.setOnPreferenceClickListener { preference ->
                var newId = 1000
                val view = LinearLayout(requireContext()).apply {
                    id = R.id.layout
                    orientation = LinearLayout.VERTICAL
                }
                val list = listOf(
                    Triple(getString(R.string.games), C.API_PREFS_GAMES, C.DEFAULT_API_PREFS_GAMES),
                    Triple(getString(R.string.streams), C.API_PREFS_STREAMS, C.DEFAULT_API_PREFS_STREAMS),
                    Triple(getString(R.string.followed_games), C.API_PREFS_FOLLOWED_GAMES, C.DEFAULT_API_PREFS_FOLLOWED_GAMES),
                    Triple(getString(R.string.followed_streams), C.API_PREFS_FOLLOWED_STREAMS, C.DEFAULT_API_PREFS_FOLLOWED_STREAMS),
                    Triple(getString(R.string.followed_videos), C.API_PREFS_FOLLOWED_VIDEOS, C.DEFAULT_API_PREFS_FOLLOWED_VIDEOS),
                    Triple(getString(R.string.followed_channels), C.API_PREFS_FOLLOWED_CHANNELS, C.DEFAULT_API_PREFS_FOLLOWED_CHANNELS),
                    Triple(getString(R.string.channel_videos), C.API_PREFS_CHANNEL_VIDEOS, C.DEFAULT_API_PREFS_CHANNEL_VIDEOS),
                    Triple(getString(R.string.channel_clips), C.API_PREFS_CHANNEL_CLIPS, C.DEFAULT_API_PREFS_CHANNEL_CLIPS),
                    Triple(getString(R.string.game_videos), C.API_PREFS_GAME_VIDEOS, C.DEFAULT_API_PREFS_GAME_VIDEOS),
                    Triple(getString(R.string.game_streams), C.API_PREFS_GAME_STREAMS, C.DEFAULT_API_PREFS_GAME_STREAMS),
                    Triple(getString(R.string.game_clips), C.API_PREFS_GAME_CLIPS, C.DEFAULT_API_PREFS_GAME_CLIPS),
                    Triple(getString(R.string.search_videos), C.API_PREFS_SEARCH_VIDEOS, C.DEFAULT_API_PREFS_SEARCH_VIDEOS),
                    Triple(getString(R.string.search_streams), C.API_PREFS_SEARCH_STREAMS, C.DEFAULT_API_PREFS_SEARCH_STREAMS),
                    Triple(getString(R.string.search_channels), C.API_PREFS_SEARCH_CHANNELS, C.DEFAULT_API_PREFS_SEARCH_CHANNELS),
                    Triple(getString(R.string.search_games), C.API_PREFS_SEARCH_GAMES, C.DEFAULT_API_PREFS_SEARCH_GAMES),
                ).map { item ->
                    newId++
                    view.addView(TextView(requireContext()).apply {
                        text = item.first
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(context.convertDpToPixels(20F), context.convertDpToPixels(3F), context.convertDpToPixels(20F), context.convertDpToPixels(3F))
                        }
                        context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceTitleMedium)).use {
                            TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                        }
                    })
                    val prefKey = item.second
                    val list = (requireContext().prefs().getString(prefKey, null) ?: item.third).split(',').map {
                        val split = it.split(':')
                        SettingsDragListItem(
                            key = split[0],
                            text = when (split[0]) {
                                "0" -> getString(R.string.api_gql)
                                "1" -> getString(R.string.api_gql_persisted_query)
                                "2" -> getString(R.string.api_helix)
                                else -> getString(R.string.api_gql)
                            },
                            default = false,
                            enabled = split[1] != "0",
                        )
                    }
                    val listAdapter = SettingsDragListAdapter()
                    val itemTouchHelper = ItemTouchHelper(
                        object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                                Collections.swap(list, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                                listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                                return true
                            }

                            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                            override fun isLongPressDragEnabled(): Boolean {
                                return false
                            }
                        }
                    )
                    listAdapter.itemTouchHelper = itemTouchHelper
                    val recyclerView = RecyclerView(requireContext()).apply {
                        id = newId
                        layoutManager = LinearLayoutManager(requireContext())
                        adapter = listAdapter
                    }
                    itemTouchHelper.attachToRecyclerView(recyclerView)
                    listAdapter.submitList(list)
                    view.addView(recyclerView)
                    prefKey to listAdapter
                }
                requireContext().getAlertDialogBuilder()
                    .setTitle(preference.title)
                    .setView(NestedScrollView(requireContext()).apply {
                        addView(view)
                        setPadding(0, context.convertDpToPixels(10F), 0, 0)
                    })
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        requireContext().prefs().edit {
                            list.forEach { item ->
                                putString(item.first, item.second.currentList.joinToString(",") {
                                    "${it.key}:${if (it.enabled) "1" else "0"}"
                                })
                            }
                            (requireActivity() as? SettingsActivity)?.setResult()
                        }
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
                true
            }
            findPreference<EditTextPreference>("gql_headers")?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_HEADERS, null)
                setOnPreferenceChangeListener { _, newValue ->
                    requireContext().tokenPrefs().edit {
                        putString(C.GQL_HEADERS, newValue.toString())
                    }
                    true
                }
            }
            findPreference<Preference>("get_integrity_token")?.setOnPreferenceClickListener {
                IntegrityDialog.show(childFragmentManager)
                true
            }
            val httpEngine = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
            val cronet = CronetProvider.getAllProviders(requireContext()).any { it.isEnabled }
            if (!httpEngine || !cronet) {
                findPreference<ListPreference>(C.NETWORK_LIBRARY)?.apply {
                    when {
                        !httpEngine && !cronet -> {
                            isVisible = false
                        }
                        !cronet -> {
                            setEntries(R.array.networkLibraryEntriesNoCronet)
                            setEntryValues(R.array.networkLibraryEntriesNoCronet)
                        }
                        else -> {
                            setEntries(R.array.networkLibraryEntriesNoHttpEngine)
                            setEntryValues(R.array.networkLibraryEntriesNoHttpEngine)
                        }
                    }
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                listView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    listView.let {
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
            }
            (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { scrollToPreference(it) }
        }
    }

    class SettingsSearchFragment : Fragment() {
        private var preferences: List<SettingsSearchItem>? = null
        private var adapter: SettingsSearchAdapter? = null
        private var savedQuery: String? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            return RecyclerView(requireContext()).apply {
                clipToPadding = false
                layoutManager = LinearLayoutManager(requireContext())
            }
        }

        @SuppressLint("RestrictedApi")
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    (view as RecyclerView).let {
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
            }
            (requireActivity() as? SettingsActivity)?.showSearchView(true)
            adapter = SettingsSearchAdapter(this).also {
                it.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        it.unregisterAdapterDataObserver(this)
                        it.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                                try {
                                    if (positionStart == 0) {
                                        (view as RecyclerView).scrollToPosition(0)
                                    }
                                } catch (e: Exception) {

                                }
                            }
                        })
                    }
                })
            }
            (view as RecyclerView).adapter = adapter
            if (preferences == null) {
                val list = mutableListOf<SettingsSearchItem>()
                val preferenceManager = PreferenceManager(requireContext())
                listOf(
                    Triple(R.xml.api_token_preferences, SettingsNavGraphDirections.actionGlobalApiTokenSettingsFragment(), requireContext().getString(R.string.api_token_settings)),
                    Triple(R.xml.buffer_preferences, SettingsNavGraphDirections.actionGlobalBufferSettingsFragment(), requireContext().getString(R.string.buffer_settings)),
                    Triple(R.xml.chat_preferences, SettingsNavGraphDirections.actionGlobalChatSettingsFragment(), requireContext().getString(R.string.chat_settings)),
                    Triple(R.xml.debug_preferences, SettingsNavGraphDirections.actionGlobalDebugSettingsFragment(), requireContext().getString(R.string.debug_settings)),
                    Triple(R.xml.download_preferences, SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment(), requireContext().getString(R.string.download_settings)),
                    Triple(R.xml.playback_preferences, SettingsNavGraphDirections.actionGlobalPlaybackSettingsFragment(), requireContext().getString(R.string.playback_settings)),
                    Triple(R.xml.player_button_preferences, SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment(), requireContext().getString(R.string.player_buttons)),
                    Triple(R.xml.player_menu_preferences, SettingsNavGraphDirections.actionGlobalPlayerMenuSettingsFragment(), requireContext().getString(R.string.player_menu_settings)),
                    Triple(R.xml.player_preferences, SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment(), requireContext().getString(R.string.player_settings)),
                    Triple(R.xml.proxy_preferences, SettingsNavGraphDirections.actionGlobalProxySettingsFragment(), requireContext().getString(R.string.proxy_settings)),
                    Triple(R.xml.root_preferences, SettingsNavGraphDirections.actionGlobalSettingsFragment(), null),
                    Triple(R.xml.theme_preferences, SettingsNavGraphDirections.actionGlobalThemeSettingsFragment(), requireContext().getString(R.string.theme)),
                    Triple(R.xml.ui_preferences, SettingsNavGraphDirections.actionGlobalUiSettingsFragment(), requireContext().getString(R.string.ui_settings)),
                    Triple(R.xml.update_preferences, SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment(), requireContext().getString(R.string.update_settings)),
                ).forEach { item ->
                    preferenceManager.inflateFromResource(requireContext(), item.first, null).forEach {
                        when (it) {
                            is SwitchPreferenceCompat -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = if (it.isChecked) {
                                        requireContext().getString(R.string.enabled_setting)
                                    } else {
                                        requireContext().getString(R.string.disabled_setting)
                                    }
                                ))
                            }
                            is SeekBarPreference -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = it.value.toString()
                                ))
                            }
                            is PreferenceCategory -> {}
                            else -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                ))
                            }
                        }
                    }
                }
                preferences = list
            }
            requireActivity().findViewById<SearchView>(R.id.searchView)?.let {
                savedQuery?.let { query -> it.setQuery(query, true) }
                it.requestFocus()
                WindowCompat.getInsetsController(requireActivity().window, it).show(WindowInsetsCompat.Type.ime())
            }
        }

        fun search(query: String) {
            savedQuery = query
            if (query.isNotBlank()) {
                preferences?.filter { it.title?.contains(query, true) == true || it.summary?.contains(query, true) == true }?.let { list ->
                    adapter?.submitList(list)
                }
            } else {
                adapter?.submitList(emptyList())
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            (requireActivity() as? SettingsActivity)?.showSearchView(false)
        }
    }
}