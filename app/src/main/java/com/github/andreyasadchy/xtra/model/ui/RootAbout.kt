package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class RootAbout(
    val id: String? = null,
    val description: String? = null,
    val socialMedias: List<SocialMedia>? = null,
    val primaryTeam: PrimaryTeam? = null,
) : Parcelable