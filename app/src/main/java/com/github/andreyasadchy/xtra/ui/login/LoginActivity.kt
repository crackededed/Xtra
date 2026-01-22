package com.github.andreyasadchy.xtra.ui.login

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
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
import android.widget.EditText
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
    private var helixToken: String? = null
    private var gqlClientId: String? = null
    private var gqlToken: String? = null
    private var gqlWebClientId: String? = null
    private var gqlWebToken: String? = null
    private var userId: String? = null
    private var userLogin: String? = null
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
                    val gqlWebClientId = prefs().getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko")
                    val gqlWebToken = tokenPrefs().getString(C.GQL_TOKEN_WEB, null)
                    if (!gqlWebClientId.isNullOrBlank() && !gqlWebToken.isNullOrBlank()) {
                        try {
                            authRepository.revoke(networkLibrary, "client_id=${gqlWebClientId}&token=${gqlWebToken}")
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
            val helixAuthUrl = "https://id.twitch.tv/oauth2/authorize" +
                    "?response_type=token" +
                    "&client_id=${helixClientId}" +
                    "&redirect_uri=${helixRedirect}" +
                    "&scope=${URLEncoder.encode(helixScopes.joinToString(" "), Charsets.UTF_8.name())}"
            webViewContainer.visibility = View.VISIBLE
            textZoom.visibility = View.VISIBLE
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
                            Toast.makeText(this@LoginActivity, R.string.no_browser_found, Toast.LENGTH_LONG).show()
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
                                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
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
                        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()
                        setPadding(padding, 0, padding, 0)
                    })
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        webView.settings.textZoom = (slider.value * 100).roundToInt()
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
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
                            if (prefs().getBoolean(C.ENABLE_INTEGRITY, false)) {
                                readHeaders = false
                                lifecycleScope.launch {
                                    val valid = validateGQLToken(networkLibrary, clientId, token)
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
                                }
                            } else {
                                if (gqlWebToken.isNullOrBlank()) {
                                    readHeaders = false
                                    lifecycleScope.launch {
                                        val valid = validateGQLToken(networkLibrary, clientId, token)
                                        if (valid) {
                                            gqlWebClientId = clientId
                                            gqlWebToken = token
                                            readHeaders = true
                                            webView.loadUrl("https://android.tv.twitch.tv/login")
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
                                } else {
                                    if (!clientId.isNullOrBlank() && clientId != gqlWebClientId) {
                                        readHeaders = false
                                        lifecycleScope.launch {
                                            val valid = validateGQLToken(networkLibrary, gqlWebClientId, token)
                                            if (valid) {
                                                gqlClientId = clientId
                                                gqlToken = token
                                                done()
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

    private fun loginIfValidUrl(url: String, networkLibrary: String?, helixClientId: String?, helixAuthUrl: String, apiSetting: Int) {
        with(binding) {
            val matcher = tokenPattern.matcher(url)
            if (matcher.find()) {
                val token = matcher.group(1)
                if (!token.isNullOrBlank()) {
                    checkUrl = false
                    webViewContainer.visibility = View.GONE
                    textZoom.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val valid = validateHelixToken(networkLibrary, helixClientId, token)
                        if (apiSetting == 0) {
                            if (valid) {
                                helixToken = token
                            }
                            webViewContainer.visibility = View.VISIBLE
                            textZoom.visibility = View.VISIBLE
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
            webViewContainer.visibility = View.VISIBLE
            textZoom.visibility = View.VISIBLE
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