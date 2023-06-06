package com.github.andreyasadchy.xtra.ui.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.viewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivitySettingsBinding
import com.github.andreyasadchy.xtra.ui.Utils
import com.github.andreyasadchy.xtra.ui.main.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.settings.api.DragListFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DisplayUtils
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    var recreate = false

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.apply {
            navigationIcon = Utils.getNavigationIcon(this@SettingsActivity)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        recreate = savedInstanceState?.getBoolean(SettingsFragment.KEY_CHANGED) == true
        if (savedInstanceState == null || recreate) {
            recreate = false
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, SettingsFragment())
                    .commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SettingsFragment.KEY_CHANGED, recreate)
    }

    @AndroidEntryPoint
    class SettingsFragment : PreferenceFragmentCompat() {

        private val viewModel: SettingsViewModel by viewModels()

        private var changed = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            changed = savedInstanceState?.getBoolean(KEY_CHANGED) == true
            if (changed) {
                requireActivity().setResult(Activity.RESULT_OK)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            val activity = requireActivity()
            val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
                setResult()
                true
            }

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
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(
                        if (value.toString() == "auto") {
                            null
                        } else {
                            value.toString()
                        }
                    ))
                    true
                }
            }

            findPreference<ListPreference>(C.UI_CUTOUTMODE)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setOnPreferenceChangeListener { _, _ ->
                        changed = true
                        activity.recreate()
                        true
                    }
                } else {
                    isVisible = false
                }
            }

            findPreference<Preference>("theme_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, ThemeSettingsFragment())
                    .addToBackStack(null)
                    .commit()
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

            findPreference<SeekBarPreference>("chatWidth")?.apply {
                summary = context.getString(R.string.pixels, activity.prefs().getInt(C.LANDSCAPE_CHAT_WIDTH, 30))
                setOnPreferenceChangeListener { _, newValue ->
                    setResult()
                    val chatWidth = DisplayUtils.calculateLandscapeWidthByPercent(activity, newValue as Int)
                    summary = context.getString(R.string.pixels, chatWidth)
                    activity.prefs().edit { putInt(C.LANDSCAPE_CHAT_WIDTH, chatWidth) }
                    true
                }
            }

            findPreference<Preference>("player_button_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, PlayerButtonSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

            findPreference<Preference>("player_menu_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, PlayerMenuSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                findPreference<ListPreference>(C.PLAYER_BACKGROUND_PLAYBACK)?.apply {
                    setEntries(R.array.backgroundPlaybackNoPipEntries)
                    setEntryValues(R.array.backgroundPlaybackNoPipValues)
                }
            }

            findPreference<Preference>("buffer_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, BufferSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

            findPreference<Preference>("clear_video_positions")?.setOnPreferenceClickListener {
                viewModel.deletePositions()
                requireContext().shortToast(R.string.cleared)
                true
            }

            findPreference<Preference>("token_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, TokenSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

            findPreference<Preference>("api_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, DragListFragment.newInstance())
                    .addToBackStack(null)
                    .commit()
                true
            }

            findPreference<Preference>("admin_settings")?.setOnPreferenceClickListener {
                startActivity(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")))
                true
            }

            findPreference<Preference>("get_integrity_token")?.setOnPreferenceClickListener {
                IntegrityDialog.show(childFragmentManager)
                true
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            outState.putBoolean(KEY_CHANGED, changed)
            super.onSaveInstanceState(outState)
        }

        private fun setResult() {
            if (!changed) {
                changed = true
                requireActivity().setResult(Activity.RESULT_OK)
            }
        }

        companion object {
            const val KEY_CHANGED = "changed"
        }
    }

    class ThemeSettingsFragment : PreferenceFragmentCompat() {

        private var changed = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            changed = savedInstanceState?.getBoolean(SettingsFragment.KEY_CHANGED) == true
            if (changed) {
                requireActivity().setResult(Activity.RESULT_OK)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.theme_preferences, rootKey)
            val activity = requireActivity()

            findPreference<ListPreference>(C.THEME)?.setOnPreferenceChangeListener { _, _ ->
                changed = true
                activity.recreate()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_THEME_FOLLOW_SYSTEM)?.setOnPreferenceChangeListener { _, _ ->
                changed = true
                activity.recreate()
                true
            }
            findPreference<ListPreference>(C.UI_THEME_DARK_ON)?.setOnPreferenceChangeListener { _, _ ->
                changed = true
                activity.recreate()
                true
            }
            findPreference<ListPreference>(C.UI_THEME_DARK_OFF)?.setOnPreferenceChangeListener { _, _ ->
                changed = true
                activity.recreate()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_STATUSBAR)?.setOnPreferenceChangeListener { _, _ ->
                changed = true
                activity.recreate()
                true
            }
            findPreference<SwitchPreferenceCompat>(C.UI_NAVBAR)?.setOnPreferenceChangeListener { _, _ ->
                changed = true
                activity.recreate()
                true
            }
        }

        override fun onSaveInstanceState(outState: Bundle) {
            outState.putBoolean(SettingsFragment.KEY_CHANGED, changed)
            super.onSaveInstanceState(outState)
        }
    }

    class PlayerButtonSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_button_preferences, rootKey)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_AUDIO_COMPRESSOR_BUTTON)?.isVisible = false
            }
        }
    }

    class PlayerMenuSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_menu_preferences, rootKey)
        }
    }

    class BufferSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.buffer_preferences, rootKey)
        }
    }

    class TokenSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.token_preferences, rootKey)
        }
    }
}