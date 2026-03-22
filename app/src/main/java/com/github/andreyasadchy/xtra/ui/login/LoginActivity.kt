package com.github.andreyasadchy.xtra.ui.login

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityLoginBinding
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    private val tokenPattern = Pattern.compile("token=(.+?)(?=&)")
    private var helixToken: String? = null
    private var gqlClientId: String? = null
    private var gqlToken: String? = null
    private var gqlWebClientId: String? = null
    private var gqlWebToken: String? = null
    private var userId: String? = null
    private var userLogin: String? = null
    private var readHeaders = false
    private var readHeaders2 = false
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
            val oldGQLWebToken = tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
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
                    if (!gqlClientId.isNullOrBlank() && !oldGQLToken.isNullOrBlank() && oldGQLToken != oldGQLWebToken) {
                        try {
                            authRepository.revoke(networkLibrary, "client_id=${gqlClientId}&token=${oldGQLToken}")
                        } catch (e: Exception) {

                        }
                    }
                    val gqlWebClientId = prefs().getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko")
                    if (!gqlWebClientId.isNullOrBlank() && !oldGQLWebToken.isNullOrBlank()) {
                        try {
                            authRepository.revoke(networkLibrary, "client_id=${gqlWebClientId}&token=${oldGQLWebToken}")
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
            val helixAuthUrl = "https://id.twitch.tv/oauth2/authorize".toUri().buildUpon().apply {
                appendQueryParameter("response_type", "token")
                appendQueryParameter("client_id", helixClientId)
                appendQueryParameter("redirect_uri", helixRedirect)
                appendQueryParameter("scope", helixScopes.joinToString(" "))
            }.build().toString()
            webView.visibility = View.VISIBLE
            textZoom.visibility = View.VISIBLE
            havingTrouble.visibility = View.VISIBLE
            setupButtons(networkLibrary, helixClientId, helixAuthUrl)
            CookieManager.getInstance().removeAllCookies(null)
            val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                it.getBoolean(0, false)
            }
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
                            val clientId = webViewRequest.requestHeaders.entries.firstOrNull { it.key.equals(C.HEADER_CLIENT_ID, true) }?.value
                            readHeaders = false
                            lifecycleScope.launch {
                                val valid = validateGQLToken(networkLibrary, clientId, token)
                                if (prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                                    if (valid) {
                                        TwitchApiHelper.checkedValidation = true
                                        tokenPrefs().edit {
                                            putLong(C.INTEGRITY_EXPIRATION, 0)
                                            putString(C.GQL_HEADERS, JSONObject(mapOf(
                                                C.HEADER_CLIENT_ID to clientId,
                                                C.HEADER_TOKEN to "OAuth $token"
                                            )).toString())
                                            if (!helixToken.isNullOrBlank()) {
                                                putString(C.TOKEN, helixToken)
                                            }
                                            putString(C.USER_ID, userId)
                                            putString(C.USERNAME, userLogin)
                                        }
                                        setResult(RESULT_OK)
                                        finish()
                                    } else {
                                        if (!helixToken.isNullOrBlank()) {
                                            done()
                                        } else {
                                            error()
                                            readHeaders = true
                                            view.loadUrl("https://www.twitch.tv/login")
                                        }
                                    }
                                } else {
                                    if (valid) {
                                        gqlWebClientId = clientId
                                        gqlWebToken = token
                                        var getTvToken = false
                                        this@LoginActivity.getAlertDialogBuilder()
                                            .setMessage(getString(R.string.tv_login_message))
                                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                                getTvToken = true
                                            }
                                            .setNegativeButton(getString(android.R.string.cancel), null)
                                            .setOnDismissListener {
                                                if (getTvToken) {
                                                    setupSecondaryWebView(networkLibrary, helixClientId, helixAuthUrl, isLightTheme)
                                                } else {
                                                    done()
                                                }
                                            }
                                            .show()
                                    } else {
                                        if (!helixToken.isNullOrBlank()) {
                                            done()
                                        } else {
                                            error()
                                            readHeaders = true
                                            view.loadUrl("https://www.twitch.tv/login")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, webViewRequest)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (checkUrl) {
                        loginIfValidUrl(request.url.toString(), networkLibrary, helixClientId, helixAuthUrl, apiSetting)
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.SHOULD_OVERRIDE_WITH_REDIRECTS)) {
                        if (checkUrl && url != null) {
                            loginIfValidUrl(url, networkLibrary, helixClientId, helixAuthUrl, apiSetting)
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
                readHeaders = true
                webView.loadUrl("https://www.twitch.tv/login")
            } else {
                checkUrl = true
                webView.loadUrl(helixAuthUrl)
            }
        }
    }

    private fun setupButtons(networkLibrary: String?, helixClientId: String?, helixAuthUrl: String) {
        with(binding) {
            textZoom.setOnClickListener {
                val slider = Slider(this@LoginActivity).apply {
                    value = (webView.settings.textZoom.toFloat() / 100)
                }
                this@LoginActivity.getAlertDialogBuilder()
                    .setTitle(getString(R.string.text_size))
                    .setView(LinearLayout(this@LoginActivity).apply {
                        addView(slider)
                        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()
                        setPadding(padding, 0, padding, 0)
                    })
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        webView.settings.textZoom = (slider.value * 100).roundToInt()
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            havingTrouble.setOnClickListener {
                val textInput = TextInputLayout(this@LoginActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                textInput.addView(TextInputEditText(textInput.context).apply {
                    maxLines = 2
                })
                this@LoginActivity.getAlertDialogBuilder()
                    .setMessage(getString(R.string.external_login_message))
                    .setView(LinearLayout(this@LoginActivity).apply {
                        addView(textInput)
                        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
                        setPadding(padding, padding, padding, 0)
                    })
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val text = textInput.editText?.text
                        if (!text.isNullOrBlank()) {
                            val matcher = tokenPattern.matcher(text)
                            if (matcher.find()) {
                                val token = matcher.group(1)
                                if (!token.isNullOrBlank()) {
                                    lifecycleScope.launch {
                                        val valid = validateHelixToken(networkLibrary, helixClientId, token)
                                        if (valid) {
                                            helixToken = token
                                            var getTvToken = false
                                            this@LoginActivity.getAlertDialogBuilder()
                                                .setMessage(getString(R.string.external_tv_login_message))
                                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                                    getTvToken = true
                                                }
                                                .setNegativeButton(getString(android.R.string.cancel), null)
                                                .setOnDismissListener {
                                                    if (getTvToken) {
                                                        setupExternalCodeLogin(networkLibrary, helixClientId, helixAuthUrl)
                                                    } else {
                                                        done()
                                                    }
                                                }
                                                .show()
                                        } else {
                                            Toast.makeText(this@LoginActivity, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    Toast.makeText(this@LoginActivity, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(this@LoginActivity, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNeutralButton(R.string.open_url) { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, helixAuthUrl.toUri()).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            }
                            startActivity(intent)
                            webView.reload()
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this@LoginActivity, R.string.no_browser_found, Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSecondaryWebView(networkLibrary: String?, helixClientId: String?, helixAuthUrl: String, isLightTheme: Boolean) {
        with(binding) {
            webView.visibility = View.INVISIBLE
            secondaryWebView.visibility = View.VISIBLE
            textZoom.setOnClickListener {
                val slider = Slider(this@LoginActivity).apply {
                    value = (secondaryWebView.settings.textZoom.toFloat() / 100)
                }
                this@LoginActivity.getAlertDialogBuilder()
                    .setTitle(getString(R.string.text_size))
                    .setView(LinearLayout(this@LoginActivity).apply {
                        addView(slider)
                        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()
                        setPadding(padding, 0, padding, 0)
                    })
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        secondaryWebView.settings.textZoom = (slider.value * 100).roundToInt()
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            havingTrouble.text = getString(R.string.next)
            havingTrouble.setOnClickListener {
                secondaryWebView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                havingTrouble.text = getString(R.string.trouble_logging_in)
                setupButtons(networkLibrary, helixClientId, helixAuthUrl)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    CookieManager.getInstance().setCookie("https://www.twitch.tv", "auth-token=$gqlWebToken")
                }
                webView.loadUrl("https://www.twitch.tv/activate")
            }
            readHeaders2 = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                WebViewCompat.setProfile(secondaryWebView, "profile2")
            } else {
                CookieManager.getInstance().removeAllCookies(null)
            }
            @Suppress("DEPRECATION")
            if (!isLightTheme) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    WebSettingsCompat.setForceDark(secondaryWebView.settings, WebSettingsCompat.FORCE_DARK_ON)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(secondaryWebView.settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
                }
            }
            secondaryWebView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            secondaryWebView.webChromeClient = WebChromeClient()
            secondaryWebView.webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, webViewRequest: WebResourceRequest): WebResourceResponse? {
                    if (readHeaders2) {
                        val token = webViewRequest.requestHeaders.entries.firstOrNull {
                            it.key.equals(C.HEADER_TOKEN, true) && !it.value.equals("undefined", true)
                        }?.value?.removePrefix("OAuth ")
                        if (!token.isNullOrBlank() && token != gqlWebToken) {
                            val clientId = webViewRequest.requestHeaders.entries.firstOrNull { it.key.equals(C.HEADER_CLIENT_ID, true) }?.value
                            if (!clientId.isNullOrBlank() && clientId != gqlWebClientId) {
                                readHeaders2 = false
                                lifecycleScope.launch {
                                    val valid = validateGQLToken(networkLibrary, clientId, token)
                                    if (valid) {
                                        gqlClientId = clientId
                                        gqlToken = token
                                        done()
                                    } else {
                                        if (!helixToken.isNullOrBlank()) {
                                            done()
                                        } else {
                                            error()
                                            secondaryWebView.visibility = View.GONE
                                            webView.visibility = View.VISIBLE
                                            havingTrouble.text = getString(R.string.trouble_logging_in)
                                            setupButtons(networkLibrary, helixClientId, helixAuthUrl)
                                            readHeaders = true
                                            webView.loadUrl("https://www.twitch.tv/login")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, webViewRequest)
                }
            }
            secondaryWebView.loadUrl("https://android.tv.twitch.tv/login")
        }
    }

    private fun loginIfValidUrl(url: String, networkLibrary: String?, helixClientId: String?, helixAuthUrl: String, apiSetting: Int) {
        with(binding) {
            val matcher = tokenPattern.matcher(url)
            if (matcher.find()) {
                val token = matcher.group(1)
                if (!token.isNullOrBlank()) {
                    checkUrl = false
                    webView.visibility = View.GONE
                    textZoom.visibility = View.GONE
                    havingTrouble.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val valid = validateHelixToken(networkLibrary, helixClientId, token)
                        if (apiSetting == 0) {
                            if (valid) {
                                helixToken = token
                            }
                            webView.visibility = View.VISIBLE
                            textZoom.visibility = View.VISIBLE
                            havingTrouble.visibility = View.VISIBLE
                            progressBar.visibility = View.GONE
                            readHeaders = true
                            webView.loadUrl("https://www.twitch.tv/login")
                        } else {
                            if (valid) {
                                helixToken = token
                                done()
                            } else {
                                error()
                                checkUrl = true
                                webView.loadUrl(helixAuthUrl)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupExternalCodeLogin(networkLibrary: String?, helixClientId: String?, helixAuthUrl: String) {
        with(binding) {
            webView.visibility = View.INVISIBLE
            havingTrouble.visibility = View.INVISIBLE
            val gqlClientId = prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
            var deviceCode: String? = null
            var userCode: String? = null
            lifecycleScope.launch {
                try {
                    val response = authRepository.getDeviceCode(networkLibrary, "client_id=${gqlClientId}&scopes=channel_read+chat%3Aread+user_blocks_edit+user_blocks_read+user_follows_edit+user_read")
                    deviceCode = response.deviceCode
                    userCode = response.userCode
                    codeText.text = userCode
                } catch (e: Exception) {

                }
            }
            codeText.visibility = View.VISIBLE
            copyCode.visibility = View.VISIBLE
            copyCode.setOnClickListener {
                if (userCode != null) {
                    val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("code", userCode))
                }
            }
            openUrl.visibility = View.VISIBLE
            openUrl.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.twitch.tv/activate".toUri()).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    startActivity(intent)
                    webView.reload()
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@LoginActivity, R.string.no_browser_found, Toast.LENGTH_LONG).show()
                }
            }
            next.visibility = View.VISIBLE
            next.setOnClickListener {
                if (deviceCode != null) {
                    lifecycleScope.launch {
                        try {
                            val response = authRepository.getToken(networkLibrary, "client_id=${gqlClientId}&device_code=${deviceCode}&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                            val token = response.token
                            if (!token.isNullOrBlank()) {
                                val valid = validateGQLToken(networkLibrary, gqlClientId, token)
                                if (valid) {
                                    this@LoginActivity.gqlClientId = gqlClientId
                                    gqlToken = token
                                    done()
                                } else {
                                    if (!helixToken.isNullOrBlank()) {
                                        done()
                                    } else {
                                        error()
                                        codeText.visibility = View.GONE
                                        codeText.text = getString(R.string.loading)
                                        copyCode.visibility = View.GONE
                                        openUrl.visibility = View.GONE
                                        next.visibility = View.GONE
                                        setupButtons(networkLibrary, helixClientId, helixAuthUrl)
                                        readHeaders = true
                                        webView.loadUrl("https://www.twitch.tv/login")
                                    }
                                }
                            } else {
                                response.message?.let {
                                    Toast.makeText(this@LoginActivity, it, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {

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
            Toast.makeText(this@LoginActivity, R.string.connection_error, Toast.LENGTH_LONG).show()
            CookieManager.getInstance().removeAllCookies(null)
            helixToken = null
            gqlClientId = null
            gqlToken = null
            gqlWebClientId = null
            gqlWebToken = null
            userId = null
            userLogin = null
            webView.visibility = View.VISIBLE
            textZoom.visibility = View.VISIBLE
            havingTrouble.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
        }
    }

    private fun done() {
        if (!gqlToken.isNullOrBlank() || !helixToken.isNullOrBlank()) {
            TwitchApiHelper.checkedValidation = true
            prefs().edit {
                if (!gqlClientId.isNullOrBlank()) {
                    putString(C.GQL_CLIENT_ID2, gqlClientId)
                }
                if (!gqlWebClientId.isNullOrBlank()) {
                    putString(C.GQL_CLIENT_ID_WEB, gqlWebClientId)
                }
            }
            tokenPrefs().edit {
                if (!helixToken.isNullOrBlank()) {
                    putString(C.TOKEN, helixToken)
                }
                if (!gqlToken.isNullOrBlank()) {
                    putString(C.GQL_TOKEN2, gqlToken)
                }
                if (!gqlWebToken.isNullOrBlank()) {
                    putString(C.GQL_TOKEN_WEB, gqlWebToken)
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