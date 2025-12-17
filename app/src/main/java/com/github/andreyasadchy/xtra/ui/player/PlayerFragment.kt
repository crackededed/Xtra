package com.github.andreyasadchy.xtra.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.trackPipAnimationHintView
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TimeBar
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding
import com.github.andreyasadchy.xtra.model.ui.Clip
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.common.RadioButtonDialogFragment
import com.github.andreyasadchy.xtra.ui.download.DownloadDialog
import com.github.andreyasadchy.xtra.ui.game.GameMediaFragmentDirections
import com.github.andreyasadchy.xtra.ui.game.GamePagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.disable
import com.github.andreyasadchy.xtra.util.enable
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.hideKeyboard
import com.github.andreyasadchy.xtra.util.isInPortraitOrientation
import com.github.andreyasadchy.xtra.util.isKeyboardShown
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(UnstableApi::class)
@AndroidEntryPoint
abstract class PlayerFragment : BaseNetworkFragment(), RadioButtonDialogFragment.OnSortOptionChanged, IntegrityDialog.CallbackListener {

    private var _binding: FragmentPlayerBinding? = null
    protected val binding get() = _binding!!
    protected val viewModel: PlayerViewModel by viewModels()
    protected var chatFragment: ChatFragment? = null

    protected var videoType: String? = null
    private var isPortrait = false
    var isMaximized = true
    private var isChatOpen = true
    private var isKeyboardShown = false
    private var resizeMode = 0
    private var chatWidthLandscape = 0

    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var isTap = false
    private var tapEventTime = 0L
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var statusBarSwipe = false
    private var chatStatusBarSwipe = false
    private var isAnimating = false
    private var moveAnimation: ViewPropertyAnimator? = null
    protected var useController = true
    protected var controllerAutoHide = true
    private var controllerHideOnTouch = true
    private val controllerHideAction = Runnable { if (view != null) hideController() }
    private var controllerIsAnimating = false
    private var controllerAnimation: ViewPropertyAnimator? = null
    private var backgroundColor: Int? = null
    private var backgroundVisible = false

