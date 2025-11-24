package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class TeamMember(
    val id: String,
    val displayName: String,
    val login: String,
    val profileImageURL: String,
    val stream: Stream? = null
) : Parcelable {}