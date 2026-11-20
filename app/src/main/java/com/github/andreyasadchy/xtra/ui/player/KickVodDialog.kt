package com.github.andreyasadchy.xtra.ui.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogKickVodBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.time.Instant

class KickVodDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CHANNEL_LOGIN = "channel_login"
        private const val ARG_TWITCH_CREATED_AT = "twitch_created_at"
        private const val ARG_IS_KICK_ACTIVE = "is_kick_active"
        private const val ARG_CHAT_OFFSET_MS = "chat_offset_ms"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun newInstance(
            channelLogin: String?,
            twitchCreatedAt: String?,
            isKickActive: Boolean,
            chatOffsetMs: Long,
        ): KickVodDialog {
            return KickVodDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHANNEL_LOGIN, channelLogin)
                    putString(ARG_TWITCH_CREATED_AT, twitchCreatedAt)
                    putBoolean(ARG_IS_KICK_ACTIVE, isKickActive)
                    putLong(ARG_CHAT_OFFSET_MS, chatOffsetMs)
                }
            }
        }

        fun formatOffset(offsetMs: Long): String {
            val sign = when {
                offsetMs > 0 -> "+"
                offsetMs < 0 -> "-"
                else -> ""
            }
            return "$sign${DateUtils.formatElapsedTime(abs(offsetMs) / 1000L)}"
        }

        fun parseTimestampMs(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            Instant.parseOrNull(trimmed)?.toEpochMilliseconds()?.takeIf { it > 0L }?.let { return it }
            // Kick often returns "YYYY-MM-DD HH:MM:SS" in UTC
            val isoCandidate = trimmed.replace(" ", "T").let {
                if (it.endsWith("Z") || it.contains("+")) it else "${it}Z"
            }
            return Instant.parseOrNull(isoCandidate)?.toEpochMilliseconds()?.takeIf { it > 0L }
        }
    }

    private data class KickVodItem(
        val title: String,
        val uuid: String,
        val createdAtRaw: String?,
        val createdAtMs: Long?,
        val durationMs: Long,
        val sourceUrl: String?,
        val watchUrl: String,
    )

    private var _binding: DialogKickVodBinding? = null
    private val binding get() = _binding!!

    private var twitchCreatedAtMs: Long? = null
    private var resolvingM3u8 = false
    private var pendingKickCreatedAtMs: Long? = null
    private var pendingChannelSlug: String? = null
    private var fetchingChannelVods = false
    private var resolvedAlready = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogKickVodBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val args = requireArguments()
        val channelLogin = args.getString(ARG_CHANNEL_LOGIN).orEmpty()
        twitchCreatedAtMs = parseTimestampMs(args.getString(ARG_TWITCH_CREATED_AT))
        val isKickActive = args.getBoolean(ARG_IS_KICK_ACTIVE, false)
        val initialOffset = args.getLong(ARG_CHAT_OFFSET_MS, 0L)

        with(binding) {
            kickChannelInput.setText(channelLogin)
            kickStatusText.text = getString(
                if (isKickActive) R.string.kick_status_active else R.string.kick_status_twitch
            )
            switchBackToTwitchButton.visibility = if (isKickActive) View.VISIBLE else View.GONE
            updateOffsetText(initialOffset)

            setupResolverWebView(kickResolverWebView)

            findKickVodsButton.setOnClickListener {
                val slug = kickChannelInput.text?.toString()?.trim()?.lowercase().orEmpty()
                if (slug.isBlank()) {
                    Toast.makeText(requireContext(), R.string.kick_enter_channel_toast, Toast.LENGTH_SHORT).show()
                } else {
                    fetchChannelVods(slug)
                }
            }

            loadKickUrlButton.setOnClickListener {
                val input = kickUrlInput.text?.toString()?.trim().orEmpty()
                if (input.isBlank()) {
                    Toast.makeText(requireContext(), R.string.kick_enter_url_toast, Toast.LENGTH_SHORT).show()
                } else {
                    handleManualUrl(input)
                }
            }

            offsetMinus30.setOnClickListener { nudgeOffset(-30_000L) }
            offsetMinus5.setOnClickListener { nudgeOffset(-5_000L) }
            offsetMinus1.setOnClickListener { nudgeOffset(-1_000L) }
            offsetReset.setOnClickListener {
                (parentFragment as? PlayerFragment)?.resetChatOffset()
                updateOffsetText(0L)
            }
            offsetPlus1.setOnClickListener { nudgeOffset(1_000L) }
            offsetPlus5.setOnClickListener { nudgeOffset(5_000L) }
            offsetPlus30.setOnClickListener { nudgeOffset(30_000L) }

            switchBackToTwitchButton.setOnClickListener {
                (parentFragment as? PlayerFragment)?.switchBackToTwitchVod()
                dismiss()
            }
        }
    }

    private fun nudgeOffset(deltaMs: Long) {
        val player = parentFragment as? PlayerFragment ?: return
        player.adjustChatOffset(deltaMs)
        updateOffsetText(player.getChatOffsetMs())
    }

    private fun updateOffsetText(offsetMs: Long) {
        if (_binding == null) return
        binding.chatOffsetValueText.text = getString(R.string.kick_chat_offset_value, formatOffset(offsetMs))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupResolverWebView(webView: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = DESKTOP_USER_AGENT
        }
        webView.addJavascriptInterface(KickJsBridge(), "KickBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (resolvingM3u8 && !resolvedAlready && isPlayableKickM3u8(url)) {
                    resolvedAlready = true
                    val cookies = CookieManager.getInstance().getCookie("https://kick.com")
                    view?.post {
                        onM3u8Resolved(url, cookies, pendingKickCreatedAtMs)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (_binding == null || view == null || url == null) return

                if (fetchingChannelVods && url.startsWith("https://kick.com/")) {
                    val slug = pendingChannelSlug ?: return
                    // Run same-origin fetch inside WebView so Cloudflare cookies are sent automatically
                    val js = """
                        (function() {
                            fetch('https://kick.com/api/v2/channels/$slug/videos', { credentials: 'include' })
                                .then(function(r) { return r.text(); })
                                .then(function(txt) { KickBridge.onVideosJson(txt); })
                                .catch(function(e) { KickBridge.onVideosError(String(e)); });
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
                } else if (resolvingM3u8 && !resolvedAlready && url.contains("/videos/")) {
                    // Also inspect page scripts / video elements in case playback_url is embedded in JSON
                    val js = """
                        (function() {
                            try {
                                var html = document.documentElement.innerHTML;
                                var m = html.match(/https?:\\\/\\\/[^"'\\s]+master\.m3u8[^"'\\s]*/i) || html.match(/https?:\/\/[^"'\s]+master\.m3u8[^"'\s]*/i);
                                if (m && m[0]) {
                                    KickBridge.onM3u8Found(m[0].replace(/\\\//g, '/'));
                                }
                            } catch (e) {}
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
                }
            }
        }
    }

    private fun isPlayableKickM3u8(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") && !lower.contains("ads") && (
            lower.contains("master.m3u8") ||
                lower.contains("stream.kick.com") ||
                lower.contains("live-video.net") ||
                lower.contains("playback")
            )
    }

    private fun fetchChannelVods(slug: String) {
        fetchingChannelVods = true
        resolvingM3u8 = false
        resolvedAlready = false
        pendingChannelSlug = slug
        binding.kickVodListContainer.removeAllViews()
        setLoading(true, getString(R.string.kick_fetching_vods, slug))
        binding.kickResolverWebView.loadUrl("https://kick.com/$slug")
    }

    private fun handleManualUrl(input: String) {
        val cleaned = input.trim()
        if (cleaned.contains(".m3u8", ignoreCase = true)) {
            val cookies = CookieManager.getInstance().getCookie("https://kick.com")
            onM3u8Resolved(cleaned, cookies, null)
            return
        }
        val targetUrl = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            cleaned
        } else {
            "https://$cleaned"
        }
        resolveKickVodPage(targetUrl, null)
    }

    private fun resolveKickVodPage(vodPageUrl: String, createdAtMs: Long?) {
        fetchingChannelVods = false
        resolvingM3u8 = true
        resolvedAlready = false
        pendingKickCreatedAtMs = createdAtMs
        setLoading(true, getString(R.string.kick_resolving_stream))
        binding.kickResolverWebView.loadUrl(vodPageUrl)
    }

    private fun onM3u8Resolved(m3u8Url: String, cookies: String?, kickCreatedAtMs: Long?) {
        if (_binding == null || !isAdded) return
        resolvingM3u8 = false
        setLoading(false, null)
        binding.kickResolverWebView.stopLoading()
        (parentFragment as? PlayerFragment)?.switchToKickVod(m3u8Url, cookies, kickCreatedAtMs)
        dismiss()
    }

    private fun setLoading(loading: Boolean, message: String?) {
        if (_binding == null) return
        binding.kickLoadingProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.kickLoadingStatus.visibility = if (!message.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.kickLoadingStatus.text = message.orEmpty()
        binding.findKickVodsButton.isEnabled = !loading
        binding.loadKickUrlButton.isEnabled = !loading
    }

    private fun renderVodList(slug: String, jsonText: String) {
        if (_binding == null || !isAdded) return
        fetchingChannelVods = false
        setLoading(false, null)
        try {
            val array = JSONArray(jsonText)
            val items = mutableListOf<KickVodItem>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val videoObj = obj.optJSONObject("video")
                val uuid = videoObj?.optString("uuid").orEmpty()
                if (uuid.isBlank()) continue
                val title = obj.optString("session_title").ifBlank { "Kick VOD" }
                val createdRaw = obj.optString("created_at").ifBlank { obj.optString("start_time") }
                val createdMs = parseTimestampMs(createdRaw)
                val durationMs = obj.optLong("duration", 0L)
                val source = obj.optString("source").takeIf { it.contains(".m3u8", ignoreCase = true) }
                items.add(
                    KickVodItem(
                        title = title,
                        uuid = uuid,
                        createdAtRaw = createdRaw,
                        createdAtMs = createdMs,
                        durationMs = durationMs,
                        sourceUrl = source,
                        watchUrl = "https://kick.com/$slug/videos/$uuid",
                    )
                )
            }
            if (items.isEmpty()) {
                binding.kickLoadingStatus.visibility = View.VISIBLE
                binding.kickLoadingStatus.text = getString(R.string.kick_no_vods_found)
                return
            }

            // Highlight the Kick VOD whose start time is closest to the current Twitch VOD
            val closestUuid = twitchCreatedAtMs?.let { twMs ->
                items.filter { it.createdAtMs != null }
                    .minByOrNull { abs(it.createdAtMs!! - twMs) }
                    ?.takeIf { abs(it.createdAtMs!! - twMs) <= 6L * 3600_000L }
                    ?.uuid
            }

            val container = binding.kickVodListContainer
            container.removeAllViews()
            val dp12 = (12 * resources.displayMetrics.density).toInt()
            val dp8 = (8 * resources.displayMetrics.density).toInt()

            items.take(10).forEach { item ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp12, dp8, dp12, dp8)
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true
                }
                val isMatch = item.uuid == closestUuid
                val titleView = TextView(requireContext()).apply {
                    text = if (isMatch) "[Closest match] ${item.title}" else item.title
                    maxLines = 2
                }
                val durationText = if (item.durationMs > 0L) {
                    DateUtils.formatElapsedTime(item.durationMs / 1000L)
                } else {
                    ""
                }
                val offsetPreview = if (item.createdAtMs != null && twitchCreatedAtMs != null) {
                    " • Auto offset ${formatOffset(item.createdAtMs - twitchCreatedAtMs!!)}"
                } else {
                    ""
                }
                val metaView = TextView(requireContext()).apply {
                    text = listOfNotNull(
                        item.createdAtRaw?.take(16)?.replace("T", " "),
                        durationText.takeIf { it.isNotBlank() }
                    ).joinToString(" • ") + offsetPreview
                    textSize = 12f
                    alpha = 0.75f
                }
                row.addView(titleView)
                row.addView(metaView)
                row.setOnClickListener {
                    if (!item.sourceUrl.isNullOrBlank()) {
                        val cookies = CookieManager.getInstance().getCookie("https://kick.com")
                        onM3u8Resolved(item.sourceUrl, cookies, item.createdAtMs)
                    } else {
                        resolveKickVodPage(item.watchUrl, item.createdAtMs)
                    }
                }
                container.addView(row)
            }
        } catch (e: Exception) {
            binding.kickLoadingStatus.visibility = View.VISIBLE
            binding.kickLoadingStatus.text = getString(R.string.kick_parse_error)
        }
    }

    private inner class KickJsBridge {
        @JavascriptInterface
        fun onVideosJson(jsonText: String) {
            val slug = pendingChannelSlug ?: return
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                val trimmed = jsonText.trim()
                if (trimmed.startsWith("[")) {
                    renderVodList(slug, trimmed)
                } else if (trimmed.startsWith("{")) {
                    // Some responses wrap array inside { "data": [...] }
                    val arr = try {
                        JSONObject(trimmed).optJSONArray("data")?.toString()
                    } catch (_: Exception) {
                        null
                    }
                    if (arr != null) {
                        renderVodList(slug, arr)
                    } else {
                        fetchingChannelVods = false
                        setLoading(false, getString(R.string.kick_cloudflare_retry_hint))
                    }
                } else {
                    fetchingChannelVods = false
                    setLoading(false, getString(R.string.kick_cloudflare_retry_hint))
                }
            }
        }

        @JavascriptInterface
        fun onVideosError(error: String) {
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                fetchingChannelVods = false
                setLoading(false, getString(R.string.kick_cloudflare_retry_hint))
            }
        }

        @JavascriptInterface
        fun onM3u8Found(url: String) {
            activity?.runOnUiThread {
                if (!isAdded || _binding == null || resolvedAlready) return@runOnUiThread
                if (isPlayableKickM3u8(url)) {
                    resolvedAlready = true
                    val cookies = CookieManager.getInstance().getCookie("https://kick.com")
                    onM3u8Resolved(url, cookies, pendingKickCreatedAtMs)
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding?.kickResolverWebView?.apply {
            stopLoading()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }
}
