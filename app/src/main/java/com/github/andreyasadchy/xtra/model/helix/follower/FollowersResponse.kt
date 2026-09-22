package com.github.andreyasadchy.xtra.model.helix.follower

import com.github.andreyasadchy.xtra.model.helix.Pagination
import kotlinx.serialization.Serializable

@Serializable
class FollowersResponse(
    val data: List<Follower>,
    val pagination: Pagination? = null,
)