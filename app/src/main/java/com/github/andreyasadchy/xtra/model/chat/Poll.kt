package com.github.andreyasadchy.xtra.model.chat

class Poll(
    val id: String?,
    val title: String?,
    val status: String?,
    val choices: List<PollChoice>?,
    val totalVotes: Int?,
    val remainingMilliseconds: Int?,
) {
    class PollChoice(
        val title: String?,
        val totalVotes: Int?,
    )
}
