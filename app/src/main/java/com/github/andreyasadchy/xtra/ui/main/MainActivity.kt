package com.github.andreyasadchy.xtra.ui.main

import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.NotLoggedIn
import com.github.andreyasadchy.xtra.model.offline.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.clips.BaseClipsFragment
import com.github.andreyasadchy.xtra.ui.common.OnChannelSelectedListener
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.pagers.MediaPagerFragment
import com.github.andreyasadchy.xtra.ui.download.HasDownloadDialog
import com.github.andreyasadchy.xtra.ui.follow.FollowMediaFragment
import com.github.andreyasadchy.xtra.ui.follow.FollowPagerFragment
import com.github.andreyasadchy.xtra.ui.games.GameFragment
import com.github.andreyasadchy.xtra.ui.games.GamesFragment
import com.github.andreyasadchy.xtra.ui.player.AudioPlayerService
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.clip.ClipPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.offline.OfflinePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerFragment
import com.github.andreyasadchy.xtra.ui.player.video.VideoPlayerFragment
import com.github.andreyasadchy.xtra.ui.saved.SavedMediaFragment
import com.github.andreyasadchy.xtra.ui.saved.SavedPagerFragment
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.search.SearchFragment
import com.github.andreyasadchy.xtra.ui.search.tags.BaseTagSearchFragment
import com.github.andreyasadchy.xtra.ui.streams.BaseStreamsFragment
import com.github.andreyasadchy.xtra.ui.top.TopFragment
import com.github.andreyasadchy.xtra.ui.videos.BaseVideosFragment
import com.github.andreyasadchy.xtra.ui.view.SlidingLayout
import com.github.andreyasadchy.xtra.util.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ncapdevi.fragnav.FragNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_media_pager.view.*