    protected lateinit var prefs: SharedPreferences

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            minimize()
        }
    }

    open fun startStream(url: String?) {}
    open fun startVideo(url: String?, playbackPosition: Long?) {}
    open fun startClip(url: String?) {}
    open fun startOfflineVideo(url: String?, position: Long) {}
    open fun getCurrentPosition(): Long? = null
    open fun getCurrentSpeed(): Float? = null
    open fun getCurrentVolume(): Float? = null
    open fun playPause() {}
    open fun rewind() {}
    open fun fastForward() {}
    open fun seek(position: Long) {}
    open fun seekToLivePosition() {}
    open fun setPlaybackSpeed(speed: Float) {}
    open fun changeVolume(volume: Float) {}
    open fun updateProgress() {}
    open fun toggleAudioCompressor() {}
    open fun setSubtitlesButton() {}
    open fun toggleSubtitles(enabled: Boolean) {}
    open fun showPlaylistTags(mediaPlaylist: Boolean) {}
    open fun changeQuality(selectedQuality: String?) {}
    open fun startAudioOnly() {}
    open fun downloadVideo() {}
    open fun close() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        videoType = requireArguments().getString(KEY_TYPE)
        if (videoType == OFFLINE_VIDEO) {
            enableNetworkCheck = false
        }
        super.onCreate(savedInstanceState)
        val activity = requireActivity()
        prefs = activity.prefs()
        isPortrait = activity.isInPortraitOrientation
        activity.onBackPressedDispatcher.addCallback(this, backPressedCallback)
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
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
            val ignoreCutouts = prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
            val cornerPadding = prefs.getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = if (!isPortrait && ignoreCutouts) {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                } else {
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
                }
                if (isPortrait) {
                    slidingLayout.updatePadding(left = 0, top = insets.top, right = 0)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cornerPadding) {
                        val rootWindowInsets = view.rootView.rootWindowInsets
                        val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                        val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                        val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                        val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                        val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                        val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                        if (ignoreCutouts) {
                            slidingLayout.updatePadding(left = leftRadius, top = 0, right = rightRadius)
                        } else {
                            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                            slidingLayout.updatePadding(left = max(cutoutInsets.left, leftRadius), top = 0, right = max(cutoutInsets.right, rightRadius))
                        }
                    } else {
                        if (ignoreCutouts) {
                            slidingLayout.updatePadding(left = 0, top = 0, right = 0)
                        } else {
                            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                            slidingLayout.updatePadding(left = cutoutInsets.left, top = 0, right = cutoutInsets.right)
                        }
                    }
                }
                chatLayout.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        requireActivity().trackPipAnimationHintView(playerLayout)
                    }
                }
            }
            if (prefs.getBoolean(C.PLAYER_KEEP_SCREEN_ON_WHEN_PAUSED, false)) {
                view.keepScreenOn = true
            }
            if (isMaximized) {
                enableBackground()
            } else {
                disableBackground()
            }
            isChatOpen = prefs.getBoolean(C.KEY_CHAT_OPENED, true) && !prefs.getBoolean(C.CHAT_DISABLE, false)
            chatWidthLandscape = prefs.getInt(C.LANDSCAPE_CHAT_WIDTH, 0)
            resizeMode = prefs.getInt(C.ASPECT_RATIO_LANDSCAPE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
            aspectRatioFrameLayout.setAspectRatio(16f / 9f)
            initLayout()
            changePlayerMode()
            val viewConfiguration = ViewConfiguration.get(requireContext())
            val touchSlop = viewConfiguration.scaledTouchSlop
            val touchSlopRange = -touchSlop.toFloat()..touchSlop.toFloat()
            val longPressTimeout = ViewConfiguration.getLongPressTimeout()
            val moveFreely = prefs.getBoolean(C.PLAYER_MOVE_FREELY, false)
            val doubleTap = prefs.getBoolean(C.PLAYER_DOUBLETAP, true) && !prefs.getBoolean(C.CHAT_DISABLE, false)
            val controllerTapDetector = GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        return if (!doubleTap || isPortrait) {
                            val visible = playerControls.root.isVisible
                            if (visible) {
                                if (controllerHideOnTouch) {
                                    hideController()
                                }
                            } else {
                                showController()
                            }
                            if (!visible) {
                                updateProgress()
                            }
                            true
                        } else {
                            false
                        }
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        return if (doubleTap && !isPortrait) {
                            val visible = playerControls.root.isVisible
                            if (visible) {
                                if (controllerHideOnTouch) {
                                    hideController()
                                }
                            } else {
                                showController()
                            }
                            if (!visible) {
                                updateProgress()
                            }
                            true
                        } else {
                            false
                        }
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        return if (doubleTap && !isPortrait && isMaximized) {
                            if (chatLayout.isVisible) {
                                hideChat()
                            } else {
                                showChat()
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
            )

            fun downAction(event: MotionEvent) {
                moveAnimation?.cancel()
                isTap = true
                tapEventTime = event.eventTime
                if (isMaximized) {
                    if (playerControls.root.isVisible) {
                        playerControls.root.dispatchTouchEvent(event)
                    } else {
                        controllerTapDetector.onTouchEvent(event)
                    }
                } else {
                    velocityTracker?.clear()
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain()
                    }
                    velocityTracker?.addMovement(
                        MotionEvent.obtain(
                            event.downTime,
                            event.eventTime,
                            event.action,
                            slidingLayout.translationX,
                            slidingLayout.translationY,
                            event.metaState
                        )
                    )
                    startTranslationX = slidingLayout.translationX
                    startTranslationY = slidingLayout.translationY
                }
            }

            fun upAction(event: MotionEvent) {
                if (isMaximized) {
                    if (playerControls.progressBar.isPressed) {
                        playerControls.root.dispatchTouchEvent(event)
                    } else {
                        if (slidingLayout.translationY in touchSlopRange) {
                            if (playerControls.root.isVisible) {
                                playerControls.root.dispatchTouchEvent(event)
                            } else {
                                controllerTapDetector.onTouchEvent(event)
                            }
                        }
                        val minimizeThreshold = slidingLayout.height / 5
                        if (slidingLayout.translationY < minimizeThreshold) {
                            moveAnimation = slidingLayout.animate().apply {
                                translationX(0f)
                                translationY(0f)
                                setDuration(250L)
                                setListener(
                                    object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            setListener(null)
                                            if (slidingLayout.translationY < touchSlop) {
                                                enableBackground()
                                            }
                                        }
                                    }
                                )
                                start()
                            }
                        } else {
                            minimize()
                        }
                    }
                } else {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    when {
                        xVelocity > 1500 -> {
                            isAnimating = true
                            slidingLayout.animate().apply {
                                translationX(slidingLayout.translationX + (slidingLayout.width * slidingLayout.scaleX))
                                setDuration(250L)
                                start()
                            }
                            close()
                            (activity as? MainActivity)?.closePlayer()
                        }
                        xVelocity < -1500 -> {
                            isAnimating = true
                            slidingLayout.animate().apply {
                                translationX(slidingLayout.translationX - (slidingLayout.width * slidingLayout.scaleX))
                                setDuration(250L)
                                start()
                            }
                            close()
                            (activity as? MainActivity)?.closePlayer()
                        }
                        else -> {
                            if (isTap && (event.eventTime - tapEventTime) < longPressTimeout) {
                                maximize()
                            } else {
                                if (moveFreely) {
                                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                    val scaledXDiff = (slidingLayout.width * (1f - slidingLayout.scaleX)) / 2
                                    val scaledYDiff = (slidingLayout.height * (1f - slidingLayout.scaleY)) / 2
                                    val minX = 0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + (insets?.left ?: 0)
                                    val minY = 0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + (insets?.top ?: 0)
                                    val maxX = 0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + slidingLayout.width - (playerLayout.width * slidingLayout.scaleX) - (insets?.right ?: 0)
                                    val maxY = 0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + slidingLayout.height - (playerLayout.height * slidingLayout.scaleY) - (insets?.bottom ?: 0)
                                    val newX = when {
                                        slidingLayout.translationX < minX -> minX
                                        slidingLayout.translationX > maxX -> maxX
                                        else -> null
                                    }
                                    val newY = when {
                                        slidingLayout.translationY < minY -> minY
                                        slidingLayout.translationY > maxY -> maxY
                                        else -> null
                                    }
                                    if (newX != null || newY != null) {
                                        moveAnimation = slidingLayout.animate().apply {
                                            newX?.let { translationX(it) }
                                            newY?.let { translationY(it) }
                                            setDuration(250L)
                                            start()
                                        }
                                    }
                                } else {
                                    val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                                    val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                    val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                                    val scaledXDiff = (slidingLayout.width * (1f - slidingLayout.scaleX)) / 2
                                    val scaledYDiff = (slidingLayout.height * (1f - slidingLayout.scaleY)) / 2
                                    val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                                    val newX = slidingLayout.width - (insets?.right ?: 0) - (playerLayout.width * slidingLayout.scaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * slidingLayout.scaleX)
                                    val newY = slidingLayout.height - navBarHeight - (playerLayout.height * slidingLayout.scaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * slidingLayout.scaleY)
                                    moveAnimation = slidingLayout.animate().apply {
                                        translationX(0f - scaledXDiff - ((insets?.left ?: 0) * slidingLayout.scaleX) + newX)
                                        translationY(0f - scaledYDiff - ((insets?.top ?: 0) * slidingLayout.scaleY) + newY)
                                        setDuration(250L)
                                        start()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            dragView.setOnTouchListener { _, event ->
                if (!isAnimating) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointerId = event.getPointerId(0)
                            val x = event.x
                            val y = event.y
                            lastX = x * slidingLayout.scaleX
                            lastY = y * slidingLayout.scaleY
                            statusBarSwipe = !isPortrait && y <= 100
                            downAction(event)
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            if (activePointerId == -1) {
                                val pointerIndex = event.actionIndex
                                val pointerId = event.getPointerId(pointerIndex)
                                val x = event.getX(pointerIndex)
                                val y = event.getY(pointerIndex)
                                if (x in 0f..playerLayout.width.toFloat() && y in 0f..playerLayout.height.toFloat()) {
                                    activePointerId = pointerId
                                    lastX = x * slidingLayout.scaleX
                                    lastY = y * slidingLayout.scaleY
                                    statusBarSwipe = !isPortrait && y <= 100
                                    downAction(event)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isMaximized) {
                                playerControls.root.dispatchTouchEvent(event)
                                if (!playerControls.progressBar.isPressed && !statusBarSwipe && activePointerId != -1) {
                                    val pointerIndex = event.findPointerIndex(activePointerId)
                                    if (pointerIndex != -1) {
                                        val y = event.getY(pointerIndex)
                                        val translationY = y - lastY
                                        if (slidingLayout.translationY + translationY < 0) {
                                            slidingLayout.translationY = 0f
                                            lastY = y
                                        } else {
                                            slidingLayout.translationY += translationY
                                            lastY = y - translationY
                                        }
                                        if (slidingLayout.translationY < touchSlop) {
                                            if (!backgroundVisible) {
                                                enableBackground()
                                            }
                                        } else {
                                            if (backgroundVisible) {
                                                disableBackground()
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (activePointerId != -1) {
                                    val pointerIndex = event.findPointerIndex(activePointerId)
                                    if (pointerIndex != -1) {
                                        val x = event.getX(pointerIndex) * slidingLayout.scaleX
                                        val y = event.getY(pointerIndex) * slidingLayout.scaleY
                                        val translationX = x - lastX
                                        val translationY = y - lastY
                                        slidingLayout.translationX += translationX
                                        if (moveFreely) {
                                            slidingLayout.translationY += translationY
                                        }
                                        lastX = x - translationX
                                        lastY = y - translationY
                                        velocityTracker?.addMovement(
                                            MotionEvent.obtain(
                                                event.downTime,
                                                event.eventTime,
                                                event.action,
                                                slidingLayout.translationX,
                                                slidingLayout.translationY,
                                                event.metaState
                                            )
                                        )
                                        if (isTap && ((startTranslationX - slidingLayout.translationX) !in touchSlopRange || (startTranslationY - slidingLayout.translationY) !in touchSlopRange)) {
                                            isTap = false
                                        }
                                    }
                                }
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val pointerIndex = event.actionIndex
                            val pointerId = event.getPointerId(pointerIndex)
                            if (pointerId == activePointerId) {
                                var newId = -1
                                for (i in 0 until event.pointerCount) {
                                    val id = event.getPointerId(i)
                                    if (id != activePointerId) {
                                        val x = event.getX(i)
                                        val y = event.getY(i)
                                        if (x in 0f..playerLayout.width.toFloat() && y in 0f..playerLayout.height.toFloat()) {
                                            newId = id
                                            lastX = x * slidingLayout.scaleX
                                            lastY = y * slidingLayout.scaleY
                                            break
                                        }
                                    }
                                }
                                if (newId == -1) {
                                    upAction(event)
                                }
                                activePointerId = newId
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> upAction(event)
                    }
                }
                true
            }
            chatTouchView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        chatStatusBarSwipe = !isPortrait && event.y <= 100
                        chatLinearLayout.dispatchTouchEvent(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (chatStatusBarSwipe) {
                            chatLinearLayout.dispatchTouchEvent(
                                MotionEvent.obtain(event).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                }
                            )
                        } else {
                            chatLinearLayout.dispatchTouchEvent(event)
                        }
                    }
                    else -> chatLinearLayout.dispatchTouchEvent(event)
                }
                true
            }
            with(playerControls) {
                root.setOnTouchListener { _, event ->
                    controllerTapDetector.onTouchEvent(event)
                }
                playPause.setOnClickListener { playPause() }
                rewind.text = ((prefs.getString(C.PLAYER_REWIND, "10000")?.toLongOrNull() ?: 10000) / 1000).toString()
                rewind.setOnClickListener { rewind() }
                fastForward.text = ((prefs.getString(C.PLAYER_FORWARD, "10000")?.toLongOrNull() ?: 10000) / 1000).toString()
                fastForward.setOnClickListener { fastForward() }
                progressBar.addListener(
                    object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                            binding.playerControls.position.text = DateUtils.formatElapsedTime(position / 1000)
                            binding.playerControls.root.removeCallbacks(controllerHideAction)
                        }

                        override fun onScrubMove(timeBar: TimeBar, position: Long) {
                            binding.playerControls.position.text = DateUtils.formatElapsedTime(position / 1000)
                        }

                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                            if (!canceled) {
                                seek(position)
                            } else {
                                if (controllerAutoHide && controllerHideOnTouch) {
                                    binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                                }
                            }
                        }
                    }
                )
                position.text = DateUtils.formatElapsedTime(0)
                duration.text = DateUtils.formatElapsedTime(0)
                subtitleView.setUserDefaultStyle()
                subtitleView.setUserDefaultTextSize()
                val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
                val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                val displayName = if (channelLogin != null && !channelLogin.equals(channelName, true)) {
                    when (prefs.getString(C.UI_NAME_DISPLAY, "0")) {
                        "0" -> "${channelName}(${channelLogin})"
                        "1" -> channelName
                        else -> channelLogin
                    }
                } else {
                    channelName
                }
                if (prefs.getBoolean(C.PLAYER_CHANNEL, true)) {
                    channel.visible()
                    channel.text = displayName
                    channel.setOnClickListener {
                        findNavController().navigate(
                            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                                channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                            )
                        )
                        minimize()
                    }
                }
                val titleText = requireArguments().getString(KEY_TITLE)
                if (!titleText.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
                    title.visible()
                    title.text = titleText
                }
                val gameName = requireArguments().getString(KEY_GAME_NAME)
                if (!gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
                    category.visible()
                    category.text = gameName
                    category.setOnClickListener {
                        findNavController().navigate(
                            if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                                GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                    gameId = requireArguments().getString(KEY_GAME_ID),
                                    gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                    gameName = gameName
                                )
                            } else {
                                GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                    gameId = requireArguments().getString(KEY_GAME_ID),
                                    gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                                    gameName = gameName
                                )
                            }
                        )
                        minimize()
                    }
                }
                if (prefs.getBoolean(C.PLAYER_MINIMIZE, true)) {
                    minimize.visible()
                    minimize.setOnClickListener { minimize() }
                }
                if (prefs.getBoolean(C.PLAYER_VOLUMEBUTTON, true)) {
                    volume.visible()
                    volume.setOnClickListener { showVolumeDialog() }
                }
                if (prefs.getBoolean(C.PLAYER_SETTINGS, true)) {
                    quality.visible()
                    quality.setOnClickListener { showQualityDialog() }
                }
                if (prefs.getBoolean(C.PLAYER_MODE, false)) {
                    audioOnly.visible()
                    audioOnly.setOnClickListener {
                        if (viewModel.quality == AUDIO_ONLY_QUALITY) {
                            changeQuality(viewModel.previousQuality)
                        } else {
                            changeQuality(AUDIO_ONLY_QUALITY)
                        }
                        changePlayerMode()
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(C.PLAYER_AUDIO_COMPRESSOR_BUTTON, true)) {
                    audioCompressor.visible()
                    if (prefs.getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                        audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_on_24dp)
                    } else {
                        audioCompressor.setImageResource(R.drawable.baseline_audio_compressor_off_24dp)
                    }
                    audioCompressor.setOnClickListener {
                        toggleAudioCompressor()
                    }
                }
                if (prefs.getBoolean(C.PLAYER_MENU, true)) {
                    menu.visible()
                    menu.setOnClickListener {
                        PlayerSettingsDialog.newInstance(
                            videoType = videoType,
                            speedText = getCurrentSpeed()?.let { speed ->
                                prefs.getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")
                                    ?.split("\n")?.find { it == speed.toString() }
                            },
                            vodGames = !viewModel.gamesList.value.isNullOrEmpty()
                        ).show(childFragmentManager, "closeOnPip")
                    }
                }
                if (videoType == STREAM) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.streamResult.collectLatest {
                                if (it != null) {
                                    startStream(it)
                                    viewModel.streamResult.value = null
                                }
                            }
                        }
                    }
                    if (!requireContext().tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                    ) {
                        if (prefs.getBoolean(C.PLAYER_CHATBARTOGGLE, false) && !prefs.getBoolean(C.CHAT_DISABLE, false)) {
                            toggleChatInput.visible()
                            toggleChatInput.setOnClickListener { toggleChatBar() }
                        }
                        slidingLayout.viewTreeObserver.addOnGlobalLayoutListener {
                            if (slidingLayout.isKeyboardShown) {
                                if (!isKeyboardShown) {
                                    isKeyboardShown = true
                                    if (!isPortrait) {
                                        chatLayout.updateLayoutParams { width = (slidingLayout.width / 1.8f).toInt() }
                                        showStatusBar()
                                    }
                                }
                            } else {
                                if (isKeyboardShown) {
                                    isKeyboardShown = false
                                    chatLayout.clearFocus()
                                    if (!isPortrait) {
                                        chatLayout.updateLayoutParams { width = chatWidthLandscape }
                                        if (isMaximized) {
                                            hideStatusBar()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.stream.collectLatest { stream ->
                                if (stream != null) {
                                    stream.id?.let { chatFragment?.updateStreamId(it) }
                                    if (prefs.getBoolean(C.CHAT_DISABLE, false) ||
                                        !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                                        viewersText.text.isNullOrBlank()
                                    ) {
                                        updateViewerCount(stream.viewerCount)
                                    }
                                    if (prefs.getBoolean(C.CHAT_DISABLE, false) ||
                                        !prefs.getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                                        title.text.isNullOrBlank() ||
                                        category.text.isNullOrBlank()
                                    ) {
                                        updateStreamInfo(stream.title, stream.gameId, stream.gameSlug, stream.gameName)
                                    }
                                    if (prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true) &&
                                        !uptimeLayout.isVisible
                                    ) {
                                        stream.startedAt?.let { date ->
                                            TwitchApiHelper.parseIso8601DateUTC(date)?.let { startedAtMs ->
                                                updateUptime(startedAtMs)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (prefs.getBoolean(C.PLAYER_RESTART, true)) {
                        restart.visible()
                        restart.setOnClickListener { restartPlayer() }
                    }
                    if (prefs.getBoolean(C.PLAYER_SEEKLIVE, false)) {
                        seekLive.visible()
                        seekLive.setOnClickListener { seekToLivePosition() }
                    }
                    if (prefs.getBoolean(C.PLAYER_VIEWERLIST, false)) {
                        viewersLayout.setOnClickListener { openViewerList() }
                    }
                    if (prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
                        requireArguments().getString(KEY_STARTED_AT)?.let {
                            TwitchApiHelper.parseIso8601DateUTC(it)?.let { startedAtMs ->
                                updateUptime(startedAtMs)
                            }
                        }
                    }
                    rewind.gone()
                    fastForward.gone()
                    position.gone()
                    progressBar.gone()
                    duration.gone()
                    updateStreamInfo(
                        requireArguments().getString(KEY_TITLE),
                        requireArguments().getString(KEY_GAME_ID),
                        requireArguments().getString(KEY_GAME_SLUG),
                        requireArguments().getString(KEY_GAME_NAME)
                    )
                    updateViewerCount(requireArguments().getInt(KEY_VIEWER_COUNT).takeIf { it != -1 })
                } else {
                    if (prefs.getBoolean(C.PLAYER_SPEEDBUTTON, true)) {
                        speed.visible()
                        speed.setOnClickListener { showSpeedDialog() }
                    }
                }
                if (videoType == VIDEO) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.videoResult.collectLatest {
                                if (it != null) {
                                    startVideo(it, viewModel.playbackPosition)
                                    viewModel.videoResult.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.savedPosition.collectLatest {
                                if (it != null) {
                                    playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, it)
                                    viewModel.savedPosition.value = null
                                }
                            }
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.isBookmarked.collectLatest {
                                    if (it != null) {
                                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setBookmarkText(it)
                                        viewModel.isBookmarked.value = null
                                    }
                                }
                            }
                        }
                    }
                    if (!requireArguments().getString(KEY_VIDEO_ID).isNullOrBlank() && (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false))) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.gamesList.collectLatest { list ->
                                    if (!list.isNullOrEmpty()) {
                                        if (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true)) {
                                            vodGames.visible()
                                            vodGames.setOnClickListener { showVodGames() }
                                        }
                                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setVodGames()
                                    }
                                }
                            }
                        }
                    }
                }
                if (videoType == CLIP) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.clipUrls.collectLatest { map ->
                                if (map != null) {
                                    val supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")?.split(',') ?: emptyList()
                                    val filtered = map.filterNot {
                                        it.key.second?.substringBefore('.').let { codec ->
                                            (codec == "av01" && !supportedCodecs.contains("av1")) || ((codec == "hev1" || codec == "hvc1") && !supportedCodecs.contains("h265"))
                                        }
                                    }
                                    val hideCodecs = filtered.all {
                                        it.key.second?.substringBefore('.').let { codec ->
                                            codec == "avc1" || codec == "mp4a" || codec.isNullOrBlank()
                                        }
                                    }
                                    val map = mutableMapOf<String, Pair<String, String?>>()
                                    filtered.forEach {
                                        val quality = it.key.first.let { quality ->
                                            val quality = if (quality == "source") {
                                                requireContext().getString(R.string.source)
                                            } else {
                                                quality
                                            }
                                            if (hideCodecs) {
                                                quality
                                            } else {
                                                val codec = it.key.second?.substringBefore('.').let { codec ->
                                                    when {
                                                        codec == "av01" -> "AV1"
                                                        codec == "hev1" || codec == "hvc1" -> "H.265"
                                                        codec == "avc1" || codec.isNullOrBlank() -> "H.264"
                                                        else -> it
                                                    }
                                                }
                                                "$quality $codec"
                                            }
                                        }
                                        map[it.key.first] = Pair(quality, it.value)
                                    }
                                    map.put(AUDIO_ONLY_QUALITY, Pair(requireContext().getString(R.string.audio_only), null))
                                    viewModel.qualities = map.toList()
                                        .sortedByDescending {
                                            it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                        }
                                        .sortedByDescending {
                                            it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                                        }
                                        .sortedByDescending {
                                            it.first == "source"
                                        }
                                        .toMap()
                                    setDefaultQuality()
                                    changePlayerMode()
                                    val quality = viewModel.qualities.entries.find { it.key == viewModel.quality }
                                    (quality?.value?.second ?: viewModel.qualities.values.firstOrNull()?.second)?.let {
                                        startClip(it)
                                    }
                                    viewModel.clipUrls.value = null
                                }
                            }
                        }
                    }
                    val videoId = requireArguments().getString(KEY_VIDEO_ID)
                    if (!videoId.isNullOrBlank()) {
                        binding.watchVideo.visible()
                        binding.watchVideo.setOnClickListener {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val offset = requireArguments().getInt(KEY_VOD_OFFSET).takeIf { it != -1 }?.let {
                                    (it * 1000) + (getCurrentPosition() ?: 0)
                                } ?: 0
                                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                                    videoId.toLongOrNull()?.let { id ->
                                        viewModel.savePosition(id, offset)
                                    }
                                }
                                (requireActivity() as MainActivity).startVideo(
                                    Video(
                                        id = videoId,
                                        channelId = requireArguments().getString(KEY_CHANNEL_ID),
                                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                                        profileImageUrl = requireArguments().getString(KEY_PROFILE_IMAGE_URL),
                                        animatedPreviewURL = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW)
                                    ),
                                    offset,
                                    true
                                )
                            }
                        }
                    }
                } else {
                    if (prefs.getBoolean(C.PLAYER_SLEEP, false)) {
                        sleepTimer.visible()
                        sleepTimer.setOnClickListener { showSleepTimerDialog() }
                    }
                }
                if (videoType == OFFLINE_VIDEO) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.savedOfflineVideoPosition.collectLatest {
                                if (it != null) {
                                    val url = requireArguments().getString(KEY_URL)
                                    viewModel.qualities = mapOf(
                                        "source" to Pair(requireContext().getString(R.string.source), url),
                                        AUDIO_ONLY_QUALITY to Pair(requireContext().getString(R.string.audio_only), null)
                                    )
                                    setDefaultQuality()
                                    changePlayerMode()
                                    startOfflineVideo(url, it)
                                    viewModel.savedOfflineVideoPosition.value = null
                                }
                            }
                        }
                    }
                } else {
                    quality.disable()
                    download.disable()
                    audioOnly.disable()
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.loaded.collectLatest {
                                if (it) {
                                    quality.enable()
                                    download.enable()
                                    audioOnly.enable()
                                    setQualityText()
                                }
                            }
                        }
                    }
                    if (prefs.getBoolean(C.PLAYER_DOWNLOAD, false)) {
                        download.visible()
                        download.setOnClickListener { showDownloadDialog() }
                    }
                    val setting = prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
                    if (prefs.getBoolean(C.PLAYER_FOLLOW, false) && (setting == 0 || setting == 1)) {
                        follow.visible()
                        follow.setOnClickListener {
                            viewModel.isFollowing.value?.let {
                                if (it) {
                                    requireContext().getAlertDialogBuilder()
                                        .setMessage(requireContext().getString(R.string.unfollow_channel, displayName))
                                        .setNegativeButton(getString(R.string.no), null)
                                        .setPositiveButton(getString(R.string.yes)) { _, _ ->
                                            viewModel.deleteFollowChannel(
                                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                                requireArguments().getString(KEY_CHANNEL_ID),
                                                setting,
                                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                                requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                            )
                                        }
                                        .show()
                                } else {
                                    viewModel.saveFollowChannel(
                                        requireContext().tokenPrefs().getString(C.USER_ID, null),
                                        requireArguments().getString(KEY_CHANNEL_ID),
                                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                                        requireArguments().getString(KEY_CHANNEL_NAME),
                                        setting,
                                        requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                                        requireArguments().getString(KEY_STARTED_AT),
                                        requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                        TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                                    )
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.isFollowing.collectLatest {
                                    if (it != null) {
                                        if (it) {
                                            follow.setImageResource(R.drawable.baseline_favorite_black_24)
                                        } else {
                                            follow.setImageResource(R.drawable.baseline_favorite_border_black_24)
                                        }
                                    }
                                }
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.follow.collectLatest { pair ->
                                    if (pair != null) {
                                        val following = pair.first
                                        val errorMessage = pair.second
                                        if (!errorMessage.isNullOrBlank()) {
                                            requireContext().shortToast(errorMessage)
                                        } else {
                                            if (following) {
                                                requireContext().shortToast(requireContext().getString(R.string.now_following, displayName))
                                            } else {
                                                requireContext().shortToast(requireContext().getString(R.string.unfollowed, displayName))
                                            }
                                        }
                                        viewModel.follow.value = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val currentChatFragment = (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) as? ChatFragment)
            if (currentChatFragment != null) {
                chatFragment = currentChatFragment
            } else {
                val fragment = when (videoType) {
                    STREAM -> ChatFragment.newInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_CHANNEL_NAME),
                        requireArguments().getString(KEY_STREAM_ID)
                    )
                    VIDEO -> ChatFragment.newInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_VIDEO_ID),
                        0
                    )
                    CLIP -> ChatFragment.newInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_VIDEO_ID),
                        requireArguments().getInt(KEY_VOD_OFFSET).takeIf { it != -1 }
                    )
                    OFFLINE_VIDEO -> ChatFragment.newLocalInstance(
                        requireArguments().getString(KEY_CHANNEL_ID),
                        requireArguments().getString(KEY_CHANNEL_LOGIN),
                        requireArguments().getString(KEY_CHAT_URL)
                    )
                    else -> null
                }
                if (fragment != null) {
                    childFragmentManager.beginTransaction().replace(R.id.chatFragmentContainer, fragment).commit()
                }
                chatFragment = fragment
            }
        }
    }

    private fun initLayout() {
        with(binding) {
            if (isPortrait) {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener(null)
                showStatusBar()
                playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    marginEnd = 0
                }
                chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    gravity = Gravity.BOTTOM
                }
                if (isMaximized) {
                    chatLayout.visible()
                } else {
                    chatLayout.gone()
                    val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                    slidingLayout.scaleX = minimizedScaleX
                    slidingLayout.scaleY = minimizedScaleY
                    slidingLayout.doOnPreDraw {
                        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                        val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                        val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                        val playerHeight = (slidingLayout.width / (16f / 9f)).toInt()
                        val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                        val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                        val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                        val newX = slidingLayout.width - (insets?.right ?: 0) - (slidingLayout.width * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                        val newY = slidingLayout.height - navBarHeight - (playerHeight * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                        slidingLayout.translationX = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                        slidingLayout.translationY = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                    }
                }
                aspectRatioFrameLayout.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                aspectRatioFrameLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    gravity = Gravity.NO_GRAVITY
                }
                playerLayout.isPortrait = true
                chatLayout.isPortrait = true
                with(playerControls) {
                    if (prefs.getBoolean(C.PLAYER_FULLSCREEN, true)) {
                        fullscreen.visible()
                        fullscreen.setImageResource(R.drawable.baseline_fullscreen_black_24)
                        fullscreen.setOnClickListener {
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                    aspectRatio.gone()
                    toggleChat.gone()
                }
            } else {
                requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener {
                    if (!isKeyboardShown && isMaximized && activity != null) {
                        hideStatusBar()
                    }
                }
                if (isMaximized) {
                    hideStatusBar()
                    val chatWidth = if (isChatOpen) chatWidthLandscape else 0
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        marginEnd = chatWidth
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidthLandscape
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.END
                    }
                    if (isChatOpen) {
                        chatLayout.visible()
                        if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
                            requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                                recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
                            }
                        }
                    } else {
                        chatLayout.gone()
                    }
                } else {
                    showStatusBar()
                    playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        marginEnd = 0
                    }
                    chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = chatWidthLandscape
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.END
                    }
                    chatLayout.gone()
                    val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                    slidingLayout.scaleX = minimizedScaleX
                    slidingLayout.scaleY = minimizedScaleY
                    slidingLayout.doOnPreDraw {
                        val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                        val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                        val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                        val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                        val playerWidth = slidingLayout.width - getHorizontalInsets(windowInsets)
                        val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                        val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                        val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                        val newX = slidingLayout.width - (insets?.right ?: 0) - (playerWidth * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                        val newY = slidingLayout.height - navBarHeight - (slidingLayout.height * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                        slidingLayout.translationX = 0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX
                        slidingLayout.translationY = 0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY
                    }
                }
                aspectRatioFrameLayout.resizeMode = resizeMode
                aspectRatioFrameLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                    gravity = Gravity.CENTER
                }
                playerLayout.isPortrait = false
                chatLayout.isPortrait = false
                with(playerControls) {
                    if (prefs.getBoolean(C.PLAYER_FULLSCREEN, true)) {
                        fullscreen.visible()
                        fullscreen.setImageResource(R.drawable.baseline_fullscreen_exit_black_24)
                        fullscreen.setOnClickListener {
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                    if (prefs.getBoolean(C.PLAYER_ASPECT, true)) {
                        aspectRatio.visible()
                        aspectRatio.setOnClickListener { setResizeMode() }
                    }
                    if (prefs.getBoolean(C.PLAYER_CHATTOGGLE, true) && !prefs.getBoolean(C.CHAT_DISABLE, false)) {
                        toggleChat.visible()
                        if (isChatOpen) {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                            toggleChat.setOnClickListener { hideChat() }
                        } else {
                            toggleChat.setImageResource(R.drawable.baseline_speaker_notes_black_24)
                            toggleChat.setOnClickListener { showChat() }
                        }
                    }
                }
            }
        }
    }

    fun setResizeMode() {
        resizeMode = (resizeMode + 1).let { if (it < 5) it else 0 }
        binding.aspectRatioFrameLayout.resizeMode = resizeMode
        prefs.edit { putInt(C.ASPECT_RATIO_LANDSCAPE, resizeMode) }
    }

    fun showSleepTimerDialog() {
        if (requireContext().prefs().getBoolean(C.SLEEP_TIMER_USE_TIME_PICKER, false)) {
            if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
                requireContext().getAlertDialogBuilder()
                    .setMessage(getString(R.string.stop_sleep_timer_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        onSleepTimerChanged(-1L, 0, 0, requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false))
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
            } else {
                val savedValue = requireContext().prefs().getInt(C.SLEEP_TIMER_TIME, 15)
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                    .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                    .setHour(savedValue / 60)
                    .setMinute(savedValue % 60)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    val minutes = TwitchApiHelper.getMinutesLeft(picker.hour, picker.minute)
                    onSleepTimerChanged(minutes * 60_000L, minutes / 60, minutes % 60, requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false))
                    requireContext().prefs().edit {
                        putInt(C.SLEEP_TIMER_TIME, picker.hour * 60 + picker.minute)
                    }
                }
                picker.show(childFragmentManager, null)
            }
        } else {
            SleepTimerDialog.newInstance((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0).show(childFragmentManager, null)
        }
    }

    fun showQualityDialog() {
        if (viewModel.qualities.isNotEmpty()) {
            RadioButtonDialogFragment.newInstance(
                REQUEST_CODE_QUALITY,
                viewModel.qualities.values.map { it.first },
                null,
                viewModel.qualities.keys.indexOf(viewModel.quality)
            ).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showSpeedDialog() {
        val speed = getCurrentSpeed()
        if (speed != null) {
            val speedList = prefs.getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")
            if (speedList != null) {
                RadioButtonDialogFragment.newInstance(
                    REQUEST_CODE_SPEED,
                    speedList,
                    null,
                    speedList.indexOf(speed.toString())
                ).show(childFragmentManager, "closeOnPip")
            }
        }
    }

    fun showVolumeDialog() {
        PlayerVolumeDialog.newInstance(getCurrentVolume()).show(childFragmentManager, "closeOnPip")
    }

    fun getTranslateAllMessages(): Boolean? {
        return if (!requireArguments().getString(KEY_CHANNEL_ID).isNullOrBlank()) {
            chatFragment?.getTranslateAllMessages()
        } else null
    }

    fun saveTranslateAllMessagesUser() {
        requireArguments().getString(KEY_CHANNEL_ID)?.let {
            chatFragment?.toggleTranslateAllMessages(true)
            viewModel.saveTranslateAllMessagesUser(it)
        }
    }

    fun deleteTranslateAllMessagesUser() {
        requireArguments().getString(KEY_CHANNEL_ID)?.let {
            chatFragment?.toggleTranslateAllMessages(false)
            viewModel.deleteTranslateAllMessagesUser(it)
        }
    }

    fun toggleChatBar() {
        with(binding) {
            requireView().findViewById<LinearLayout>(R.id.messageView)?.let {
                if (it.isVisible) {
                    chatLayout.hideKeyboard()
                    chatLayout.clearFocus()
                    if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleEmoteMenu(false)
                    }
                    it.gone()
                    prefs.edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, false) }
                } else {
                    it.visible()
                    prefs.edit { putBoolean(C.KEY_CHAT_BAR_VISIBLE, true) }
                }
            }
        }
    }

    fun hideChat() {
        isChatOpen = false
        hideChatLayout()
        if (prefs.getBoolean(C.PLAYER_CHATTOGGLE, true)) {
            binding.playerControls.toggleChat.apply {
                visible()
                setImageResource(R.drawable.baseline_speaker_notes_black_24)
                setOnClickListener { showChat() }
            }
        }
        prefs.edit { putBoolean(C.KEY_CHAT_OPENED, false) }
    }

    fun showChat() {
        isChatOpen = true
        showChatLayout()
        if (prefs.getBoolean(C.PLAYER_CHATTOGGLE, true)) {
            binding.playerControls.toggleChat.apply {
                visible()
                setImageResource(R.drawable.baseline_speaker_notes_off_black_24)
                setOnClickListener { hideChat() }
            }
        }
        prefs.edit { putBoolean(C.KEY_CHAT_OPENED, true) }
        if (requireView().findViewById<Button>(R.id.btnDown)?.isVisible == false) {
            requireView().findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
                recyclerView.adapter?.itemCount?.let { recyclerView.scrollToPosition(it - 1) }
            }
        }
    }

    private fun hideChatLayout() {
        with(binding) {
            playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                marginEnd = 0
            }
            chatLayout.hideKeyboard()
            chatLayout.clearFocus()
            chatLayout.gone()
        }
    }

    private fun showChatLayout() {
        with(binding) {
            playerLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                marginEnd = chatWidthLandscape
            }
            chatLayout.updateLayoutParams<FrameLayout.LayoutParams> {
                width = chatWidthLandscape
                height = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.END
            }
            chatLayout.visible()
        }
    }

    fun setQualityText() {
        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setQuality(
            viewModel.qualities[viewModel.quality]?.first
        )
    }

    fun updateViewerCount(viewerCount: Int?) {
        with(binding.playerControls) {
            if (viewerCount != null) {
                viewersText.text = TwitchApiHelper.formatCount(viewerCount, requireContext().prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, true))
                if (prefs.getBoolean(C.PLAYER_VIEWERICON, true)) {
                    viewersIcon.visible()
                }
            } else {
                viewersText.text = null
                viewersIcon.gone()
            }
        }
    }

    fun updateLiveStatus(live: Boolean, serverTime: Long?, channelLogin: String?) {
        if (channelLogin == requireArguments().getString(KEY_CHANNEL_LOGIN)) {
            if (live) {
                restartPlayer()
            }
            updateUptime(serverTime?.times(1000))
        }
    }

    private fun updateUptime(uptimeMs: Long?) {
        with(binding.playerControls) {
            uptimeTimer.stop()
            if (uptimeMs != null && prefs.getBoolean(C.PLAYER_SHOW_UPTIME, true)) {
                uptimeLayout.visible()
                uptimeTimer.base = SystemClock.elapsedRealtime() + uptimeMs - System.currentTimeMillis()
                uptimeTimer.start()
                if (prefs.getBoolean(C.PLAYER_VIEWERICON, true)) {
                    uptimeIcon.visible()
                } else {
                    uptimeIcon.gone()
                }
            } else {
                uptimeLayout.gone()
            }
        }
    }

    fun updateStreamInfo(title: String?, gameId: String?, gameSlug: String?, gameName: String?) {
        binding.playerControls.title.apply {
            if (!title.isNullOrBlank() && prefs.getBoolean(C.PLAYER_TITLE, true)) {
                text = title.trim()
                visible()
            } else {
                text = null
                gone()
            }
        }
        binding.playerControls.category.apply {
            if (!gameName.isNullOrBlank() && prefs.getBoolean(C.PLAYER_CATEGORY, true)) {
                text = gameName
                visible()
                setOnClickListener {
                    findNavController().navigate(
                        if (prefs.getBoolean(C.UI_GAMEPAGER, true)) {
                            GamePagerFragmentDirections.actionGlobalGamePagerFragment(
                                gameId = gameId,
                                gameSlug = gameSlug,
                                gameName = gameName
                            )
                        } else {
                            GameMediaFragmentDirections.actionGlobalGameMediaFragment(
                                gameId = gameId,
                                gameSlug = gameSlug,
                                gameName = gameName
                            )
                        }
                    )
                    minimize()
                }
            } else {
                text = null
                gone()
            }
        }
    }

    fun restartPlayer() {
        if (viewModel.quality != CHAT_ONLY_QUALITY) {
            loadStream()
        }
    }

    fun openViewerList() {
        requireArguments().getString(KEY_CHANNEL_LOGIN)?.let { login ->
            PlayerViewerListDialog.newInstance(login).show(childFragmentManager, "closeOnPip")
        }
    }

    fun showVodGames() {
        viewModel.gamesList.value?.let {
            PlayerGamesDialog.newInstance(it).show(childFragmentManager, "closeOnPip")
        }
    }

    fun checkBookmark() {
        requireArguments().getString(KEY_VIDEO_ID)?.let { viewModel.checkBookmark(it) }
    }

    fun saveBookmark() {
        viewModel.saveBookmark(
            filesDir = requireContext().filesDir.path,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
            helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
            videoId = requireArguments().getString(KEY_VIDEO_ID),
            title = requireArguments().getString(KEY_TITLE),
            uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
            duration = requireArguments().getString(KEY_DURATION),
            type = requireArguments().getString(KEY_VIDEO_TYPE),
            animatedPreviewUrl = requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW),
            channelId = requireArguments().getString(KEY_CHANNEL_ID),
            channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
            channelName = requireArguments().getString(KEY_CHANNEL_NAME),
            channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
            thumbnail = requireArguments().getString(KEY_THUMBNAIL),
            gameId = requireArguments().getString(KEY_GAME_ID),
            gameSlug = requireArguments().getString(KEY_GAME_SLUG),
            gameName = requireArguments().getString(KEY_GAME_NAME),
        )
    }

    protected fun setDefaultQuality() {
        val defaultQuality = prefs.getString(C.PLAYER_DEFAULTQUALITY, "saved")?.substringBefore(" ")
        viewModel.quality = when (defaultQuality) {
            "saved" -> {
                val savedQuality = prefs.getString(C.PLAYER_QUALITY, "720p60")?.substringBefore(" ")
                when (savedQuality) {
                    AUTO_QUALITY -> viewModel.qualities.entries.find { it.key == AUTO_QUALITY }?.key
                    AUDIO_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == AUDIO_ONLY_QUALITY }?.key
                    CHAT_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == CHAT_ONLY_QUALITY }?.key
                    else -> findQuality(savedQuality)
                }
            }
            AUTO_QUALITY -> viewModel.qualities.entries.find { it.key == AUTO_QUALITY }?.key
            "Source" -> viewModel.qualities.entries.find { it.key != AUTO_QUALITY }?.key
            AUDIO_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == AUDIO_ONLY_QUALITY }?.key
            CHAT_ONLY_QUALITY -> viewModel.qualities.entries.find { it.key == CHAT_ONLY_QUALITY }?.key
            else -> findQuality(defaultQuality)
        } ?: viewModel.qualities.entries.firstOrNull()?.key
    }

    private fun findQuality(targetQualityString: String?): String? {
        val targetQuality = targetQualityString?.split("p")
        return targetQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
            val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
            val last = viewModel.qualities.keys.last { it != AUDIO_ONLY_QUALITY && it != CHAT_ONLY_QUALITY }
            viewModel.qualities.keys.find { qualityString ->
                val quality = qualityString.split("p")
                val resolution = quality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()
                val fps = quality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                resolution != null && ((targetResolution == resolution && targetFps >= fps) || targetResolution > resolution || qualityString == last)
            }
        }
    }

    fun changePlayerMode() {
        with(binding) {
            if (canEnterPictureInPicture()) {
                if (!controllerHideOnTouch && !controllerIsAnimating && controllerAutoHide && !binding.playerControls.progressBar.isPressed) {
                    playerControls.root.postDelayed(controllerHideAction, 3000)
                }
                controllerHideOnTouch = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
                }
            } else {
                controllerHideOnTouch = false
                showController(true)
                updateProgress()
                requireView().keepScreenOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
                }
            }
        }
    }

    protected fun showController(force: Boolean = false) {
        if (!controllerIsAnimating) {
            if (!binding.playerControls.root.isVisible) {
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                controllerAnimation = binding.playerControls.root.animate().apply {
                    alpha(1f)
                    setDuration(250L)
                    setListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: Animator) {
                                controllerIsAnimating = true
                                binding.playerControls.root.visible()
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                controllerIsAnimating = false
                                setListener(null)
                                if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                                    binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                                }
                            }
                        }
                    )
                    start()
                }
            } else {
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                    binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                }
            }
        } else {
            if (force) {
                controllerAnimation?.cancel()
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                binding.playerControls.root.alpha = 1f
                binding.playerControls.root.visible()
                if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                    binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                }
            }
        }
    }

    private fun hideController(force: Boolean = false) {
        if (!controllerIsAnimating && binding.playerControls.root.isVisible) {
            controllerAnimation = binding.playerControls.root.animate().apply {
                alpha(0f)
                setDuration(250L)
                setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            controllerIsAnimating = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            controllerIsAnimating = false
                            setListener(null)
                            binding.playerControls.root.gone()
                        }
                    }
                )
                start()
            }
        } else {
            if (force) {
                controllerAnimation?.cancel()
                binding.playerControls.root.alpha = 0f
                binding.playerControls.root.gone()
            }
        }
    }

    private fun showStatusBar() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun enableBackground() {
        backgroundVisible = true
        binding.playerBackground.setBackgroundColor(
            if (isPortrait) {
                backgroundColor ?: MaterialColors.getColor(binding.playerBackground, com.google.android.material.R.attr.colorSurface).also { backgroundColor = it }
            } else {
                Color.BLACK
            }
        )
        binding.playerBackground.isClickable = true
    }

    private fun disableBackground() {
        backgroundVisible = false
        binding.playerBackground.setBackgroundColor(Color.TRANSPARENT)
        binding.playerBackground.isClickable = false
    }

    private fun getHorizontalInsets(windowInsets: WindowInsetsCompat?): Int {
        return if (windowInsets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.getBoolean(C.PLAYER_ROUNDED_CORNER_PADDING, false)) {
                val rootWindowInsets = requireView().rootWindowInsets
                val topLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                val topRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                val bottomLeft = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
                val bottomRight = rootWindowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
                val leftRadius = max(topLeft?.radius ?: 0, bottomLeft?.radius ?: 0)
                val rightRadius = max(topRight?.radius ?: 0, bottomRight?.radius ?: 0)
                if (prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)) {
                    leftRadius + rightRadius
                } else {
                    val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    max(cutoutInsets.left, leftRadius) + max(cutoutInsets.right, rightRadius)
                }
            } else {
                if (prefs.getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)) {
                    0
                } else {
                    val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                    cutoutInsets.left + cutoutInsets.right
                }
            }
        } else 0
    }

    private fun getScaleValues(): Pair<Float, Float> {
        return if (isPortrait) {
            0.5f to 0.5f
        } else {
            0.3f to 0.325f
        }
    }

    fun getIsPortrait() = isPortrait

    fun reloadEmotes() = chatFragment?.reloadEmotes()

    fun isActive() = chatFragment?.isActive()

    fun disconnect() = chatFragment?.disconnect()

    fun reconnect() = chatFragment?.reconnect()

    fun secondViewIsHidden() = !binding.chatLayout.isVisible

    fun canEnterPictureInPicture(): Boolean {
        val quality = if (viewModel.restoreQuality) {
            viewModel.previousQuality
        } else {
            viewModel.quality
        }
        return quality != AUDIO_ONLY_QUALITY && quality != CHAT_ONLY_QUALITY
    }

    protected fun setPipActions(playing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            prefs.getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
        ) {
            requireActivity().setPictureInPictureParams(
                PictureInPictureParams.Builder().apply {
                    setActions(listOf(
                        RemoteAction(
                            Icon.createWithResource(requireContext(), R.drawable.baseline_audiotrack_black_24),
                            requireContext().getString(R.string.audio_only),
                            requireContext().getString(R.string.audio_only),
                            PendingIntent.getBroadcast(
                                requireContext(),
                                REQUEST_CODE_AUDIO_ONLY,
                                Intent(MainActivity.INTENT_START_AUDIO_ONLY).setPackage(requireContext().packageName),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        ),
                        if (playing) {
                            RemoteAction(
                                Icon.createWithResource(requireContext(), R.drawable.baseline_pause_black_48),
                                requireContext().getString(R.string.pause),
                                requireContext().getString(R.string.pause),
                                PendingIntent.getBroadcast(
                                    requireContext(),
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        } else {
                            RemoteAction(
                                Icon.createWithResource(requireContext(), R.drawable.baseline_play_arrow_black_48),
                                requireContext().getString(R.string.resume),
                                requireContext().getString(R.string.resume),
                                PendingIntent.getBroadcast(
                                    requireContext(),
                                    REQUEST_CODE_PLAY_PAUSE,
                                    Intent(MainActivity.INTENT_PLAY_PAUSE_PLAYER).setPackage(requireContext().packageName),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                        }
                    ))
                }.build()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val isInPIPMode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
            else -> false
        }
        if (isInPIPMode) {
            if (isPortrait) {
                binding.chatLayout.gone()
            } else {
                hideChatLayout()
            }
            useController = false
        }
    }

    override fun initialize() {
        if (requireArguments().getString(KEY_TYPE) != OFFLINE_VIDEO) {
            viewModel.isFollowingChannel(
                requireContext().tokenPrefs().getString(C.USER_ID, null),
                requireArguments().getString(KEY_CHANNEL_ID),
                requireArguments().getString(KEY_CHANNEL_LOGIN),
                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                TwitchApiHelper.getHelixHeaders(requireContext()),
            )
            if (videoType == VIDEO) {
                val videoId = requireArguments().getString(KEY_VIDEO_ID)
                if (!videoId.isNullOrBlank() && (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false))) {
                    viewModel.loadGamesList(
                        videoId,
                        prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                        TwitchApiHelper.getGQLHeaders(requireContext()),
                        prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                    )
                }
            }
        }
    }

    protected fun startPlayer() {
        viewModel.started = true
        when (videoType) {
            STREAM -> {
                viewModel.useCustomProxy = prefs.getBoolean(C.PLAYER_STREAM_PROXY, false)
                loadStream()
                viewModel.loadStream(
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    viewerCount = requireArguments().getInt(KEY_VIEWER_COUNT).takeIf { it != -1 },
                    loop = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) ||
                            !requireContext().prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true) ||
                            (requireContext().prefs().getBoolean(C.CHAT_POINTS_COLLECT, true) &&
                                    !requireContext().tokenPrefs().getString(C.USER_ID, null).isNullOrBlank() &&
                                    !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()),
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    val id = requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()
                    if (id != null) {
                        viewModel.getVideoPosition(id)
                    } else {
                        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, 0)
                    }
                } else {
                    if (requireArguments().getBoolean(KEY_IGNORE_SAVED_POSITION)) {
                        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, requireArguments().getLong(KEY_OFFSET).takeIf { it != -1L } ?: 0)
                        requireArguments().putBoolean(KEY_IGNORE_SAVED_POSITION, false)
                        requireArguments().putLong(KEY_OFFSET, -1)
                    } else {
                        playVideo((prefs.getString(C.TOKEN_SKIP_VIDEO_ACCESS_TOKEN, "2")?.toIntOrNull() ?: 2) <= 1, 0)
                    }
                }
            }
            CLIP -> {
                viewModel.loadClip(
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                    id = requireArguments().getString(KEY_CLIP_ID),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
            }
            OFFLINE_VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    viewModel.getOfflineVideoPosition(requireArguments().getInt(KEY_OFFLINE_VIDEO_ID))
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.savedOfflineVideoPosition.value = 0
                    }
                }
            }
        }
    }

    private fun loadStream() {
        requireArguments().getString(KEY_CHANNEL_LOGIN)?.let { channelLogin ->
            val proxyUrl = prefs.getString(C.PLAYER_PROXY_URL, "")
            if (viewModel.useCustomProxy && !proxyUrl.isNullOrBlank()) {
                startStream(proxyUrl.replace("\$channel", channelLogin))
            } else {
                if (viewModel.useCustomProxy) {
                    viewModel.useCustomProxy = false
                }
                viewModel.loadStreamResult(
                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = channelLogin,
                    randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                    xDeviceId = prefs.getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                    playerType = prefs.getString(C.TOKEN_PLAYERTYPE, "site"),
                    supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                    proxyHost = prefs.getString(C.PROXY_HOST, null),
                    proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                    proxyUser = prefs.getString(C.PROXY_USER, null),
                    proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                    enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
                )
            }
        }
    }

    protected fun playVideo(skipAccessToken: Boolean, playbackPosition: Long?) {
        if (skipAccessToken && !requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW).isNullOrBlank()) {
            requireArguments().getString(KEY_VIDEO_ANIMATED_PREVIEW)?.let { preview ->
                val qualityMap = TwitchApiHelper.getVideoUrlMapFromPreview(preview, requireArguments().getString(KEY_VIDEO_TYPE), viewModel.backupQualities)
                val map = mutableMapOf<String, Pair<String, String?>>()
                qualityMap.forEach {
                    when (it.key) {
                        "source" -> map[it.key] = Pair(requireContext().getString(R.string.source), it.value)
                        "audio_only" -> map[it.key] = Pair(requireContext().getString(R.string.audio_only), it.value)
                        else -> map[it.key] = Pair(it.key, it.value)
                    }
                }
                map.put(AUDIO_ONLY_QUALITY, map.remove(AUDIO_ONLY_QUALITY) //move audio option to bottom
                    ?: Pair(requireContext().getString(R.string.audio_only), null))
                val qualities = map.toList()
                    .sortedByDescending {
                        it.first.substringAfter("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .sortedByDescending {
                        it.first.substringBefore("p", "").takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .sortedByDescending {
                        it.first == "source"
                    }
                    .toMap()
                viewModel.qualities = qualities
                viewModel.quality = qualities.keys.firstOrNull()
                qualities.values.firstOrNull()?.second
            }?.let { url ->
                startVideo(url, playbackPosition)
            }
        } else {
            viewModel.playbackPosition = playbackPosition
            viewModel.loadVideo(
                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                videoId = requireArguments().getString(KEY_VIDEO_ID),
                playerType = prefs.getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        with(binding) {
            isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
            if (isMaximized) {
                enableBackground()
            } else {
                disableBackground()
            }
            val isInPIPMode = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireActivity().isInPictureInPictureMode
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> !useController && isMaximized
                else -> false
            }
            if (!isInPIPMode) {
                chatLayout.hideKeyboard()
                chatLayout.clearFocus()
                initLayout()
            }
            (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.dismiss()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        with(binding) {
            if (isInPictureInPictureMode) {
                if (!isMaximized) {
                    isMaximized = true
                    requireActivity().onBackPressedDispatcher.addCallback(this@PlayerFragment, backPressedCallback)
                    if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                        chatFragment?.toggleBackPressedCallback(true)
                    }
                    slidingLayout.translationX = 0f
                    slidingLayout.translationY = 0f
                    slidingLayout.scaleX = 1f
                    slidingLayout.scaleY = 1f
                }
                if (isPortrait) {
                    chatLayout.gone()
                } else {
                    hideChatLayout()
                }
                useController = false
                controllerAnimation?.cancel()
                binding.playerControls.root.alpha = 0f
                binding.playerControls.root.gone()
                // player dialog
                (childFragmentManager.findFragmentByTag("closeOnPip") as? BottomSheetDialogFragment)?.dismiss()
                // player chat message dialog
                (chatFragment?.childFragmentManager?.findFragmentByTag("messageDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("replyDialog") as? BottomSheetDialogFragment)?.dismiss()
                (chatFragment?.childFragmentManager?.findFragmentByTag("imageDialog") as? BottomSheetDialogFragment)?.dismiss()
            } else {
                useController = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        binding.playerControls.root.removeCallbacks(controllerHideAction)
    }

    protected fun savePosition() {
        when (videoType) {
            VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    requireArguments().getString(KEY_VIDEO_ID)?.toLongOrNull()?.let { id ->
                        getCurrentPosition()?.let { position ->
                            viewModel.saveVideoPosition(id, position)
                        }
                    }
                }
            }
            OFFLINE_VIDEO -> {
                if (prefs.getBoolean(C.PLAYER_USE_VIDEOPOSITIONS, true)) {
                    getCurrentPosition()?.let { position ->
                        viewModel.saveOfflineVideoPosition(requireArguments().getInt(KEY_OFFLINE_VIDEO_ID), position)
                    }
                }
            }
        }
    }

    fun minimize() {
        with(binding) {
            isMaximized = false
            if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(false)
            }
            backPressedCallback.remove()
            useController = false
            hideController(true)
            fun animate() {
                val (minimizedScaleX, minimizedScaleY) = getScaleValues()
                val windowInsets = ViewCompat.getRootWindowInsets(requireView())
                val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val keyboardInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom?.let { if (it > 0) it - (insets?.bottom ?: 0) else it } ?: 0
                val scaledXDiff = (slidingLayout.width * (1f - minimizedScaleX)) / 2
                val scaledYDiff = (slidingLayout.height * (1f - minimizedScaleY)) / 2
                val navBarHeight = requireView().rootView.findViewById<LinearLayout>(R.id.navBarContainer)?.height?.takeIf { it > 0 }?.let { it - keyboardInsets } ?: (insets?.bottom ?: 0)
                val playerWidth = if (isPortrait) {
                    playerLayout.width
                } else {
                    slidingLayout.width - getHorizontalInsets(windowInsets)
                }
                val newX = slidingLayout.width - (insets?.right ?: 0) - (playerWidth * minimizedScaleX) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20F, resources.displayMetrics) * minimizedScaleX)
                val newY = slidingLayout.height - navBarHeight - (playerLayout.height * minimizedScaleY) - (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30F, resources.displayMetrics) * minimizedScaleY)
                slidingLayout.animate().apply {
                    translationX(0f - scaledXDiff - ((insets?.left ?: 0) * minimizedScaleX) + newX)
                    translationY(0f - scaledYDiff - ((insets?.top ?: 0) * minimizedScaleY) + newY)
                    scaleX(minimizedScaleX)
                    scaleY(minimizedScaleY)
                    setDuration(250L)
                    setListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: Animator) {
                                isAnimating = true
                                disableBackground()
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                                setListener(null)
                                activePointerId = -1
                            }
                        }
                    )
                    start()
                }
            }
            if (isPortrait) {
                chatLayout.gone()
                slidingLayout.doOnLayout {
                    animate()
                }
            } else {
                showStatusBar()
                hideChatLayout()
                slidingLayout.doOnPreDraw {
                    animate()
                }
                val activity = requireActivity()
                activity.lifecycleScope.launch {
                    delay(500L)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    fun maximize() {
        with(binding) {
            isMaximized = true
            requireActivity().onBackPressedDispatcher.addCallback(this@PlayerFragment, backPressedCallback)
            if (videoType == STREAM && chatFragment?.emoteMenuIsVisible() == true) {
                chatFragment?.toggleBackPressedCallback(true)
            }
            useController = true
            if (!controllerHideOnTouch) {
                showController(true)
                updateProgress()
            }
            if (isPortrait) {
                chatLayout.visible()
            } else {
                hideStatusBar()
                if (isChatOpen) {
                    showChatLayout()
                }
            }
            slidingLayout.animate().apply {
                translationX(0f)
                translationY(0f)
                scaleX(1f)
                scaleY(1f)
                setDuration(250L)
                setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            isAnimating = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            isAnimating = false
                            setListener(null)
                            enableBackground()
                            activePointerId = -1
                        }
                    }
                )
                start()
            }
        }
    }

    fun showDownloadDialog() {
        if (viewModel.loaded.value) {
            when (videoType) {
                STREAM -> {
                    val qualities = viewModel.qualities.filter { !it.value.second.isNullOrBlank() }
                    DownloadDialog.newInstance(
                        id = requireArguments().getString(KEY_STREAM_ID),
                        title = requireArguments().getString(KEY_TITLE),
                        startedAt = requireArguments().getString(KEY_STARTED_AT),
                        channelId = requireArguments().getString(KEY_CHANNEL_ID),
                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                        channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
                        thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                        gameId = requireArguments().getString(KEY_GAME_ID),
                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                        gameName = requireArguments().getString(KEY_GAME_NAME),
                        qualityKeys = qualities.keys.toTypedArray(),
                        qualityNames = qualities.map { it.value.first }.toTypedArray(),
                        qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
                VIDEO -> {
                    downloadVideo()
                }
                CLIP -> {
                    val qualities = viewModel.qualities.filter { !it.value.second.isNullOrBlank() }
                    DownloadDialog.newInstance(
                        clipId = requireArguments().getString(KEY_CLIP_ID),
                        title = requireArguments().getString(KEY_TITLE),
                        uploadDate = requireArguments().getString(KEY_UPLOAD_DATE),
                        duration = requireArguments().getDouble(KEY_DURATION),
                        videoId = requireArguments().getString(KEY_VIDEO_ID),
                        vodOffset = requireArguments().getInt(KEY_VOD_OFFSET),
                        channelId = requireArguments().getString(KEY_CHANNEL_ID),
                        channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                        channelName = requireArguments().getString(KEY_CHANNEL_NAME),
                        channelLogo = requireArguments().getString(KEY_CHANNEL_LOGO),
                        thumbnail = requireArguments().getString(KEY_THUMBNAIL),
                        gameId = requireArguments().getString(KEY_GAME_ID),
                        gameSlug = requireArguments().getString(KEY_GAME_SLUG),
                        gameName = requireArguments().getString(KEY_GAME_NAME),
                        qualityKeys = qualities.keys.toTypedArray(),
                        qualityNames = qualities.map { it.value.first }.toTypedArray(),
                        qualityUrls = qualities.mapNotNull { it.value.second }.toTypedArray(),
                    ).show(childFragmentManager, null)
                }
            }
        }
    }

    fun onSleepTimerChanged(durationMs: Long, hours: Int, minutes: Int, lockScreen: Boolean) {
        if (durationMs > 0L) {
            requireContext().toast(
                when {
                    hours == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                    minutes == 0 -> getString(
                        R.string.playback_will_stop,
                        resources.getQuantityString(R.plurals.hours, hours, hours)
                    )
                    else -> getString(
                        R.string.playback_will_stop_hours_minutes,
                        resources.getQuantityString(R.plurals.hours, hours, hours),
                        resources.getQuantityString(R.plurals.minutes, minutes, minutes)
                    )
                }
            )
        } else if (((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0) > 0L) {
            requireContext().toast(R.string.timer_canceled)
        }
        if (lockScreen != prefs.getBoolean(C.SLEEP_TIMER_LOCK, false)) {
            prefs.edit { putBoolean(C.SLEEP_TIMER_LOCK, lockScreen) }
        }
        (activity as? MainActivity)?.setSleepTimer(durationMs)
    }

    override fun onChange(requestCode: Int, index: Int, text: CharSequence, tag: Int?) {
        when (requestCode) {
            REQUEST_CODE_QUALITY -> {
                changeQuality(viewModel.qualities.keys.elementAtOrNull(index))
                changePlayerMode()
                setQualityText()
            }
            REQUEST_CODE_SPEED -> {
                prefs.getString(C.PLAYER_SPEED_LIST, "0.25\n0.5\n0.75\n1.0\n1.25\n1.5\n1.75\n2.0\n3.0\n4.0\n8.0")?.split("\n")?.let { speeds ->
                    speeds.getOrNull(index)?.toFloatOrNull()?.let { speed ->
                        setPlaybackSpeed(speed)
                        prefs.edit { putFloat(C.PLAYER_SPEED, speed) }
                        (childFragmentManager.findFragmentByTag("closeOnPip") as? PlayerSettingsDialog?)?.setSpeed(speed.toString())
                    }
                }
            }
        }
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    when (callback) {
                        "refreshStream" -> {
                            requireArguments().getString(KEY_CHANNEL_LOGIN)?.let { channelLogin ->
                                viewModel.loadStreamResult(
                                    networkLibrary = prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                                    channelLogin = channelLogin,
                                    randomDeviceId = prefs.getBoolean(C.TOKEN_RANDOM_DEVICEID, true),
                                    xDeviceId = prefs.getString(C.TOKEN_XDEVICEID, "twitch-web-wall-mason"),
                                    playerType = prefs.getString(C.TOKEN_PLAYERTYPE, "site"),
                                    supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                                    proxyPlaybackAccessToken = prefs.getBoolean(C.PROXY_PLAYBACK_ACCESS_TOKEN, false),
                                    proxyHost = prefs.getString(C.PROXY_HOST, null),
                                    proxyPort = prefs.getString(C.PROXY_PORT, null)?.toIntOrNull(),
                                    proxyUser = prefs.getString(C.PROXY_USER, null),
                                    proxyPassword = prefs.getString(C.PROXY_PASSWORD, null),
                                    enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false)
                                )
                            }
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                requireArguments().getString(KEY_CHANNEL_ID),
                                requireArguments().getString(KEY_CHANNEL_LOGIN),
                                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                        }
                        "refreshVideo" -> {
                            val videoId = requireArguments().getString(KEY_VIDEO_ID)
                            viewModel.loadVideo(
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                                videoId = videoId,
                                playerType = prefs.getString(C.TOKEN_PLAYERTYPE_VIDEO, "channel_home_live"),
                                supportedCodecs = prefs.getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                requireArguments().getString(KEY_CHANNEL_ID),
                                requireArguments().getString(KEY_CHANNEL_LOGIN),
                                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                            if (!videoId.isNullOrBlank() && (prefs.getBoolean(C.PLAYER_GAMESBUTTON, true) || prefs.getBoolean(C.PLAYER_MENU_GAMES, false))) {
                                viewModel.loadGamesList(
                                    videoId,
                                    prefs.getString(C.NETWORK_LIBRARY, "OkHttp"),
                                    TwitchApiHelper.getGQLHeaders(requireContext()),
                                    prefs.getBoolean(C.ENABLE_INTEGRITY, false),
                                )
                            }
                        }
                        "refreshClip" -> {
                            viewModel.loadClip(
                                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext()),
                                id = requireArguments().getString(KEY_CLIP_ID),
                                enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                            )
                            viewModel.isFollowingChannel(
                                requireContext().tokenPrefs().getString(C.USER_ID, null),
                                requireArguments().getString(KEY_CHANNEL_ID),
                                requireArguments().getString(KEY_CHANNEL_LOGIN),
                                prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                                requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                                TwitchApiHelper.getGQLHeaders(requireContext(), true),
                                TwitchApiHelper.getHelixHeaders(requireContext()),
                            )
                        }
                        "follow" -> viewModel.saveFollowChannel(
                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                            requireArguments().getString(KEY_CHANNEL_ID),
                            requireArguments().getString(KEY_CHANNEL_LOGIN),
                            requireArguments().getString(KEY_CHANNEL_NAME),
                            prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                            requireArguments().getString(KEY_STARTED_AT),
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                        "unfollow" -> viewModel.deleteFollowChannel(
                            requireContext().tokenPrefs().getString(C.USER_ID, null),
                            requireArguments().getString(KEY_CHANNEL_ID),
                            prefs.getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext(), true),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                    }
                }
            }
        }
    }

    protected fun getStreamArguments(item: Stream): Bundle {
        return bundleOf(
            KEY_TYPE to STREAM,
            KEY_STREAM_ID to item.id,
            KEY_TITLE to item.title,
            KEY_VIEWER_COUNT to (item.viewerCount ?: -1),
            KEY_STARTED_AT to item.startedAt,
            KEY_CHANNEL_ID to item.channelId,
            KEY_CHANNEL_LOGIN to item.channelLogin,
            KEY_CHANNEL_NAME to item.channelName,
            KEY_CHANNEL_LOGO to item.channelLogo,
            KEY_THUMBNAIL to item.thumbnail,
            KEY_GAME_ID to item.gameId,
            KEY_GAME_SLUG to item.gameSlug,
            KEY_GAME_NAME to item.gameName,
        )
    }

    protected fun getVideoArguments(item: Video, offset: Long?, ignoreSavedPosition: Boolean): Bundle {
        return bundleOf(
            KEY_TYPE to VIDEO,
            KEY_VIDEO_ID to item.id,
            KEY_TITLE to item.title,
            KEY_UPLOAD_DATE to item.uploadDate,
            KEY_DURATION to item.duration,
            KEY_OFFSET to (offset ?: -1L),
            KEY_IGNORE_SAVED_POSITION to ignoreSavedPosition,
            KEY_VIDEO_TYPE to item.type,
            KEY_VIDEO_ANIMATED_PREVIEW to item.animatedPreviewURL,
            KEY_CHANNEL_ID to item.channelId,
            KEY_CHANNEL_LOGIN to item.channelLogin,
            KEY_CHANNEL_NAME to item.channelName,
            KEY_CHANNEL_LOGO to item.channelLogo,
            KEY_THUMBNAIL to item.thumbnail,
            KEY_GAME_ID to item.gameId,
            KEY_GAME_SLUG to item.gameSlug,
            KEY_GAME_NAME to item.gameName,
        )
    }

    protected fun getClipArguments(item: Clip): Bundle {
        return bundleOf(
            KEY_TYPE to CLIP,
            KEY_CLIP_ID to item.id,
            KEY_TITLE to item.title,
            KEY_UPLOAD_DATE to item.uploadDate,
            KEY_DURATION to item.duration,
            KEY_VIDEO_ID to item.videoId,
            KEY_VIDEO_ANIMATED_PREVIEW to item.videoAnimatedPreviewURL,
            KEY_VOD_OFFSET to (item.vodOffset ?: -1),
            KEY_CHANNEL_ID to item.channelId,
            KEY_CHANNEL_LOGIN to item.channelLogin,
            KEY_CHANNEL_NAME to item.channelName,
            KEY_PROFILE_IMAGE_URL to item.profileImageUrl,
            KEY_CHANNEL_LOGO to item.channelLogo,
            KEY_THUMBNAIL to item.thumbnail,
            KEY_GAME_ID to item.gameId,
            KEY_GAME_SLUG to item.gameSlug,
            KEY_GAME_NAME to item.gameName,
        )
    }

    protected fun getOfflineVideoArguments(item: OfflineVideo): Bundle {
        return bundleOf(
            KEY_TYPE to OFFLINE_VIDEO,
            KEY_OFFLINE_VIDEO_ID to item.id,
            KEY_TITLE to item.name,
            KEY_URL to item.url,
            KEY_CHAT_URL to item.chatUrl,
            KEY_CHANNEL_ID to item.channelId,
            KEY_CHANNEL_LOGIN to item.channelLogin,
            KEY_CHANNEL_NAME to item.channelName,
            KEY_CHANNEL_LOGO to item.channelLogo,
            KEY_GAME_ID to item.gameId,
            KEY_GAME_SLUG to item.gameSlug,
            KEY_GAME_NAME to item.gameName,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        protected const val AUTO_QUALITY = "auto"
        protected const val AUDIO_ONLY_QUALITY = "audio_only"
        protected const val CHAT_ONLY_QUALITY = "chat_only"

        private const val REQUEST_CODE_QUALITY = 0
        private const val REQUEST_CODE_SPEED = 1
        private const val REQUEST_CODE_AUDIO_ONLY = 2
        private const val REQUEST_CODE_PLAY_PAUSE = 3

        internal const val STREAM = "stream"
        internal const val VIDEO = "video"
        internal const val CLIP = "clip"
        internal const val OFFLINE_VIDEO = "offlineVideo"

        protected const val KEY_TYPE = "type"
        protected const val KEY_STREAM_ID = "streamId"
        protected const val KEY_VIDEO_ID = "videoId"
        protected const val KEY_CLIP_ID = "clipId"
        protected const val KEY_OFFLINE_VIDEO_ID = "offlineVideoId"
        protected const val KEY_TITLE = "title"
        protected const val KEY_VIEWER_COUNT = "viewerCount"
        protected const val KEY_STARTED_AT = "startedAt"
        protected const val KEY_UPLOAD_DATE = "uploadDate"
        protected const val KEY_DURATION = "duration"
        protected const val KEY_OFFSET = "offset"
        protected const val KEY_IGNORE_SAVED_POSITION = "ignoreSavedPosition"
        protected const val KEY_VIDEO_TYPE = "videoType"
        protected const val KEY_VIDEO_ANIMATED_PREVIEW = "videoAnimatedPreview"
        protected const val KEY_VOD_OFFSET = "vodOffset"
        protected const val KEY_URL = "url"
        protected const val KEY_CHAT_URL = "chatUrl"
        protected const val KEY_CHANNEL_ID = "channelId"
        protected const val KEY_CHANNEL_LOGIN = "channelLogin"
        protected const val KEY_CHANNEL_NAME = "channelName"
        protected const val KEY_PROFILE_IMAGE_URL = "profileImageUrl"
        protected const val KEY_CHANNEL_LOGO = "channelLogo"
        protected const val KEY_THUMBNAIL = "thumbnail"
        protected const val KEY_GAME_ID = "gameId"
        protected const val KEY_GAME_SLUG = "gameSlug"
        protected const val KEY_GAME_NAME = "gameName"
    }
}
