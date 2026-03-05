package com.github.andreyasadchy.xtra.model.chat

import com.github.andreyasadchy.xtra.util.TwitchApiHelper

class Raid(
    val raidId: String? = null,
    val targetId: String? = null,
    val targetLogin: String? = null,
    val targetName: String? = null,
    val targetProfileImage: String? = null,
    val viewerCount: Int? = null,
    val openStream: Boolean,
) {

    val targetLogo: String?
        get() = TwitchApiHelper.getProfileImage(targetProfileImage)
}