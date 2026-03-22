package com.github.andreyasadchy.xtra.ui.common

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.webkit.WebViewClientCompat
import com.github.andreyasadchy.xtra.databinding.DialogIntegrityBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import org.json.JSONObject

class IntegrityDialog : DialogFragment() {

    interface CallbackListener {
        fun onIntegrityDialogCallback(callback: String?)
    }

    private var _binding: DialogIntegrityBinding? = null
    private val binding get() = _binding!!
    private var listener: CallbackListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? CallbackListener
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogIntegrityBinding.inflate(layoutInflater)
        val context = requireContext()
        val builder = context.getAlertDialogBuilder()
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
            webViewClient = object : WebViewClientCompat() {

                override fun shouldInterceptRequest(view: WebView, webViewRequest: WebResourceRequest): WebResourceResponse? {
                    if (!webViewRequest.requestHeaders.entries.firstOrNull { it.key.equals("Client-Integrity", true) }?.value.isNullOrBlank()) {
                        context.tokenPrefs().edit {
                            putLong(C.INTEGRITY_EXPIRATION, System.currentTimeMillis() + 57600000)
                            putString(C.GQL_HEADERS, JSONObject(
                                if (context.prefs().getBoolean(C.GET_ALL_GQL_HEADERS, false)) {
                                    webViewRequest.requestHeaders
                                } else {
                                    webViewRequest.requestHeaders.filterKeys {
                                        it.equals(C.HEADER_TOKEN, true) ||
                                                it.equals(C.HEADER_CLIENT_ID, true) ||
                                                it.equals("Client-Integrity", true) ||
                                                it.equals("X-Device-Id", true)
                                    }
                                }
                            ).toString())
                        }
                        listener?.onIntegrityDialogCallback(requireArguments().getString(KEY_CALLBACK))
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
        private const val KEY_CALLBACK = "callback"

        fun show(fragmentManager: FragmentManager, callback: String? = null) {
            IntegrityDialog().apply {
                arguments = Bundle().apply {
                    putString(KEY_CALLBACK, callback)
                }
                show(fragmentManager, null)
            }
        }
    }
}