package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class ChannelPanel(
    val id: String,
    val type: String,
    val title: String? = null,
    val imageURL: String? = null,
    val linkURL: String? = null,
    val description: String? = null,
    val altText: String? = null,
) : Parcelable {}