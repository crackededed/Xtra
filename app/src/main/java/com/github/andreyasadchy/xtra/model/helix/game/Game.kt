package com.github.andreyasadchy.xtra.model.helix.game

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Game(
    val id: String? = null,
    val name: String? = null,
    @SerialName("box_art_url")
    val boxArtURL: String? = null,
)