package com.github.andreyasadchy.xtra.ui.main

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.acsbendi.requestinspectorwebview.RequestInspectorWebViewClient
import com.acsbendi.requestinspectorwebview.WebViewRequest
import com.github.andreyasadchy.xtra.databinding.DialogIntegrityBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

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
        val token = Account.get(context).gqlToken
        if (!token.isNullOrBlank()) {
            CookieManager.getInstance().setCookie("https://www.twitch.tv", "auth-token=$token")
        }
        with(binding.webView) {
            settings.javaScriptEnabled = true
            webViewClient = object : RequestInspectorWebViewClient(binding.webView) {

                override fun shouldInterceptRequest(view: WebView, webViewRequest: WebViewRequest): WebResourceResponse? {
                    val integrityToken = webViewRequest.headers["client-integrity"]
                    if (!integrityToken.isNullOrBlank()) {
                        val clientId = webViewRequest.headers["client-id"]
                        val deviceId = webViewRequest.headers["x-device-id"]
                        context.prefs().edit {
                            putString(C.GQL_CLIENT_ID, clientId ?: "kimne78kx3ncx6brgo4mv6wki5h1ko")
                            putString(C.INTEGRITY_TOKEN, integrityToken)
                            putLong(C.INTEGRITY_EXPIRATION, System.currentTimeMillis() + 57600000)
                            putString(C.DEVICE_ID, deviceId)
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