package com.github.andreyasadchy.xtra

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.multidex.MultiDex
import org.conscrypt.Conscrypt
import java.security.Security

class XtraApp : Application() {

    companion object {
        lateinit var INSTANCE: Application
    }

    lateinit var xtraModule: XtraModule

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        xtraModule = XtraModule(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val conscrypt = Conscrypt.newProvider()
            Security.insertProviderAt(conscrypt, 1)
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (BuildConfig.DEBUG) {
            MultiDex.install(this)
        }
    }
}
