package com.github.andreyasadchy.xtra.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.acsbendi.requestinspectorwebview.RequestInspectorWebViewClient
import com.acsbendi.requestinspectorwebview.WebViewRequest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ActivityLoginBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.LoggedIn
import com.github.andreyasadchy.xtra.model.NotLoggedIn
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: AuthRepository

    private val tokenPattern = Pattern.compile("token=(.+?)(?=&)")
    private var tokens = mutableListOf<String>()
    private var userId: String? = null
    private var userLogin: String? = null
    private var helixToken: String? = null
    private var gqlToken: String? = null
    private var deviceCode: String? = null

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val helixClientId = prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
        val gqlClientId = if (prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) {
            prefs().getString(C.GQL_CLIENT_ID, "kimne78kx3ncx6brgo4mv6wki5h1ko")
        } else {
            prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp")
        }
        val account = Account.get(this)
        if (account !is NotLoggedIn) {
            TwitchApiHelper.checkedValidation = false
            Account.set(this, null)
            GlobalScope.launch {
                if (!helixClientId.isNullOrBlank() && !account.helixToken.isNullOrBlank()) {
                    try {
                        repository.revoke(helixClientId, account.helixToken)
                    } catch (e: Exception) {

                    }
                }
                if (!gqlClientId.isNullOrBlank() && !account.gqlToken.isNullOrBlank()) {
                    try {
                        repository.revoke(gqlClientId, account.gqlToken)
                    } catch (e: Exception) {

                    }
                }
            }
        }
        if (gqlClientId == "kd1unb4b3q4t58fwlpcbzcbnm76a8fp") {
            prefs().edit {
                putString(C.GQL_CLIENT_ID2, "ue6666qo983tsx6so1t0vnawi233wa")
                putString(C.GQL_REDIRECT2, "https://www.twitch.tv/settings/connections")
            }
        }
        initWebView(helixClientId, prefs().getString(C.GQL_CLIENT_ID2, "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(helixClientId: String?, gqlClientId: String?) {
        val apiSetting = prefs().getString(C.API_LOGIN, "0")?.toInt() ?: 0
        val helixRedirect = prefs().getString(C.HELIX_REDIRECT, "https://localhost")
        val helixScopes = listOf(
            "channel:edit:commercial", // channels/commercial
            "channel:manage:broadcast", // streams/markers
            "channel:manage:moderators", // moderation/moderators
            "channel:manage:raids", // raids
            "channel:manage:vips", // channels/vips
            "channel:moderate",
            "chat:edit",
            "chat:read",
            "moderator:manage:announcements", // chat/announcements
            "moderator:manage:banned_users", // moderation/bans
            "moderator:manage:chat_messages", // moderation/chat
            "moderator:manage:chat_settings", // chat/settings
            "moderator:read:chatters", // chat/chatters
            "moderator:read:followers", // channels/followers
            "user:manage:chat_color", // chat/color
            "user:manage:whispers", // whispers
            "user:read:follows", // streams/followed, channels/followed
        )
        val helixAuthUrl = "https://id.twitch.tv/oauth2/authorize?response_type=token&client_id=${helixClientId}&redirect_uri=${helixRedirect}&scope=${URLEncoder.encode(helixScopes.joinToString(" "), Charsets.UTF_8.name())}"
        val gqlRedirect = prefs().getString(C.GQL_REDIRECT2, "https://www.twitch.tv/")
        with(binding) {
            webViewContainer.visible()
            havingTrouble.setOnClickListener {
                AlertDialog.Builder(this@LoginActivity)
                    .setMessage(getString(R.string.login_problem_solution))
                    .setPositiveButton(R.string.log_in) { _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW, helixAuthUrl.toUri())
                        if (intent.resolveActivity(packageManager) != null) {
                            webView.reload()
                            startActivity(intent)
                        } else {
                            toast(R.string.no_browser_found)
                        }
                    }
                    .setNeutralButton(R.string.to_enter_url) { _, _ ->
                        val editText = EditText(this@LoginActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                val margin = convertDpToPixels(10f)
                                setMargins(margin, 0, margin, 0)
                            }
                        }
                        val dialog = AlertDialog.Builder(this@LoginActivity)
                            .setTitle(R.string.enter_url)
                            .setView(editText)
                            .setPositiveButton(R.string.log_in) { _, _ ->
                                val text = editText.text
                                if (text.isNotEmpty()) {
                                    if (!loginIfValidUrl(text.toString(), helixAuthUrl, helixClientId, gqlRedirect, gqlClientId, 2)) {
                                        shortToast(R.string.invalid_url)
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        dialog.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            clearCookies()
        }
        with(binding.webView) {
            val theme = if (prefs().getBoolean(C.UI_THEME_FOLLOW_SYSTEM, false)) {
                when (resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK)) {
                    Configuration.UI_MODE_NIGHT_YES -> prefs().getString(C.UI_THEME_DARK_ON, "0")!!
                    else -> prefs().getString(C.UI_THEME_DARK_OFF, "2")!!
                }
            } else {
                prefs().getString(C.THEME, "0")!!
            }
            if (theme != "2") {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    WebSettingsCompat.setForceDark(this.settings, WebSettingsCompat.FORCE_DARK_ON)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(this.settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
                }
            }
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    loginIfValidUrl(url, helixAuthUrl, helixClientId, gqlRedirect, gqlClientId, apiSetting)
                    return false
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                    val errorMessage = if (errorCode == -11) {
                        getString(R.string.browser_workaround)
                    } else {
                        getString(R.string.error, "$errorCode $description")
                    }
                    val html = "<html><body><div align=\"center\">$errorMessage</div></body>"
                    loadUrl("about:blank")
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
            if (apiSetting == 1) {
                if (prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) {
                    readHeaders()
                    loadUrl("https://www.twitch.tv/login")
                }
                getGqlAuthUrl(gqlClientId, gqlRedirect)
            } else loadUrl(helixAuthUrl)
        }
    }

    private fun readHeaders() {
        binding.webView.webViewClient = object : RequestInspectorWebViewClient(binding.webView) {
            override fun shouldInterceptRequest(view: WebView, webViewRequest: WebViewRequest): WebResourceResponse? {
                val token = webViewRequest.headers["authorization"]?.takeUnless { it == "undefined" }?.removePrefix("OAuth ")
                if (!token.isNullOrBlank()) {
                    val clientId = webViewRequest.headers["client-id"]
                    val integrityToken = webViewRequest.headers["client-integrity"]
                    val deviceId = webViewRequest.headers["x-device-id"]
                    prefs().edit {
                        putString(C.GQL_CLIENT_ID, clientId ?: "kimne78kx3ncx6brgo4mv6wki5h1ko")
                        putString(C.INTEGRITY_TOKEN, integrityToken)
                        putLong(C.INTEGRITY_EXPIRATION, System.currentTimeMillis() + 57600000)
                        putString(C.DEVICE_ID, deviceId)
                    }
                    loginIfValidUrl("token=${token}&", "", null, null, clientId, 1)
                }
                return super.shouldInterceptRequest(view, webViewRequest)
            }
        }
    }

    private fun getGqlAuthUrl(gqlClientId: String?, gqlRedirect: String?) {
        lifecycleScope.launch {
            try {
                val response = repository.getDeviceCode("client_id=${gqlClientId}&scopes=channel_read+chat%3Aread+user_blocks_edit+user_blocks_read+user_follows_edit+user_read".toRequestBody())
                deviceCode = response.deviceCode
                val gqlAuthUrl = "https://id.twitch.tv/oauth2/authorize?client_id=${gqlClientId}&device_code=${deviceCode}&force_verify=true&redirect_uri=${gqlRedirect}&response_type=device_grant_trigger&scope=channel_read chat:read user_blocks_edit user_blocks_read user_follows_edit user_read"
                binding.webView.loadUrl(gqlAuthUrl)
                binding.webViewContainer.visible()
                binding.progressBar.gone()
            } catch (e: Exception) {
                if (!helixToken.isNullOrBlank() || !gqlToken.isNullOrBlank()) {
                    TwitchApiHelper.checkedValidation = true
                    Account.set(this@LoginActivity, LoggedIn(userId, userLogin, helixToken, gqlToken))
                }
                setResult(RESULT_OK)
                finish()
            }
        }
    }

/*    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }*/

    private fun loginIfValidUrl(url: String, helixAuthUrl: String, helixClientId: String?, gqlRedirect: String?, gqlClientId: String?, apiSetting: Int): Boolean {
        with(binding) {
            return if (((apiSetting == 0 && tokens.count() == 1) || apiSetting == 1) && url == gqlRedirect && !prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) {
                lifecycleScope.launch {
                    try {
                        val response = repository.getToken("client_id=${gqlClientId}&device_code=${deviceCode}&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code".toRequestBody())
                        loginIfValidUrl("token=${response.token}&", helixAuthUrl, helixClientId, gqlRedirect, gqlClientId, apiSetting)
                    } catch (e: Exception) {
                        if (!helixToken.isNullOrBlank() || !gqlToken.isNullOrBlank()) {
                            TwitchApiHelper.checkedValidation = true
                            Account.set(this@LoginActivity, LoggedIn(userId, userLogin, helixToken, gqlToken))
                        }
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                true
            } else {
                val matcher = tokenPattern.matcher(url)
                if (matcher.find()) {
                    webViewContainer.gone()
                    progressBar.visible()
                    val token = matcher.group(1)
                    if (!token.isNullOrBlank() && !tokens.contains(token)) {
                        tokens.add(token)
                        lifecycleScope.launch {
                            try {
                                val api = when {
                                    (apiSetting == 0 && tokens.count() == 1) || (apiSetting == 2) -> C.HELIX
                                    else -> C.GQL
                                }
                                val response = repository.validate(
                                    when (api) {
                                        C.HELIX -> TwitchApiHelper.addTokenPrefixHelix(token)
                                        else -> TwitchApiHelper.addTokenPrefixGQL(token)
                                    })
                                if (!response?.clientId.isNullOrBlank() && response?.clientId ==
                                    when (api) {
                                        C.HELIX -> helixClientId
                                        else -> gqlClientId
                                    }) {
                                    response?.userId?.let { userId = it }
                                    response?.login?.let { userLogin = it }
                                    when (api) {
                                        C.HELIX -> helixToken = token
                                        C.GQL -> gqlToken = token
                                    }
                                    if ((apiSetting == 0 && tokens.count() == 2) || (apiSetting == 1) || (apiSetting == 2)) {
                                        TwitchApiHelper.checkedValidation = true
                                        Account.set(this@LoginActivity, LoggedIn(userId, userLogin, helixToken, gqlToken))
                                        setResult(RESULT_OK)
                                        finish()
                                    }
                                } else {
                                    throw IOException()
                                }
                            } catch (e: Exception) {
                                toast(R.string.connection_error)
                                if ((apiSetting == 0 && tokens.count() == 2) || (apiSetting == 1) || (apiSetting == 2)) {
                                    clearCookies()
                                    tokens = mutableListOf()
                                    userId = null
                                    userLogin = null
                                    helixToken = null
                                    gqlToken = null
                                    webViewContainer.visible()
                                    progressBar.gone()
                                    if ((prefs().getString(C.API_LOGIN, "0")?.toInt() ?: 0) == 1) {
                                        if (prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) {
                                            readHeaders()
                                            webView.loadUrl("https://www.twitch.tv/login")
                                        }
                                        getGqlAuthUrl(gqlClientId, gqlRedirect)
                                    } else webView.loadUrl(helixAuthUrl)
                                }
                            }
                            if (apiSetting == 0 && tokens.count() == 1) {
                                if (prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) {
                                    readHeaders()
                                    webView.loadUrl("https://www.twitch.tv/login")
                                } else getGqlAuthUrl(gqlClientId, gqlRedirect)
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onDestroy() {
        binding.webView.loadUrl("about:blank")
        super.onDestroy()
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
    }
}