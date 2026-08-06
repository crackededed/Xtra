package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class WatchStreakResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val channel: Channel? = null,
    )

    @Serializable
    class Channel(
        val self: Self? = null,
    )

    @Serializable
    class Self(
        val watchStreakMilestone: Milestone? = null,
    )

    @Serializable
    class Milestone(
        val watchStreakMilestone: MilestoneValue? = null,
        val watchStreakThreshold: JsonElement? = null,
        val watchStreakCopoBonus: JsonElement? = null,
    )

    @Serializable
    class MilestoneValue(
        val id: String? = null,
        val value: JsonElement? = null,
        val shareStatus: String? = null,
    )
}
