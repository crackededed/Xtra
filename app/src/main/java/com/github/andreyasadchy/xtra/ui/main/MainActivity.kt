package com.github.andreyasadchy.xtra.ui.main

import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityMainBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.view.SlidingLayout
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DisplayUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isInPortraitOrientation
import com.github.andreyasadchy.xtra.util.isLightTheme
import com.github.andreyasadchy.xtra.util.isNetworkAvailable
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), SlidingLayout.Listener {

    companion object {
        const val KEY_VIDEO = "video"

        const val INTENT_INSTALL_UPDATE = "com.github.andreyasadchy.xtra.INSTALL_UPDATE"
        const val INTENT_LIVE_NOTIFICATION = "com.github.andreyasadchy.xtra.LIVE_NOTIFICATION"
        const val INTENT_OPEN_DOWNLOADS_TAB = "com.github.andreyasadchy.xtra.OPEN_DOWNLOADS_TAB"
        const val INTENT_OPEN_DOWNLOADED_VIDEO = "com.github.andreyasadchy.xtra.OPEN_DOWNLOADED_VIDEO"
        const val INTENT_OPEN_PLAYER = "com.github.andreyasadchy.xtra.OPEN_PLAYER"
        const val INTENT_START_AUDIO_ONLY = "com.github.andreyasadchy.xtra.START_AUDIO_ONLY"
        const val INTENT_PLAY_PAUSE_PLAYER = "com.github.andreyasadchy.xtra.PLAY_PAUSE_PLAYER"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var navController: NavController
    var playerFragment: PlayerFragment? = null
        private set
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            lifecycleScope.launch {
                viewModel.checkNetworkStatus.value = true
            }
        }

        override fun onLost(network: Network) {
            lifecycleScope.launch {
                viewModel.checkNetworkStatus.value = true
            }
        }
    }
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_START_AUDIO_ONLY -> {
                    playerFragment?.startAudioOnly()
                    moveTaskToBack(false)
                }
                INTENT_PLAY_PAUSE_PLAYER -> {
                    playerFragment?.handlePlayPauseAction()
                }
            }
        }
    }
    private lateinit var prefs: SharedPreferences
    var settingsResultLauncher: ActivityResultLauncher<Intent>? = null
    var loginResultLauncher: ActivityResultLauncher<Intent>? = null
    var logoutResultLauncher: ActivityResultLauncher<Intent>? = null

    //Lifecycle methods

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = prefs()
        migrateSettings()
        if (tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0) <= 0L) {
            tokenPrefs().edit {
                putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collectLatest {
                    if (it != null &&
                        it != "done" &&
                        prefs.getBoolean(C.ENABLE_INTEGRITY, false) &&
                        prefs.getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                    ) {
                        IntegrityDialog.show(supportFragmentManager, it)
                        viewModel.integrity.value = "done"
                    }
                }
            }
        }
        applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setNavBarColor(isInPortraitOrientation)
        val ignoreCutouts = prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = if (ignoreCutouts) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            } else {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            }
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            binding.navBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
            }
            windowInsets
        }
        settingsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                recreate()
            }
        }
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                restartActivity()
            }
        }
        logoutResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            restartActivity()
        }

        var initialized = savedInstanceState != null
        initNavigation()
        if (!initialized && !isNetworkAvailable) {
            initialized = true
            shortToast(R.string.no_connection)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.checkNetworkStatus.collectLatest {
                    if (it) {
                        val online = isNetworkAvailable
                        if (viewModel.isNetworkAvailable.value != online) {
                            viewModel.isNetworkAvailable.value = online
                            if (initialized) {
                                shortToast(if (online) R.string.connection_restored else R.string.no_connection)
                            } else {
                                initialized = true
                            }
                            if (online) {
                                if (!TwitchApiHelper.checkedValidation && prefs.getBoolean(C.VALIDATE_TOKENS, true)) {
                                    viewModel.validate(
                                        prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                        TwitchApiHelper.getGQLHeaders(this@MainActivity, true),
                                        tokenPrefs().getString(C.GQL_TOKEN_WEB, null)?.takeIf { it.isNotBlank() }?.let { TwitchApiHelper.addTokenPrefixGQL(it) },
                                        TwitchApiHelper.getHelixHeaders(this@MainActivity),
                                        this@MainActivity.tokenPrefs().getString(C.USER_ID, null),
                                        this@MainActivity.tokenPrefs().getString(C.USERNAME, null),
                                        this@MainActivity
                                    )
                                }
                                if (!TwitchApiHelper.checkedUpdates &&
                                    prefs.getBoolean(C.UPDATE_CHECK_ENABLED, false) &&
                                    (prefs.getString(C.UPDATE_CHECK_FREQUENCY, "7")?.toIntOrNull() ?: 7) * 86400000 + tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0) < System.currentTimeMillis()
                                ) {
                                    viewModel.checkUpdates(
                                        prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                        prefs.getString(C.UPDATE_URL, null) ?: "https://api.github.com/repos/crackededed/xtra/releases/tags/latest",
                                        tokenPrefs().getLong(C.UPDATE_LAST_CHECKED, 0)
                                    )
                                }
                            }
                        }
                        viewModel.checkNetworkStatus.value = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.updateUrl.collectLatest {
                    if (it != null) {
                        getAlertDialogBuilder()
                            .setTitle(getString(R.string.update_available))
                            .setMessage(getString(R.string.update_message))
                            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                if (prefs.getBoolean(C.UPDATE_USE_BROWSER, false)) {
                                    val intent = Intent(Intent.ACTION_VIEW, it.toUri())
                                    if (intent.resolveActivity(packageManager) != null) {
                                        tokenPrefs().edit {
                                            putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                        }
                                        startActivity(intent)
                                    } else {
                                        toast(R.string.no_browser_found)
                                    }
                                } else {
                                    viewModel.downloadUpdate(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), it)
                                }
                            }
                            .setNegativeButton(getString(R.string.no)) { _, _ ->
                                tokenPrefs().edit {
                                    putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                                }
                            }
                            .show()
                    }
                }
            }
        }
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().apply {
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }.build(), networkCallback
        )
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter().apply {
                addAction(INTENT_START_AUDIO_ONLY)
                addAction(INTENT_PLAY_PAUSE_PLAYER)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        restorePlayerFragment()
        handleIntent(intent)
        if (prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false)) {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "live_notifications",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LiveNotificationWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }

    private fun setNavBarColor(isPortrait: Boolean) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                window.isNavigationBarContrastEnforced = !isPortrait || !binding.navBarContainer.isVisible
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                @Suppress("DEPRECATION")
                window.navigationBarColor = if (isPortrait && binding.navBarContainer.isVisible) {
                    Color.TRANSPARENT
                } else {
                    ContextCompat.getColor(this, if (!isLightTheme) R.color.darkScrim else R.color.lightScrim)
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                if (!isLightTheme) {
                    window.navigationBarColor = if (isPortrait && binding.navBarContainer.isVisible) {
                        Color.TRANSPARENT
                    } else {
                        ContextCompat.getColor(this, R.color.darkScrim)
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setNavBarColor(newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    override fun onResume() {
        super.onResume()
        restorePlayerFragment()
    }

    override fun onDestroy() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(pipActionReceiver)
        if (isFinishing) {
            playerFragment?.onClose()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun restartActivity() {
        finish()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true) &&
            playerFragment?.enterPictureInPicture() == true
        ) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: IllegalStateException) {
                //device doesn't support PIP
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.data.toString()
                when {
                    url.contains("twitch.tv/videos/") -> {
                        val id = url.substringAfter("twitch.tv/videos/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                        val offset = url.substringAfter("?t=").takeIf { it.isNotBlank() }?.let { (TwitchApiHelper.getDuration(it) ?: 0) * 1000 }
                        if (!id.isNullOrBlank()) {
                            viewModel.loadVideo(
                                id,
                                offset,
                                prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true),
                                prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(this),
                                TwitchApiHelper.getHelixHeaders(this),
                                prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.video.collectLatest { video ->
                                        if (video != null) {
                                            if (!video.id.isNullOrBlank()) {
                                                startVideo(video, offset, offset != null)
                                            }
                                            viewModel.video.value = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                    url.contains("/clip/") -> {
                        val id = url.substringAfter("/clip/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                        if (!id.isNullOrBlank()) {
                            viewModel.loadClip(
                                id,
                                prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(this),
                                TwitchApiHelper.getHelixHeaders(this),
                                prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.clip.collectLatest { clip ->
                                        if (clip != null) {
                                            if (!clip.id.isNullOrBlank()) {
                                                startClip(clip)
                                            }
                                            viewModel.clip.value = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                    url.contains("clips.twitch.tv/") -> {
                        val id = url.substringAfter("clips.twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                        if (!id.isNullOrBlank()) {
                            viewModel.loadClip(
                                id,
                                prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getHelixHeaders(this),
                                TwitchApiHelper.getGQLHeaders(this),
                                prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.clip.collectLatest { clip ->
                                        if (clip != null) {
                                            if (!clip.id.isNullOrBlank()) {
                                                startClip(clip)
                                            }
                                            viewModel.clip.value = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                    url.contains("twitch.tv/directory/category/") -> {
                        val slug = url.substringAfter("twitch.tv/directory/category/").takeIf { it.isNotBlank() }?.substringBefore("/")
                        if (!slug.isNullOrBlank()) {
                            playerFragment?.minimize()
                            navController.navigate(
                                if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameSlug = slug
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameSlug = slug
                                    )
                                }
                            )
                        }
                    }
                    url.contains("twitch.tv/directory/game/") -> {
                        val name = url.substringAfter("twitch.tv/directory/game/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                        if (!name.isNullOrBlank()) {
                            playerFragment?.minimize()
                            navController.navigate(
                                if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                                    GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                        gameName = Uri.decode(name)
                                    )
                                } else {
                                    GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                        gameName = Uri.decode(name)
                                    )
                                }
                            )
                        }
                    }
                    else -> {
                        val login = url.substringAfter("twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                        if (!login.isNullOrBlank()) {
                            viewModel.loadUser(
                                login,
                                prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(this),
                                TwitchApiHelper.getHelixHeaders(this),
                                prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    viewModel.user.collectLatest { user ->
                                        if (user != null) {
                                            if (!user.channelId.isNullOrBlank() || !user.channelLogin.isNullOrBlank()) {
                                                playerFragment?.minimize()
                                                navController.navigate(
                                                    ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                                        channelId = user.channelId,
                                                        channelLogin = user.channelLogin,
                                                        channelName = user.channelName,
                                                        channelLogo = user.channelLogo,
                                                    )
                                                )
                                            }
                                            viewModel.user.value = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            INTENT_INSTALL_UPDATE -> {
                val extras = intent.extras
                if (extras?.getInt(PackageInstaller.EXTRA_STATUS) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(Intent.EXTRA_INTENT)
                    }?.let {
                        tokenPrefs().edit {
                            putLong(C.UPDATE_LAST_CHECKED, System.currentTimeMillis())
                        }
                        startActivity(it)
                    }
                }
            }
            INTENT_LIVE_NOTIFICATION -> {
                startStream(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(KEY_VIDEO, Stream::class.java)!!
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(KEY_VIDEO)!!
                    }
                )
            }
            INTENT_OPEN_DOWNLOADS_TAB -> {
                binding.navBar.selectedItemId = if (prefs.getBoolean(C.UI_SAVEDPAGER, true)) {
                    R.id.savedPagerFragment
                } else {
                    R.id.savedMediaFragment
                }
            }
            INTENT_OPEN_DOWNLOADED_VIDEO -> {
                startOfflineVideo(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(KEY_VIDEO, OfflineVideo::class.java)!!
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(KEY_VIDEO)!!
                    }
                )
            }
            INTENT_OPEN_PLAYER -> playerFragment?.maximize() //TODO if was closed need to reopen
        }
    }

//Navigation listeners

    fun startStream(stream: Stream) {
        startPlayer(PlayerFragment.newInstance(stream))
    }

    fun startVideo(video: Video, offset: Long?, ignoreSavedPosition: Boolean = false) {
        startPlayer(PlayerFragment.newInstance(video, offset, ignoreSavedPosition))
    }

    fun startClip(clip: Clip) {
        startPlayer(PlayerFragment.newInstance(clip))
    }

    fun startOfflineVideo(video: OfflineVideo) {
        startPlayer(PlayerFragment.newInstance(video))
    }

//SlidingLayout.Listener

    override fun onMaximize() {
        viewModel.onMaximize()
    }

    override fun onMinimize() {
        viewModel.onMinimize()
    }

    override fun onClose() {
        closePlayer()
    }

//Player methods

    private fun startPlayer(fragment: PlayerFragment) {
        playerFragment?.onClose()
        playerFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.playerContainer, fragment).commit()
        viewModel.onPlayerStarted()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
        }
    }

    fun closePlayer() {
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .remove(supportFragmentManager.findFragmentById(R.id.playerContainer)!!)
            .commit()
        playerFragment = null
        viewModel.onPlayerClosed()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
        viewModel.sleepTimer?.cancel()
        viewModel.sleepTimerEndTime = 0L
    }

    private fun restorePlayerFragment() {
        if (playerFragment == null) {
            playerFragment = supportFragmentManager.findFragmentById(R.id.playerContainer) as? PlayerFragment
        } else {
            if (viewModel.isPlayerOpened && playerFragment?.secondViewIsHidden() == true && prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)) {
                playerFragment?.maximize()
            }
        }
    }

    fun setSleepTimer(duration: Long) {
        viewModel.sleepTimer?.cancel()
        viewModel.sleepTimerEndTime = 0L
        if (duration > 0L) {
            viewModel.sleepTimer = Timer().apply {
                schedule(duration) {
                    lifecycleScope.launch {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            playerFragment?.let {
                                it.onMinimize()
                                it.onClose()
                                closePlayer()
                            }
                            if (prefs.getBoolean(C.SLEEP_TIMER_LOCK, false)) {
                                if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
                                    try {
                                        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                                    } catch (e: SecurityException) {

                                    }
                                }
                            }
                        } else {
                            withStarted {
                                playerFragment?.let {
                                    it.onMinimize()
                                    it.onClose()
                                    closePlayer()
                                }
                            }
                        }
                    }
                }
            }
            viewModel.sleepTimerEndTime = System.currentTimeMillis() + duration
        }
    }

    fun getSleepTimerTimeLeft(): Long {
        return viewModel.sleepTimerEndTime - System.currentTimeMillis()
    }

    fun downloadStream(filesDir: String, id: String?, title: String?, startedAt: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModel.downloadStream(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), filesDir, id, title, startedAt, channelId, channelLogin, channelName, channelLogo, thumbnail, gameId, gameSlug, gameName, downloadPath, quality, downloadChat, downloadChatEmotes, wifiOnly)
    }

    fun downloadVideo(filesDir: String, id: String?, title: String?, uploadDate: String?, type: String?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, from: Long, to: Long, downloadChat: Boolean, downloadChatEmotes: Boolean, playlistToFile: Boolean, wifiOnly: Boolean) {
        viewModel.downloadVideo(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), filesDir, id, title, uploadDate, type, channelId, channelLogin, channelName, channelLogo, thumbnail, gameId, gameSlug, gameName, url, downloadPath, quality, from, to, downloadChat, downloadChatEmotes, playlistToFile, wifiOnly)
    }

    fun downloadClip(filesDir: String, clipId: String?, title: String?, uploadDate: String?, duration: Double?, videoId: String?, vodOffset: Int?, channelId: String?, channelLogin: String?, channelName: String?, channelLogo: String?, thumbnail: String?, gameId: String?, gameSlug: String?, gameName: String?, url: String, downloadPath: String, quality: String, downloadChat: Boolean, downloadChatEmotes: Boolean, wifiOnly: Boolean) {
        viewModel.downloadClip(prefs.getString(C.NETWORK_LIBRARY, "OkHttp"), filesDir, clipId, title, uploadDate, duration, videoId, vodOffset, channelId, channelLogin, channelName, channelLogo, thumbnail, gameId, gameSlug, gameName, url, downloadPath, quality, downloadChat, downloadChatEmotes, wifiOnly)
    }

    fun popFragment() {
        navController.navigateUp()
    }

    private fun initNavigation() {
        navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        val tabList = prefs.getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
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
        navController.setGraph(navController.navInflater.inflate(R.navigation.nav_graph).also {
            val startOnFollowed = prefs.getString(C.UI_STARTONFOLLOWED, "1")?.toIntOrNull() ?: 1
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()
            val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
            when {
                (isLoggedIn && startOnFollowed < 2) || (!isLoggedIn && startOnFollowed == 0) || defaultItem == "2" -> {
                    if (prefs.getBoolean(C.UI_FOLLOWPAGER, true)) {
                        it.setStartDestination(R.id.followPagerFragment)
                    } else {
                        it.setStartDestination(R.id.followMediaFragment)
                    }
                }
                defaultItem == "0" -> it.setStartDestination(R.id.rootGamesFragment)
                defaultItem == "3" -> {
                    if (prefs.getBoolean(C.UI_SAVEDPAGER, true)) {
                        it.setStartDestination(R.id.savedPagerFragment)
                    } else {
                        it.setStartDestination(R.id.savedMediaFragment)
                    }
                }
            }
        }, null)
        binding.navBar.apply {
            if (!prefs.getBoolean(C.UI_THEME_BOTTOM_NAV_COLOR, true) && prefs.getBoolean(C.UI_THEME_MATERIAL3, true)) {
                setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            }
            if (tabList.any { it.split(':')[2] != "0" }) {
                tabList.forEach {
                    val split = it.split(':')
                    val key = split[0]
                    val enabled = split[2] != "0"
                    if (enabled) {
                        when (key) {
                            "0" -> menu.add(Menu.NONE, R.id.rootGamesFragment, Menu.NONE, R.string.games).setIcon(R.drawable.ic_games_black_24dp)
                            "1" -> menu.add(Menu.NONE, R.id.rootTopFragment, Menu.NONE, R.string.popular).setIcon(R.drawable.ic_trending_up_black_24dp)
                            "2" -> {
                                if (prefs.getBoolean(C.UI_FOLLOWPAGER, true)) {
                                    menu.add(Menu.NONE, R.id.followPagerFragment, Menu.NONE, R.string.following).setIcon(R.drawable.ic_favorite_black_24dp)
                                } else {
                                    menu.add(Menu.NONE, R.id.followMediaFragment, Menu.NONE, R.string.following).setIcon(R.drawable.ic_favorite_black_24dp)
                                }
                            }
                            "3" -> {
                                if (prefs.getBoolean(C.UI_SAVEDPAGER, true)) {
                                    menu.add(Menu.NONE, R.id.savedPagerFragment, Menu.NONE, R.string.saved).setIcon(R.drawable.ic_file_download_black_24dp)
                                } else {
                                    menu.add(Menu.NONE, R.id.savedMediaFragment, Menu.NONE, R.string.saved).setIcon(R.drawable.ic_file_download_black_24dp)
                                }
                            }
                        }
                    }
                }
            } else {
                binding.navBarContainer.gone()
            }
            setupWithNavController(navController)
            setOnItemSelectedListener {
                NavigationUI.onNavDestinationSelected(it, navController)
                return@setOnItemSelectedListener true
            }
            setOnItemReselectedListener {
                if (!navController.popBackStack(it.itemId, false)) {
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0)
                    if (currentFragment is Scrollable) {
                        currentFragment.scrollToTop()
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun migrateSettings() {
        val version = prefs.getInt(C.SETTINGS_VERSION, 0).let {
            if (it == 0 && !prefs.getBoolean(C.FIRST_LAUNCH2, true)) {
                when {
                    !prefs.getBoolean(C.FIRST_LAUNCH9, true) -> 8
                    !prefs.getBoolean(C.FIRST_LAUNCH8, true) -> 7
                    !prefs.getBoolean(C.FIRST_LAUNCH7, true) -> 6
                    !prefs.getBoolean(C.FIRST_LAUNCH6, true) -> 5
                    !prefs.getBoolean(C.FIRST_LAUNCH5, true) -> 4
                    !prefs.getBoolean(C.FIRST_LAUNCH3, true) -> 3
                    !prefs.getBoolean(C.FIRST_LAUNCH1, true) -> 2
                    else -> 1
                }
            } else {
                it
            }
        }
        if (version < 1) {
            prefs.edit {
                putInt(C.LANDSCAPE_CHAT_WIDTH, DisplayUtils.calculateLandscapeWidthByPercent(this@MainActivity, 30))
                if (resources.getBoolean(R.bool.isTablet)) {
                    putString(C.PORTRAIT_COLUMN_COUNT, "2")
                    putString(C.LANDSCAPE_COLUMN_COUNT, "3")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    putString(C.THEME, "4")
                }
            }
        }
        if (version < 3) {
            val langPref = prefs.getString(C.UI_LANGUAGE, "")
            if (!langPref.isNullOrBlank() && langPref != "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langPref))
            }
        }
        if (version < 4) {
            prefs.edit {
                if (prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp") == "kd1unb4b3q4t58fwlpcbzcbnm76a8fp" && prefs().getString(C.GQL_TOKEN2, null).isNullOrBlank()) {
                    putString(C.GQL_CLIENT_ID2, "ue6666qo983tsx6so1t0vnawi233wa")
                    putString(C.GQL_REDIRECT2, "https://www.twitch.tv/settings/connections")
                }
            }
        }
        if (version < 5) {
            prefs.edit {
                if (prefs.getString(C.PLAYER_PROXY, "1")?.toIntOrNull() == 0) {
                    putBoolean(C.PLAYER_STREAM_PROXY, true)
                }
            }
        }
        if (version < 6) {
            prefs.edit {
                when {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).none { it.hardwareAccelerated } -> {
                        putString(C.TOKEN_SUPPORTED_CODECS, "h264")
                    }
                    MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_AV1, false, false).none { it.hardwareAccelerated } -> {
                        putString(C.TOKEN_SUPPORTED_CODECS, "h265,h264")
                    }
                }
            }
        }
        if (version < 7) {
            prefs.edit {
                if (prefs.getString(C.UI_CUTOUTMODE, "0") == "1") {
                    putBoolean(C.UI_DRAW_BEHIND_CUTOUTS, true)
                }
            }
        }
        if (version < 8) {
            tokenPrefs().edit {
                putString(C.USER_ID, prefs.getString(C.USER_ID, null))
                putString(C.USERNAME, prefs.getString(C.USERNAME, null))
                putString(C.TOKEN, prefs.getString(C.TOKEN, null))
                putString(C.GQL_TOKEN2, prefs.getString(C.GQL_TOKEN2, null))
                putString(C.GQL_HEADERS, prefs.getString(C.GQL_HEADERS, null))
                putLong(C.INTEGRITY_EXPIRATION, prefs.getLong(C.INTEGRITY_EXPIRATION, 0))
            }
            prefs.edit {
                remove(C.USER_ID)
                remove(C.USERNAME)
                remove(C.TOKEN)
                remove(C.GQL_TOKEN)
                remove(C.GQL_TOKEN2)
                remove(C.GQL_HEADERS)
                remove(C.INTEGRITY_EXPIRATION)
            }
        }
        if (version < 9) {
            prefs.edit {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    putBoolean(C.CHAT_USE_WEBP, false)
                    putString(C.CHAT_IMAGE_LIBRARY, "1")
                }
            }
        }
        if (version < 10) {
            viewModel.deleteOldImages()
            prefs.edit {
                prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0")?.let {
                    if (it == "1") {
                        putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                    } else if (it == "2") {
                        putBoolean(C.PLAYER_PICTURE_IN_PICTURE, false)
                        putBoolean(C.PLAYER_BACKGROUND_AUDIO, false)
                    }
                }
            }
        }
        if (version < 11) {
            prefs.edit {
                val tabs = prefs.getStringSet(C.UI_NAVIGATION_TABS, null)?.toSortedSet()
                val defaultPage = prefs.getString(C.UI_DEFAULT_PAGE, null)
                if (tabs != null || defaultPage != null) {
                    val set = tabs ?: setOf("0", "1", "2", "3")
                    val default = defaultPage ?: "1"
                    val list = "0:${if (default == "0") "1" else "0"}:${if (set.contains("0")) "1" else "0"}," +
                            "1:${if (default == "1") "1" else "0"}:${if (set.contains("1")) "1" else "0"}," +
                            "2:${if (default == "2") "1" else "0"}:${if (set.contains("2")) "1" else "0"}," +
                            "3:${if (default == "3") "1" else "0"}:${if (set.contains("3")) "1" else "0"}"
                    putString(C.UI_NAVIGATION_TAB_LIST, list)
                }
                val defaultFollowPage = prefs.getString(C.UI_FOLLOW_DEFAULT_PAGE, null)
                if (defaultFollowPage != null) {
                    val list = "0:${if (defaultFollowPage == "3") "1" else "0"}:1," +
                            "1:${if (defaultFollowPage == "0") "1" else "0"}:1," +
                            "2:${if (defaultFollowPage == "1") "1" else "0"}:1," +
                            "3:${if (defaultFollowPage == "2") "1" else "0"}:1"
                    putString(C.UI_FOLLOWING_TABS, list)
                }
                val defaultSavedPage = prefs.getString(C.UI_SAVED_DEFAULT_PAGE, null)
                if (defaultSavedPage != null) {
                    val list = "0:${if (defaultSavedPage == "0") "1" else "0"}:1," +
                            "1:${if (defaultSavedPage == "1") "1" else "0"}:1"
                    putString(C.UI_SAVED_TABS, list)
                }
                putInt(C.SETTINGS_VERSION, 11)
            }
        }
    }
}