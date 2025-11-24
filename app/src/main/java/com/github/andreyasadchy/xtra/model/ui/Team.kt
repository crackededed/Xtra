package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class Team(
    val id: String,
    val backgroundImageURL: String? = null,
    val bannerURL: String? = null,
    val description: String? = null,
    val displayName: String,
    val logoURL: String? = null,
    val ownerId: String,
    val ownerLogin: String,
) : Parcelable {}