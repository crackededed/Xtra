package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class SocialMedia(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val url: String? = null
) : Parcelable