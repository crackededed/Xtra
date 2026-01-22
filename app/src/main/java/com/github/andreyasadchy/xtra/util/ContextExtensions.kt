package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.use
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.github.andreyasadchy.xtra.R
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.chromium.net.CronetException
import org.chromium.net.UrlResponseInfo
import org.chromium.net.apihelpers.ByteArrayCronetCallback
import org.chromium.net.apihelpers.CronetRequestCompletionListener
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun Context.prefs(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

fun Context.tokenPrefs(): SharedPreferences = getSharedPreferences("prefs2", Context.MODE_PRIVATE)

fun Activity.applyTheme() {
    // On Android 15, wrong language is used when multiple languages are set in device settings
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val lang = AppCompatDelegate.getApplicationLocales()
        resources.configuration.setLocale(
            if (!lang.isEmpty) {
                Locale.forLanguageTag(lang.toLanguageTags())
            } else {
                Locale.getDefault()
            }
        )
    }
    val theme = if (prefs().getBoolean(C.UI_THEME_FOLLOW_SYSTEM, false)) {
        when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> prefs().getString(C.UI_THEME_DARK_ON, "0") ?: "0"
            else -> prefs().getString(C.UI_THEME_DARK_OFF, "2") ?: "2"
        }
    } else {
        prefs().getString(C.THEME, "0") ?: "0"
    }
    if (prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
        val reducedPadding = prefs().getBoolean(C.UI_THEME_REDUCED_PADDING, false)
        val compactText = prefs().getBoolean(C.UI_THEME_COMPACT_TEXT, false)
        when (prefs().getString(C.UI_THEME_ROUNDED_CORNERS, "0")) {
            "1" -> {
                when {
                    reducedPadding && compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCornersReducedPaddingCompactText
                                "6" -> R.style.AmoledThemeSmallCornersReducedPaddingCompactText
                                "5" -> R.style.LightThemeSmallCornersReducedPaddingCompactText
                                "1" -> R.style.AmoledThemeSmallCornersReducedPaddingCompactText
                                "2" -> R.style.LightThemeSmallCornersReducedPaddingCompactText
                                "3" -> R.style.BlueThemeSmallCornersReducedPaddingCompactText
                                else -> R.style.DarkThemeSmallCornersReducedPaddingCompactText
                            }
                        )
                    }
                    reducedPadding -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCornersReducedPadding
                                "6" -> R.style.AmoledThemeSmallCornersReducedPadding
                                "5" -> R.style.LightThemeSmallCornersReducedPadding
                                "1" -> R.style.AmoledThemeSmallCornersReducedPadding
                                "2" -> R.style.LightThemeSmallCornersReducedPadding
                                "3" -> R.style.BlueThemeSmallCornersReducedPadding
                                else -> R.style.DarkThemeSmallCornersReducedPadding
                            }
                        )
                    }
                    compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCornersCompactText
                                "6" -> R.style.AmoledThemeSmallCornersCompactText
                                "5" -> R.style.LightThemeSmallCornersCompactText
                                "1" -> R.style.AmoledThemeSmallCornersCompactText
                                "2" -> R.style.LightThemeSmallCornersCompactText
                                "3" -> R.style.BlueThemeSmallCornersCompactText
                                else -> R.style.DarkThemeSmallCornersCompactText
                            }
                        )
                    }
                    else -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeSmallCorners
                                "6" -> R.style.AmoledThemeSmallCorners
                                "5" -> R.style.LightThemeSmallCorners
                                "1" -> R.style.AmoledThemeSmallCorners
                                "2" -> R.style.LightThemeSmallCorners
                                "3" -> R.style.BlueThemeSmallCorners
                                else -> R.style.DarkThemeSmallCorners
                            }
                        )
                    }
                }
            }
            "2" -> {
                when {
                    reducedPadding && compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCornersReducedPaddingCompactText
                                "6" -> R.style.AmoledThemeNoCornersReducedPaddingCompactText
                                "5" -> R.style.LightThemeNoCornersReducedPaddingCompactText
                                "1" -> R.style.AmoledThemeNoCornersReducedPaddingCompactText
                                "2" -> R.style.LightThemeNoCornersReducedPaddingCompactText
                                "3" -> R.style.BlueThemeNoCornersReducedPaddingCompactText
                                else -> R.style.DarkThemeNoCornersReducedPaddingCompactText
                            }
                        )
                    }
                    reducedPadding -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCornersReducedPadding
                                "6" -> R.style.AmoledThemeNoCornersReducedPadding
                                "5" -> R.style.LightThemeNoCornersReducedPadding
                                "1" -> R.style.AmoledThemeNoCornersReducedPadding
                                "2" -> R.style.LightThemeNoCornersReducedPadding
                                "3" -> R.style.BlueThemeNoCornersReducedPadding
                                else -> R.style.DarkThemeNoCornersReducedPadding
                            }
                        )
                    }
                    compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCornersCompactText
                                "6" -> R.style.AmoledThemeNoCornersCompactText
                                "5" -> R.style.LightThemeNoCornersCompactText
                                "1" -> R.style.AmoledThemeNoCornersCompactText
                                "2" -> R.style.LightThemeNoCornersCompactText
                                "3" -> R.style.BlueThemeNoCornersCompactText
                                else -> R.style.DarkThemeNoCornersCompactText
                            }
                        )
                    }
                    else -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeNoCorners
                                "6" -> R.style.AmoledThemeNoCorners
                                "5" -> R.style.LightThemeNoCorners
                                "1" -> R.style.AmoledThemeNoCorners
                                "2" -> R.style.LightThemeNoCorners
                                "3" -> R.style.BlueThemeNoCorners
                                else -> R.style.DarkThemeNoCorners
                            }
                        )
                    }
                }
            }
            else -> {
                when {
                    reducedPadding && compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeReducedPaddingCompactText
                                "6" -> R.style.AmoledThemeReducedPaddingCompactText
                                "5" -> R.style.LightThemeReducedPaddingCompactText
                                "1" -> R.style.AmoledThemeReducedPaddingCompactText
                                "2" -> R.style.LightThemeReducedPaddingCompactText
                                "3" -> R.style.BlueThemeReducedPaddingCompactText
                                else -> R.style.DarkThemeReducedPaddingCompactText
                            }
                        )
                    }
                    reducedPadding -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeReducedPadding
                                "6" -> R.style.AmoledThemeReducedPadding
                                "5" -> R.style.LightThemeReducedPadding
                                "1" -> R.style.AmoledThemeReducedPadding
                                "2" -> R.style.LightThemeReducedPadding
                                "3" -> R.style.BlueThemeReducedPadding
                                else -> R.style.DarkThemeReducedPadding
                            }
                        )
                    }
                    compactText -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkThemeCompactText
                                "6" -> R.style.AmoledThemeCompactText
                                "5" -> R.style.LightThemeCompactText
                                "1" -> R.style.AmoledThemeCompactText
                                "2" -> R.style.LightThemeCompactText
                                "3" -> R.style.BlueThemeCompactText
                                else -> R.style.DarkThemeCompactText
                            }
                        )
                    }
                    else -> {
                        setTheme(
                            when (theme) {
                                "4" -> R.style.DarkTheme
                                "6" -> R.style.AmoledTheme
                                "5" -> R.style.LightTheme
                                "1" -> R.style.AmoledTheme
                                "2" -> R.style.LightTheme
                                "3" -> R.style.BlueTheme
                                else -> R.style.DarkTheme
                            }
                        )
                    }
                }
            }
        }
        if (theme == "4" || theme == "6" || theme == "5") {
            DynamicColors.applyToActivityIfAvailable(
                this,
                DynamicColorsOptions.Builder().apply {
                    setThemeOverlay(
                        when (theme) {
                            "4" -> R.style.DarkDynamicOverlay
                            "6" -> R.style.AmoledDynamicOverlay
                            "5" -> R.style.LightDynamicOverlay
                            else -> R.style.DarkDynamicOverlay
                        }
                    )
                }.build()
            )
        }
    } else {
        setTheme(
            when (theme) {
                "4" -> R.style.AppCompatDarkTheme
                "6" -> R.style.AppCompatAmoledTheme
                "5" -> R.style.AppCompatLightTheme
                "1" -> R.style.AppCompatAmoledTheme
                "2" -> R.style.AppCompatLightTheme
                "3" -> R.style.AppCompatBlueTheme
                else -> R.style.AppCompatDarkTheme
            }
        )
    }
    val isLightTheme = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
        it.getBoolean(0, false)
    }
    WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = isLightTheme
        isAppearanceLightNavigationBars = isLightTheme
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
    }
}

