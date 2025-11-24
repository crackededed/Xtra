package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class RootAbout(
    val id: String,
    val description: String? = null,
    val socialMedias: List<SocialMedia>? = null,
    val primaryTeam: PrimaryTeam? = null,
) : Parcelable {
    @Parcelize
    class SocialMedia(
        val id: String,
        val name: String,
        val title: String,
        val url: String
    ) : Parcelable
    @Parcelize
    class PrimaryTeam(
        val id: String,
        val name: String,
        val displayName: String,
    ) : Parcelable
}