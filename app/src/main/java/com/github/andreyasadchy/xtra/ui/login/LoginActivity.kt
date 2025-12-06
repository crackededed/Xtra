package com.github.andreyasadchy.xtra.ui.login

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityLoginBinding
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.convertDpToPixels
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.gone
import com.github.andreyasadchy.xtra.util.isLightTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.shortToast
import com.github.andreyasadchy.xtra.util.toast
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.visible
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    private val tokenPattern = Pattern.compile("token=(.+?)(?=&)")
    private var tokens = mutableListOf<String>()
    private var userId: String? = null
    private var userLogin: String? = null
    private var helixToken: String? = null
    private var gqlToken: String? = null
    private var webGQLToken: String? = null
    private var deviceCode: String? = null
    private var readHeaders = false
    private var checkUrl = false

    private lateinit var binding: ActivityLoginBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
                leftMargin = insets.left
                rightMargin = insets.right
                bottomMargin = insets.bottom
            }
            windowInsets
        }
        with(binding) {
            val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, "OkHttp")
            val helixHeaders = TwitchApiHelper.getHelixHeaders(this@LoginActivity)
            val helixClientId = helixHeaders[C.HEADER_CLIENT_ID]
            val oldHelixToken = helixHeaders[C.HEADER_TOKEN]?.removePrefix("Bearer ")
            val gqlHeaders = TwitchApiHelper.getGQLHeaders(this@LoginActivity, true)
            val oldGQLToken = gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth ")
            if (!oldGQLToken.isNullOrBlank() || !oldHelixToken.isNullOrBlank()) {
                TwitchApiHelper.checkedValidation = false
                tokenPrefs().edit {
                    putString(C.TOKEN, null)
                    putString(C.GQL_HEADERS, null)
                    putLong(C.INTEGRITY_EXPIRATION, 0)
                    putString(C.GQL_TOKEN2, null)
                    putString(C.GQL_TOKEN_WEB, null)
                    putString(C.USER_ID, null)
                    putString(C.USERNAME, null)
                }
                lifecycleScope.launch {
                    if (!helixClientId.isNullOrBlank() && !oldHelixToken.isNullOrBlank()) {
                        try {
                            authRepository.revoke(networkLibrary, "client_id=${helixClientId}&token=${oldHelixToken}")
                        } catch (e: Exception) {

                        }
                    }
                    val gqlClientId = gqlHeaders[C.HEADER_CLIENT_ID]
                    if (!gqlClientId.isNullOrBlank() && !oldGQLToken.isNullOrBlank()) {
                        try {
                            authRepository.revoke(networkLibrary, "client_id=${gqlClientId}&token=${oldGQLToken}")
                        } catch (e: Exception) {

                        }
                    }
                    val webGQLToken = tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
                    if (!webGQLToken.isNullOrBlank()) {
                        try {
                            authRepository.revoke(networkLibrary, "client_id=kimne78kx3ncx6brgo4mv6wki5h1ko&token=${webGQLToken}")
                        } catch (e: Exception) {

                        }
                    }
                }
            }
            if (prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp") == "kd1unb4b3q4t58fwlpcbzcbnm76a8fp") {
                prefs().edit {
                    putString(C.GQL_CLIENT_ID2, "ue6666qo983tsx6so1t0vnawi233wa")
                    putString(C.GQL_REDIRECT2, "https://www.twitch.tv/settings/connections")
                }
            }
            val apiSetting = prefs().getString(C.API_LOGIN, "0")?.toInt() ?: 0
            val gqlClientId = prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
            val gqlRedirect = prefs().getString(C.GQL_REDIRECT2, "https://www.twitch.tv/")
            val helixRedirect = prefs().getString(C.HELIX_REDIRECT, "https://localhost")
            val helixScopes = listOf(
                "channel:edit:commercial", // channels/commercial
                "channel:manage:broadcast", // streams/markers
                "channel:manage:moderators", // moderation/moderators
                "channel:manage:raids", // raids
                "channel:manage:vips", // channels/vips
                "channel:moderate",
                "chat:edit", // irc
                "chat:read", // irc
                "moderator:manage:announcements", // chat/announcements
                "moderator:manage:banned_users", // moderation/bans
                "moderator:manage:chat_messages", // moderation/chat
                "moderator:manage:chat_settings", // chat/settings
                "moderator:read:chatters", // chat/chatters
                "moderator:read:followers", // channels/followers
                "user:manage:chat_color", // chat/color
                "user:manage:whispers", // whispers
                "user:read:chat",
                "user:read:emotes", // chat/emotes/user
                "user:read:follows", // streams/followed, channels/followed
                "user:write:chat", // chat/messages
            )
            val helixAuthUrl = "https://id.twitch.tv/oauth2/authorize" +
                    "?response_type=token" +
                    "&client_id=${helixClientId}" +
                    "&redirect_uri=${helixRedirect}" +
                    "&scope=${URLEncoder.encode(helixScopes.joinToString(" "), Charsets.UTF_8.name())}"
            webViewContainer.visible()
            textZoom.visible()
            havingTrouble.setOnClickListener {
                this@LoginActivity.getAlertDialogBuilder()
                    .setMessage(getString(R.string.login_problem_solution))
                    .setPositiveButton(R.string.log_in) { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, helixAuthUrl.toUri()).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            }
                            startActivity(intent)
                            webView.reload()
                        } catch (e: ActivityNotFoundException) {
                            toast(R.string.no_browser_found)
                        }
                    }
                    .setNeutralButton(R.string.to_enter_url) { _, _ ->
                        val editText = EditText(this@LoginActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        this@LoginActivity.getAlertDialogBuilder()
                            .setTitle(R.string.enter_url)
                            .setView(LinearLayout(this@LoginActivity).apply {
                                addView(editText)
                                val padding = convertDpToPixels(20f)
                                setPadding(padding, 0, padding, 0)
                            })
                            .setPositiveButton(R.string.log_in) { _, _ ->
                                val text = editText.text
                                if (text.isNotEmpty()) {
                                    val matcher = tokenPattern.matcher(text)
                                    if (matcher.find()) {
                                        val token = matcher.group(1)
                                        if (!token.isNullOrBlank()) {
                                            lifecycleScope.launch {
                                                val valid = validateHelixToken(networkLibrary, helixClientId, token)
                                                if (valid) {
                                                    helixToken = token
                                                    done()
                                                } else {
                                                    shortToast(R.string.invalid_url)
                                                }
                                            }
                                        } else {
                                            shortToast(R.string.invalid_url)
                                        }
                                    } else {
                                        shortToast(R.string.invalid_url)
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            textZoom.setOnClickListener {
                val slider = Slider(this@LoginActivity).apply {
                    value = (webView.settings.textZoom.toFloat() / 100)
                }
                this@LoginActivity.getAlertDialogBuilder()
                    .setTitle(getString(R.string.text_size))
                    .setView(LinearLayout(this@LoginActivity).apply {
                        addView(slider)
                        val padding = convertDpToPixels(10f)
                        setPadding(padding, 0, padding, 0)
                    })
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        webView.settings.textZoom = (slider.value * 100).roundToInt()
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            CookieManager.getInstance().removeAllCookies(null)
            @Suppress("DEPRECATION")
            if (!isLightTheme) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(webView.settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
                }
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClientCompat() {

                override fun shouldInterceptRequest(view: WebView, webViewRequest: WebResourceRequest): WebResourceResponse? {
                    if (readHeaders) {
                        val token = webViewRequest.requestHeaders.entries.firstOrNull {
                            it.key.equals(C.HEADER_TOKEN, true) && !it.value.equals("undefined", true)
                        }?.value?.removePrefix("OAuth ")
                        if (!token.isNullOrBlank()) {
                            readHeaders = false
                            val clientId = webViewRequest.requestHeaders.entries.firstOrNull { it.key.equals(C.HEADER_CLIENT_ID, true) }?.value
                            lifecycleScope.launch {
                                val valid = validateGQLToken(networkLibrary, clientId, token)
                                if (prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                                    if (apiSetting == 0) {
                                        if (valid || !helixToken.isNullOrBlank()) {
                                            TwitchApiHelper.checkedValidation = true
                                            tokenPrefs().edit {
                                                if (valid) {
                                                    putLong(C.INTEGRITY_EXPIRATION, 0)
                                                    putString(C.GQL_HEADERS, JSONObject(mapOf(
                                                        C.HEADER_CLIENT_ID to clientId,
                                                        C.HEADER_TOKEN to "OAuth $token"
                                                    )).toString())
                                                }
                                                if (!helixToken.isNullOrBlank()) {
                                                    putString(C.TOKEN, helixToken)
                                                }
                                                putString(C.USER_ID, userId)
                                                putString(C.USERNAME, userLogin)
                                            }
                                        }
                                        setResult(RESULT_OK)
                                        finish()
                                    } else {
                                        if (valid) {
                                            TwitchApiHelper.checkedValidation = true
                                            tokenPrefs().edit {
                                                putLong(C.INTEGRITY_EXPIRATION, 0)
                                                putString(C.GQL_HEADERS, JSONObject(mapOf(
                                                    C.HEADER_CLIENT_ID to clientId,
                                                    C.HEADER_TOKEN to "OAuth $token"
                                                )).toString())
                                                putString(C.USER_ID, userId)
                                                putString(C.USERNAME, userLogin)
                                            }
                                            setResult(RESULT_OK)
                                            finish()
                                        } else {
                                            error()
                                            view.loadUrl("https://www.twitch.tv/login")
                                        }
                                    }
                                } else {
                                    if (valid) {
                                        webGQLToken = token
                                    }
                                    done()
                                }
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, webViewRequest)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (checkUrl) {
                        loginIfValidUrl(request.url.toString(), networkLibrary, gqlClientId, gqlRedirect, helixClientId, helixAuthUrl, apiSetting)
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.SHOULD_OVERRIDE_WITH_REDIRECTS)) {
                        if (checkUrl && url != null) {
                            loginIfValidUrl(url, networkLibrary, gqlClientId, gqlRedirect, helixClientId, helixAuthUrl, apiSetting)
                        }
                    }
                    return super.shouldOverrideUrlLoading(view, url)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        val errorCode = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
                            error.errorCode
                        } else null
                        val errorMessage = if (errorCode == ERROR_FAILED_SSL_HANDSHAKE) {
                            getString(R.string.browser_workaround)
                        } else {
                            getString(R.string.error, "${errorCode ?: ""} ${
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
                                    error.description
                                } else ""
                            }")
                        }
                        val html = "<html><body><div align=\"center\">$errorMessage</div></body>"
                        view.loadUrl("about:blank")
                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.RECEIVE_WEB_RESOURCE_ERROR)) {
                        val errorMessage = if (errorCode == ERROR_FAILED_SSL_HANDSHAKE) {
                            getString(R.string.browser_workaround)
                        } else {
                            getString(R.string.error, "$errorCode $description")
                        }
                        val html = "<html><body><div align=\"center\">$errorMessage</div></body>"
                        view?.loadUrl("about:blank")
                        view?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                }
            }
            if (apiSetting == 1) {
                if (prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                    readHeaders = true
                    webView.loadUrl("https://www.twitch.tv/login")
                } else {
                    checkUrl = true
                    lifecycleScope.launch {
                        loadGQLAuthUrl(networkLibrary, gqlClientId, gqlRedirect)
                    }
                }
            } else {
                checkUrl = true
                webView.loadUrl(helixAuthUrl)
            }
        }
    }

    private suspend fun loadGQLAuthUrl(networkLibrary: String?, gqlClientId: String?, gqlRedirect: String?): Boolean {
        with(binding) {
            return try {
                val response = authRepository.getDeviceCode(networkLibrary, "client_id=${gqlClientId}&scopes=channel_read+chat%3Aread+user_blocks_edit+user_blocks_read+user_follows_edit+user_read")
                deviceCode = response.deviceCode
                val gqlAuthUrl = "https://id.twitch.tv/oauth2/authorize?client_id=${gqlClientId}&device_code=${deviceCode}&force_verify=true&redirect_uri=${gqlRedirect}&response_type=device_grant_trigger&scope=channel_read chat:read user_blocks_edit user_blocks_read user_follows_edit user_read"
                webView.loadUrl(gqlAuthUrl)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun loginIfValidUrl(url: String, networkLibrary: String?, gqlClientId: String?, gqlRedirect: String?, helixClientId: String?, helixAuthUrl: String, apiSetting: Int) {
        with(binding) {
            if (url == gqlRedirect) {
                lifecycleScope.launch {
                    val response = authRepository.getToken(networkLibrary, "client_id=${gqlClientId}&device_code=${deviceCode}&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                    val token = response.token
                    val valid = validateGQLToken(networkLibrary, gqlClientId, token)
                    if (apiSetting == 0) {
                        if (valid) {
                            gqlToken = token
                        }
                        readHeaders = true
                        checkUrl = false
                        webView.loadUrl("https://www.twitch.tv/login")
                    } else {
                        if (valid) {
                            gqlToken = token
                            readHeaders = true
                            checkUrl = false
                            webView.loadUrl("https://www.twitch.tv/login")
                        } else {
                            error()
                            loadGQLAuthUrl(networkLibrary, gqlClientId, gqlRedirect)
                        }
                    }
                }
            } else {
                val matcher = tokenPattern.matcher(url)
                if (matcher.find()) {
                    val token = matcher.group(1)
                    if (!token.isNullOrBlank() && !tokens.contains(token)) {
                        tokens.add(token)
                        webViewContainer.gone()
                        textZoom.gone()
                        progressBar.visible()
                        lifecycleScope.launch {
                            val valid = validateHelixToken(networkLibrary, helixClientId, token)
                            if (apiSetting == 0) {
                                if (valid) {
                                    helixToken = token
                                }
                                if (prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                                    readHeaders = true
                                    checkUrl = false
                                    webView.loadUrl("https://www.twitch.tv/login")
                                } else {
                                    val loaded = loadGQLAuthUrl(networkLibrary, gqlClientId, gqlRedirect)
                                    if (loaded) {
                                        webViewContainer.visible()
                                        textZoom.visible()
                                        progressBar.gone()
                                    } else {
                                        done()
                                    }
                                }
                            } else {
                                if (valid) {
                                    helixToken = token
                                    done()
                                } else {
                                    error()
                                    webView.loadUrl(helixAuthUrl)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun validateGQLToken(networkLibrary: String?, gqlClientId: String?, token: String): Boolean {
        return try {
            val response = authRepository.validate(networkLibrary, TwitchApiHelper.addTokenPrefixGQL(token))
            if (response.clientId.isNotBlank() && response.clientId == gqlClientId) {
                response.userId?.let { userId = it }
                response.login?.let { userLogin = it }
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun validateHelixToken(networkLibrary: String?, helixClientId: String?, token: String): Boolean {
        return try {
            val response = authRepository.validate(networkLibrary, TwitchApiHelper.addTokenPrefixHelix(token))
            if (response.clientId.isNotBlank() && response.clientId == helixClientId) {
                response.userId?.let { userId = it }
                response.login?.let { userLogin = it }
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun error() {
        with(binding) {
            toast(R.string.connection_error)
            CookieManager.getInstance().removeAllCookies(null)
            tokens = mutableListOf()
            userId = null
            userLogin = null
            helixToken = null
            gqlToken = null
            webGQLToken = null
            webViewContainer.visible()
            textZoom.visible()
            progressBar.gone()
        }
    }

    private fun done() {
        if (!gqlToken.isNullOrBlank() || !helixToken.isNullOrBlank()) {
            TwitchApiHelper.checkedValidation = true
            tokenPrefs().edit {
                if (!gqlToken.isNullOrBlank()) {
                    putString(C.GQL_TOKEN2, gqlToken)
                }
                if (!webGQLToken.isNullOrBlank()) {
                    putString(C.GQL_TOKEN_WEB, webGQLToken)
                }
                if (!helixToken.isNullOrBlank()) {
                    putString(C.TOKEN, helixToken)
                }
                putString(C.USER_ID, userId)
                putString(C.USERNAME, userLogin)
            }
        }
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        binding.webView.loadUrl("about:blank")
        super.onDestroy()
    }
}