fun Context.getAlertDialogBuilder(): AlertDialog.Builder {
    return if (prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
        MaterialAlertDialogBuilder(this)
    } else {
        AlertDialog.Builder(this)
    }
}

fun Context.getActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> this.baseContext.getActivity()
        else -> null
    }
}

fun getByteArrayCronetCallback(continuation: Continuation<Pair<UrlResponseInfo, ByteArray>>): ByteArrayCronetCallback {
    return object : ByteArrayCronetCallback() {
        override fun shouldFollowRedirect(info: UrlResponseInfo?, newLocationUrl: String?): Boolean {
            return true
        }
    }.also {
        it.addCompletionListener(object : CronetRequestCompletionListener<ByteArray> {
            override fun onFailed(info: UrlResponseInfo?, exception: CronetException) {
                continuation.resumeWithException(exception)
            }

            override fun onCanceled(info: UrlResponseInfo?) {
                continuation.resumeWithException(IOException("The request was canceled!"))
            }

            override fun onSucceeded(info: UrlResponseInfo, body: ByteArray) {
                continuation.resume(Pair(info, body))
            }
        })
    }
}

val Response.body
    get() = this.body()!!

val Response.request: Request
    get() = this.request()

val Response.code
    get() = this.code()

fun Map<String, String>.toHeaders(): Headers {
    return Headers.of(this)
}

fun String.toRequestBody(contentType: MediaType? = null): RequestBody {
    return RequestBody.create(contentType, toByteArray())
}