plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.apollo)
}

kotlin {
    jvmToolchain(21)
}

android {
    signingConfigs {
        getByName("debug") {
            keyAlias = "debug"
            keyPassword = "123456"
            storeFile = file("debug-keystore.jks")
            storePassword = "123456"
        }
    }
    namespace = "com.github.andreyasadchy.xtra"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.andreyasadchy.xtra"
        minSdk = 23
        targetSdk = 36
        versionCode = 121
        versionName = "2.51.1"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "ContentDescription"
    }
    packaging.jniLibs.excludes.addAll(listOf(
        "lib/x86/libtranslate_jni.so",
        "lib/x86/liblanguage_id_l2c_jni.so",
        "lib/x86_64/libtranslate_jni.so",
        "lib/x86_64/liblanguage_id_l2c_jni.so",
        "lib/armeabi-v7a/libtranslate_jni.so",
        "lib/armeabi-v7a/liblanguage_id_l2c_jni.so",
    ))
}

dependencies {
    implementation("com.google.android.gms:play-services-cronet:18.1.0")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    implementation(libs.material)

    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.coordinatorlayout)
    implementation(libs.core.ktx)
    implementation(libs.customview)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.paging.runtime)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.paging)
    implementation(libs.swiperefreshlayout)
    implementation(libs.viewpager2)
    implementation(libs.webkit)
    implementation(libs.work.runtime)

    implementation(libs.cronet.api)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.conscrypt)
    implementation(libs.serialization.json)
    implementation(libs.apollo.api)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)

    implementation(libs.glide)
    ksp(libs.glide.ksp)
    implementation(libs.glide.okhttp)
    implementation(libs.glide.webpdecoder)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.extension.compiler)

    implementation(libs.coroutines)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

apollo {
    service("service") {
        packageName.set("com.github.andreyasadchy.xtra")
    }
}

// Delete large build log files from ~/.gradle/daemon/X.X/daemon-XXX.out.log
// Source: https://discuss.gradle.org/t/gradle-daemon-produces-a-lot-of-logs/9905
File("${project.gradle.gradleUserHomeDir.absolutePath}/daemon/${project.gradle.gradleVersion}").listFiles()?.forEach {
    if (it.name.endsWith(".out.log")) {
        // println("Deleting gradle log file: $it") // Optional debug output
        it.delete()
    }
}