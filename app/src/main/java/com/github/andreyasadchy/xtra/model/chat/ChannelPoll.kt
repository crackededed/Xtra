package com.github.andreyasadchy.xtra.model.chat

class ChannelPoll(
    val pollId: String?,
    val ownedBy: String?,
    val createdBy: String?,
    val title: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    val endedBy: String?,
    val durationSeconds: Long?,
    val settings: PollSettings?,
    val status: Status?,
    val choices: List<PollChoice>?,
    val votes: Votes?,
    val tokens: Tokens?,
    val totalVoters: Int?,
    val remainingDurationMilliseconds: Long?,
    val topContributor: Contributor?,
    val topBitsContributor: Contributor?,
    val topChannelPointsContributor: Contributor?,
) {
    class PollSettings(
        val multiChoice: Setting?,
        val subscriberOnly: Setting?,
        val subscriberMultiplier: Setting?,
        val channelPointsVotes: Setting?,
        val bitsVotes: Setting?,
    ) {
        class Setting(
            val isEnabled: Boolean,
            val cost: Long?,
        )
    }

    class PollChoice(
        val choiceId: String?,
        val title: String?,
        val votes: Votes?,
        val tokens: Tokens?,
        val totalVoters: Int?,
    )

    class Votes(
        val total: Long?,
        val bits: Long?,
        val channelPoints: Long?,
        val base: Long?,
    )

    class Tokens(
        val bits: Long?,
        val channelPoints: Long?,
    )

    class Contributor(
        val userId: String?,
        val displayName: String?,
        val bitsContributed: Long?,
        val channelPointsContributed: Long?,
    )

    enum class Status {
        ACTIVE,
        COMPLETED,
        ARCHIVED,
        TERMINATED,
        MODERATED,
    }
}
