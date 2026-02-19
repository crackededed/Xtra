package com.github.andreyasadchy.xtra.model.chat

class Emote(
    val name: String? = null,
    val localData: Pair<Long, Int>? = null,
    val url1x: String? = null,
    val url2x: String? = null,
    val url3x: String? = null,
    val url4x: String? = null,
    val format: String? = null,
    val isAnimated: Boolean = true,
    val isOverlayEmote: Boolean = false,
    val source: Int? = null,
) {
    val thirdParty = source == PERSONAL_STV || source == CHANNEL_STV || source == CHANNEL_BTTV || source == CHANNEL_FFZ || source == GLOBAL_STV || source == GLOBAL_BTTV || source == GLOBAL_FFZ

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Emote || name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return ":$name"
    }

    companion object {
        const val PERSONAL_STV = 0
        const val CHANNEL_STV = 1
        const val CHANNEL_BTTV = 2
        const val CHANNEL_FFZ = 3
        const val GLOBAL_STV = 4
        const val GLOBAL_BTTV = 5
        const val GLOBAL_FFZ = 6
    }
}