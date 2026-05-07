package com.github.andreyasadchy.xtra

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import java.io.InputStream

@GlideModule
class XtraGlideModule : AppGlideModule() {

    override fun isManifestParsingEnabled(): Boolean = false

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val xtraModule = (context as XtraApp).xtraModule
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(xtraModule.okHttpClient.value))
        super.registerComponents(context, glide, registry)
    }
}
