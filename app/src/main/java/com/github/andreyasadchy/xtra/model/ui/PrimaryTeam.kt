package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class PrimaryTeam(
    val id: String? = null,
    val name: String? = null,
    val displayName: String? = null,
) : Parcelable