const val INDEX_GAMES = FragNavController.TAB1
const val INDEX_TOP = FragNavController.TAB2
const val INDEX_FOLLOWED = FragNavController.TAB3
const val INDEX_DOWNLOADS = FragNavController.TAB4

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), GamesFragment.OnGameSelectedListener, GamesFragment.OnTagGames, BaseStreamsFragment.OnStreamSelectedListener, OnChannelSelectedListener, BaseClipsFragment.OnClipSelectedListener, BaseVideosFragment.OnVideoSelectedListener, DownloadsFragment.OnVideoSelectedListener, SlidingLayout.Listener {

    companion object {
        const val KEY_CODE = "code"
        const val KEY_VIDEO = "video"

        const val INTENT_OPEN_DOWNLOADS_TAB = 0
        const val INTENT_OPEN_DOWNLOADED_VIDEO = 1
        const val INTENT_OPEN_PLAYER = 2
    }

    private val viewModel: MainViewModel by viewModels()
    var playerFragment: BasePlayerFragment? = null
        private set
    private val fragNavController = FragNavController(supportFragmentManager, R.id.fragmentContainer)
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.setNetworkAvailable(isNetworkAvailable)
        }
    }
    private lateinit var prefs: SharedPreferences

    //Lifecycle methods

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = prefs()
        if (prefs.getBoolean(C.FIRST_LAUNCH2, true)) {
            PreferenceManager.setDefaultValues(this@MainActivity, R.xml.root_preferences, false)
            PreferenceManager.setDefaultValues(this@MainActivity, R.xml.player_button_preferences, true)
            PreferenceManager.setDefaultValues(this@MainActivity, R.xml.player_menu_preferences, true)
            PreferenceManager.setDefaultValues(this@MainActivity, R.xml.buffer_preferences, true)
            PreferenceManager.setDefaultValues(this@MainActivity, R.xml.token_preferences, true)
            PreferenceManager.setDefaultValues(this@MainActivity, R.xml.api_token_preferences, true)
            prefs.edit {
                putBoolean(C.FIRST_LAUNCH2, false)
                putInt(C.LANDSCAPE_CHAT_WIDTH, DisplayUtils.calculateLandscapeWidthByPercent(this@MainActivity, 30))
                if (resources.getBoolean(R.bool.isTablet)) {
                    putString(C.PORTRAIT_COLUMN_COUNT, "2")
                    putString(C.LANDSCAPE_COLUMN_COUNT, "3")
                }
            }
        }
        if (prefs.getBoolean(C.FIRST_LAUNCH, true)) {
            prefs.edit {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    putString(C.CHAT_IMAGE_LIBRARY, "2")
                }
                putBoolean(C.FIRST_LAUNCH, false)
            }
        }
        if (prefs.getBoolean(C.FIRST_LAUNCH1, true)) {
            prefs.edit {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    putString(C.PLAYER_BACKGROUND_PLAYBACK, "1")
                } else {
                    putString(C.PLAYER_BACKGROUND_PLAYBACK, "0")
                }
                putBoolean(C.FIRST_LAUNCH1, false)
            }
        }
        if (prefs.getBoolean(C.FIRST_LAUNCH3, true)) {
            prefs.edit {
                val langPref = prefs.getString(C.UI_LANGUAGE, "")
                if (!langPref.isNullOrBlank() && langPref != "auto") {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langPref))
                }
                putBoolean(C.FIRST_LAUNCH3, false)
            }
        }
        applyTheme()
        setContentView(R.layout.activity_main)

        val notInitialized = savedInstanceState == null
        initNavigation()
        if ((Account.get(this) !is NotLoggedIn && ((prefs.getString(C.UI_STARTONFOLLOWED, "1")?.toInt() ?: 1) < 2)) || (Account.get(this) is NotLoggedIn && ((prefs.getString(C.UI_STARTONFOLLOWED, "1")?.toInt() ?: 1) == 0))) {
            fragNavController.initialize(INDEX_FOLLOWED, savedInstanceState)
            if (notInitialized) {
                navBar.selectedItemId = R.id.fragment_follow
            }
        } else {
            fragNavController.initialize(INDEX_TOP, savedInstanceState)
            if (notInitialized) {
                navBar.selectedItemId = R.id.fragment_top
            }
        }
        var flag = notInitialized && !isNetworkAvailable
        viewModel.isNetworkAvailable.observe(this) {
            it.getContentIfNotHandled()?.let { online ->
                if (online) {
                    if (prefs.getBoolean(C.VALIDATE_TOKENS, true)) {
                        viewModel.validate(prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"), prefs.getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"), this)
                    }
                }
                if (flag) {
                    shortToast(if (online) R.string.connection_restored else R.string.no_connection)
                } else {
                    flag = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (!viewModel.isPlayerMaximized) {
                if (fragNavController.isRootFragment) {
                    if ((Account.get(this@MainActivity) !is NotLoggedIn && ((prefs.getString(C.UI_STARTONFOLLOWED, "1")?.toInt() ?: 1) < 2)) || (Account.get(this@MainActivity) is NotLoggedIn && ((prefs.getString(C.UI_STARTONFOLLOWED, "1")?.toInt() ?: 1) == 0))) {
                        if (fragNavController.currentStackIndex != INDEX_FOLLOWED) {
                            navBar.selectedItemId = R.id.fragment_follow
                        } else {
                            finish()
                        }
                    } else {
                        if (fragNavController.currentStackIndex != INDEX_TOP) {
                            navBar.selectedItemId = R.id.fragment_top
                        } else {
                            finish()
                        }
                    }
                } else {
                    val currentFrag = fragNavController.currentFrag
                    if (currentFrag !is ChannelPagerFragment || (currentFrag.currentFragment.let { it !is ChatFragment || !it.hideEmotesMenu() })) {
                        fragNavController.popFragment()
                    }
                }
            } else {
                playerFragment?.let {
                    if (it is StreamPlayerFragment) {
                        if (!it.hideEmotesMenu()) {
                            it.minimize()
                        }
                    } else {
                        it.minimize()
                    }
                }
            }
        }
        registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        restorePlayerFragment()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        restorePlayerFragment()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        fragNavController.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        unregisterReceiver(networkReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Result of LoginActivity
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        fun restartActivity() {
            finish()
            overridePendingTransition(0, 0)
            startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) })
            overridePendingTransition(0, 0)
        }
        when (requestCode) {
            1 -> { //Was not logged in
                when (resultCode) {//Logged in
                    RESULT_OK -> restartActivity()
                }
            }
            2 -> restartActivity() //Was logged in
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            0 -> {
                if (grantResults.isNotEmpty() && grantResults.indexOf(PackageManager.PERMISSION_DENIED) == -1) {
                    val fragment = fragNavController.currentFrag
                    if (fragment is HasDownloadDialog) {
                        fragment.showDownloadDialog()
                    } else if (fragment is MediaPagerFragment && fragment.currentFragment is HasDownloadDialog) {
                        (fragment.currentFragment as HasDownloadDialog).showDownloadDialog()
                    }
                } else {
                    toast(R.string.permission_denied)
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        playerFragment?.let {
            if (it.enterPictureInPicture()) {
                it.setPauseHandled()
                if (prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0") == "0") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                        // player dialog
                        (it.childFragmentManager.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment?)?.dismiss()
                        // player chat message dialog
                        (it.childFragmentManager.findFragmentById(R.id.chatFragmentContainer)?.childFragmentManager?.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment?)?.dismiss()
                        // channel chat message dialog
                        val fragment = fragNavController.currentFrag as? ChannelPagerFragment?
                        if (fragment != null) {
                            ((fragment.childFragmentManager.findFragmentByTag("android:switcher:" + fragment.view?.viewPager?.id + ":" + fragment.view?.viewPager?.currentItem) as? ChatFragment?)?.childFragmentManager?.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment?)?.dismiss()
                        }
                        try {
                            val params = PictureInPictureParams.Builder()
                                .setSourceRectHint(Rect(0, 0, it.playerWidth, it.playerHeight))
//                            .setAspectRatio(Rational(it.playerWidth, it.playerHeight))
                                .build()
                            enterPictureInPictureMode(params)
                        } catch (e: IllegalStateException) {
                            //device doesn't support PIP
                        }
                    }
                } else {
                    if (prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0") == "1" && it.isPlaying()) {
                        (it as? StreamPlayerFragment)?.startAudioOnly() ?: (it as? VideoPlayerFragment)?.startAudioOnly() ?: (it as? OfflinePlayerFragment)?.startAudioOnly()
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val url = intent.data.toString()
            when {
                url.contains("twitch.tv/videos/") -> {
                    val id = url.substringAfter("twitch.tv/videos/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                    val offset = url.substringAfter("?t=").takeIf { it.isNotBlank() }?.let { (TwitchApiHelper.getDuration(it)?.toDouble() ?: 0.0) * 1000.0 }
                    if (!id.isNullOrBlank()) {
                        viewModel.loadVideo(id, prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(this).helixToken, prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"))
                        viewModel.video.observe(this) { video ->
                            if (video != null && !video.id.isNullOrBlank()) {
                                startVideo(video, offset)
                            }
                        }
                    }
                }
                url.contains("/clip/") -> {
                    val id = url.substringAfter("/clip/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                    if (!id.isNullOrBlank()) {
                        viewModel.loadClip(id, prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(this).helixToken, prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"))
                        viewModel.clip.observe(this) { clip ->
                            if (clip != null && !clip.id.isNullOrBlank()) {
                                startClip(clip)
                            }
                        }
                    }
                }
                url.contains("clips.twitch.tv/") -> {
                    val id = url.substringAfter("clips.twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                    if (!id.isNullOrBlank()) {
                        viewModel.loadClip(id, prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(this).helixToken, prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"))
                        viewModel.clip.observe(this) { clip ->
                            if (clip != null && !clip.id.isNullOrBlank()) {
                                startClip(clip)
                            }
                        }
                    }
                }
                url.contains("twitch.tv/directory/game/") -> {
                    val name = url.substringAfter("twitch.tv/directory/game/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                    if (!name.isNullOrBlank()) {
                        playerFragment?.minimize()
                        openGame(id = null, name = Uri.decode(name))
                    }
                }
                else -> {
                    val login = url.substringAfter("twitch.tv/").takeIf { it.isNotBlank() }?.let { it.substringBefore("?", it.substringBefore("/")) }
                    if (!login.isNullOrBlank()) {
                        viewModel.loadUser(login, prefs.getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi"), Account.get(this).helixToken, prefs.getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko"))
                        viewModel.user.observe(this) { user ->
                            if (user != null && (!user.channelId.isNullOrBlank() || !user.channelLogin.isNullOrBlank())) {
                                playerFragment?.minimize()
                                viewChannel(id = user.channelId, login = user.channelLogin, name = user.channelName, channelLogo = user.channelLogo)
                            }
                        }
                    }
                }
            }
        } else {
            when (intent?.getIntExtra(KEY_CODE, -1)) {
                INTENT_OPEN_DOWNLOADS_TAB -> navBar.selectedItemId = R.id.fragment_downloads
                INTENT_OPEN_DOWNLOADED_VIDEO -> startOfflineVideo(intent.getParcelableExtra(KEY_VIDEO)!!)
                INTENT_OPEN_PLAYER -> { //TODO if was closed need to reopen
                    playerFragment?.maximize() ?: AudioPlayerService.connection?.let { XtraApp.INSTANCE.unbindService(it) }
                }
            }
        }
    }

//Navigation listeners

    override fun openTagGames(tags: List<String>?) {
        fragNavController.pushFragment(GamesFragment.newInstance(tags))
    }

    override fun openGame(id: String?, name: String?, tags: List<String>?, updateLocal: Boolean) {
        fragNavController.pushFragment(GameFragment.newInstance(id, name, tags, updateLocal))
    }

    override fun startStream(stream: Stream) {
//        playerFragment?.play(stream)
        startPlayer(StreamPlayerFragment.newInstance(stream))
    }

    override fun startVideo(video: Video, offset: Double?) {
        startPlayer(VideoPlayerFragment.newInstance(video, offset))
    }

    override fun startClip(clip: Clip) {
        startPlayer(ClipPlayerFragment.newInstance(clip))
    }

    override fun startOfflineVideo(video: OfflineVideo) {
        startPlayer(OfflinePlayerFragment.newInstance(video))
    }

    override fun viewChannel(id: String?, login: String?, name: String?, channelLogo: String?, updateLocal: Boolean, streamId: String?) {
        fragNavController.pushFragment(ChannelPagerFragment.newInstance(id, login, name, channelLogo, updateLocal, streamId))
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

    private fun startPlayer(fragment: BasePlayerFragment) {
//        if (playerFragment == null) {
        playerFragment = fragment
        supportFragmentManager.beginTransaction()
                .replace(R.id.playerContainer, fragment).commit()
        viewModel.onPlayerStarted()
    }

    fun closePlayer() {
        supportFragmentManager.beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .remove(supportFragmentManager.findFragmentById(R.id.playerContainer)!!)
                .commit()
        playerFragment = null
        viewModel.onPlayerClosed()
    }

    private fun restorePlayerFragment() {
        if (viewModel.isPlayerOpened) {
            if (playerFragment == null) {
                playerFragment = supportFragmentManager.findFragmentById(R.id.playerContainer) as BasePlayerFragment?
            } else {
                if (playerFragment?.slidingLayout?.secondView?.isVisible == false && prefs.getString(C.PLAYER_BACKGROUND_PLAYBACK, "0") == "0") {
                    playerFragment?.maximize()
                }
            }
        }
    }

    private fun hideNavigationBar() {
        navBarContainer.gone()
    }

    private fun showNavigationBar() {
        navBarContainer.visible()
    }

    fun popFragment() {
        fragNavController.popFragment()
    }

    fun openSearch() {
        fragNavController.pushFragment(SearchFragment())
    }

    fun openTagSearch(getGameTags: Boolean = false, gameId: String? = null, gameName: String? = null) {
        fragNavController.pushFragment(BaseTagSearchFragment.newInstance(getGameTags, gameId, gameName))
    }

    private fun initNavigation() {
        fragNavController.apply {
            rootFragments = listOf(
                GamesFragment(),
                TopFragment(),
                if (prefs.getBoolean(C.UI_FOLLOWPAGER, true)) {
                    FollowPagerFragment.newInstance(prefs.getString(C.UI_FOLLOW_DEFAULT_PAGE, "0")?.toInt(), !Account.get(this@MainActivity).gqlToken.isNullOrBlank())
                } else {
                    FollowMediaFragment.newInstance(prefs.getString(C.UI_FOLLOW_DEFAULT_PAGE, "0")?.toInt(), !Account.get(this@MainActivity).gqlToken.isNullOrBlank())
                },
                if (prefs.getBoolean(C.UI_SAVEDPAGER, true)) {
                    SavedPagerFragment.newInstance(prefs.getString(C.UI_SAVED_DEFAULT_PAGE, "0")?.toInt())
                } else {
                    SavedMediaFragment.newInstance(prefs.getString(C.UI_SAVED_DEFAULT_PAGE, "0")?.toInt())
                }
            )
            fragmentHideStrategy = FragNavController.DETACH_ON_NAVIGATE_HIDE_ON_SWITCH
            transactionListener = object : FragNavController.TransactionListener {
                override fun onFragmentTransaction(fragment: Fragment?, transactionType: FragNavController.TransactionType) {
                }

                override fun onTabTransaction(fragment: Fragment?, index: Int) {
                }
            }
        }
        navBar.apply {
            setOnNavigationItemSelectedListener {
                val index = when (it.itemId) {
                    R.id.fragment_games -> INDEX_GAMES
                    R.id.fragment_top -> INDEX_TOP
                    R.id.fragment_follow -> INDEX_FOLLOWED
                    R.id.fragment_downloads -> INDEX_DOWNLOADS
                    else -> throw IllegalArgumentException()
                }
                fragNavController.switchTab(index)
                true
            }

            setOnNavigationItemReselectedListener {
                val currentFragment = fragNavController.currentFrag
                when (it.itemId) {
                    R.id.fragment_games -> {
                        if (currentFragment is GamesFragment && currentFragment.arguments?.getStringArray(C.TAGS).isNullOrEmpty()) {
                            currentFragment.scrollToTop()
                        } else {
                            fragNavController.clearStack()
                        }
                    }
                    else -> if (fragNavController.isRootFragment) {
                        if (currentFragment is Scrollable) {
                            currentFragment.scrollToTop()
                        }
                    } else {
                        fragNavController.clearStack()
                    }
                }
            }
        }
    }
}
