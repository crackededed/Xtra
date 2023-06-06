package com.github.andreyasadchy.xtra.ui.main

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.acsbendi.requestinspectorwebview.RequestInspectorWebViewClient
import com.acsbendi.requestinspectorwebview.WebViewRequest
import com.github.andreyasadchy.xtra.databinding.DialogIntegrityBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import org.json.JSONObject

class IntegrityDialog : DialogFragment() {

    private var _binding: DialogIntegrityBinding? = null
    private val binding get() = _binding!!

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogIntegrityBinding.inflate(layoutInflater)
        val context = requireContext()
        val builder = AlertDialog.Builder(context)
                .setView(binding.root)
        CookieManager.getInstance().removeAllCookies(null)
        val token = TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN]?.removePrefix("OAuth ")
        if (!token.isNullOrBlank()) {
            CookieManager.getInstance().setCookie("https://www.twitch.tv", "auth-token=$token")
        }
        with(binding.webView) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = WebChromeClient()
            webViewClient = object : RequestInspectorWebViewClient(binding.webView) {

                override fun shouldInterceptRequest(view: WebView, webViewRequest: WebViewRequest): WebResourceResponse? {
                    if (!webViewRequest.headers["client-integrity"].isNullOrBlank()) {
                        context.prefs().edit {
                            putLong(C.INTEGRITY_EXPIRATION, System.currentTimeMillis() + 57600000)
                            putString(C.GQL_HEADERS, JSONObject(
                                if (context.prefs().getBoolean(C.GET_ALL_GQL_HEADERS, false)) {
                                    webViewRequest.headers
                                } else {
                                    webViewRequest.headers.filterKeys {
                                        it == C.HEADER_TOKEN || it == C.HEADER_CLIENT_ID || it == "client-integrity" || it == "x-device-id"
                                    }
                                }
                            ).toString())
                        }
                        dismiss()
                    }
                    return super.shouldInterceptRequest(view, webViewRequest)
                }
            }
            loadUrl("https://www.twitch.tv/login")
        }
        return builder.create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        binding.webView.loadUrl("about:blank")
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(fragmentManager: FragmentManager) {
            IntegrityDialog().show(fragmentManager, null)
        }
    }
}