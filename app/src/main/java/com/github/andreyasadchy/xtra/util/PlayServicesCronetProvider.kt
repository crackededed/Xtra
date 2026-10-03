package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import androidx.core.os.use
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import org.chromium.net.ApiVersion
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.ICronetEngineBuilder

@Suppress("unused")
class PlayServicesCronetProvider(context: Context) : CronetProvider(context) {
    private var dynamiteModuleContext: Context? = null
    private var version = "0"

    private fun getDynamiteModule() {
        if (dynamiteModuleContext == null) {
            try {
                mContext.contentResolver.query(
                    Uri.Builder()
                        .scheme("content")
                        .authority("com.google.android.gms.chimera")
                        .path("api")
                        .appendPath("com.google.android.gms.cronet_dynamite")
                        .build(),
                    null, null, null, null
                ).use { cursor ->
                    if (cursor?.moveToFirst() == true) {
                        val loaderVersionIndex = cursor.getColumnIndex("loaderVersion")
                        if (loaderVersionIndex >= 0) {
                            val loaderVersion = cursor.getInt(loaderVersionIndex)
                            val dynamiteClassLoaderPath = cursor.getString(2)
                            val dynamiteClassLoader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                DelegateLastClassLoader(dynamiteClassLoaderPath, ClassLoader.getSystemClassLoader())
                            } else {
                                PathClassLoader(dynamiteClassLoaderPath, ClassLoader.getSystemClassLoader())
                            }
                            val dynamiteLoader = dynamiteClassLoader.loadClass("com.google.android.gms.dynamiteloader.DynamiteLoaderV2")
                                .getConstructor()
                                .newInstance() as IBinder
                            val result = Parcel.obtain().use { parcel ->
                                parcel.writeInterfaceToken("com.google.android.gms.dynamite.IDynamiteLoaderV2")
                                parcel.writeStrongBinder(ObjectWrapper(mContext))
                                parcel.writeString("com.google.android.gms.cronet_dynamite")
                                parcel.writeInt(0)
                                parcel.writeStrongBinder(ObjectWrapper(cursor))
                                Parcel.obtain().use { result ->
                                    dynamiteLoader.transact(if (loaderVersion >= 2) 3 else 2, parcel, result, 0)
                                    result.readException()
                                    result.readStrongBinder()
                                }
                            }
                            result.queryLocalInterface("com.google.android.gms.dynamic.IObjectWrapper")?.asBinder()?.let { objectWrapper ->
                                val field = objectWrapper.javaClass.declaredFields.first()
                                field.isAccessible = true
                                val context = field.get(objectWrapper) as Context
                                val loader = context.classLoader
                                val impl = loader.loadClass("org.chromium.net.impl.ImplVersion")
                                val api = impl
                                    .getMethod("getApiLevel")
                                    .invoke(null) as Int
                                val cronetVersion = impl
                                    .getMethod("getCronetVersion")
                                    .invoke(null) as String
                                version = cronetVersion
                                val builderImpl = loader.loadClass("org.chromium.net.impl.NativeCronetEngineBuilderImpl")
                                    .asSubclass(ICronetEngineBuilder::class.java)
                                    .getConstructor(Context::class.java)
                                    .newInstance(mContext)
                                if (ApiVersion.getApiLevel() <= api && builderImpl != null) {
                                    dynamiteModuleContext = context
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    class ObjectWrapper(val field: Any): Binder(), IInterface {
        init {
            attachInterface(this, "com.google.android.gms.dynamic.IObjectWrapper")
        }

        override fun asBinder(): IBinder {
            return this
        }
    }

    override fun createBuilder(): CronetEngine.Builder {
        getDynamiteModule()
        val loader = dynamiteModuleContext!!.classLoader
        val builderImpl = loader.loadClass("org.chromium.net.impl.NativeCronetEngineBuilderImpl")
            .asSubclass(ICronetEngineBuilder::class.java)
            .getConstructor(Context::class.java)
            .newInstance(mContext)
        return CronetEngine.Builder(builderImpl)
    }

    override fun getName(): String {
        return "Google-Play-Services-Cronet-Provider"
    }

    override fun getVersion(): String {
        getDynamiteModule()
        return version
    }

    override fun isEnabled(): Boolean {
        getDynamiteModule()
        return dynamiteModuleContext != null
    }
}