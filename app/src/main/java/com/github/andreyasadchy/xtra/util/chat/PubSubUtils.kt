package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChannelPoll
import com.github.andreyasadchy.xtra.model.chat.ChannelPrediction
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.util.TwitchApiHelper.parseIso8601DateUTC
import org.json.JSONObject

object PubSubUtils {
    fun parsePlaybackMessage(message: JSONObject): PlaybackMessage? {
        val messageType = message.optString("type")
        return when {
            messageType.startsWith("viewcount") -> PlaybackMessage(viewers = if (!message.isNull("viewers")) message.optInt("viewers") else null)
            messageType.startsWith("stream-up") -> PlaybackMessage(true, if (!message.isNull("server_time")) message.optLong("server_time").takeIf { it > 0 } else null)
            messageType.startsWith("stream-down") -> PlaybackMessage(false)
            else -> null
        }
    }

    fun parseStreamInfo(message: JSONObject): StreamInfo {
        return StreamInfo(
            title = if (!message.isNull("status")) message.optString("status").takeIf { it.isNotBlank() } else null,
            gameId = if (!message.isNull("game_id")) message.optInt("game_id").takeIf { it > 0 }?.toString() else null,
            gameName = if (!message.isNull("game")) message.optString("game").takeIf { it.isNotBlank() } else null,
        )
    }

    fun parseRewardMessage(message: JSONObject): ChatMessage {
        val messageData = message.optJSONObject("data")
        val redemption = messageData?.optJSONObject("redemption")
        val user = redemption?.optJSONObject("user")
        val reward = redemption?.optJSONObject("reward")
        val rewardImage = reward?.optJSONObject("image")
        val defaultImage = reward?.optJSONObject("default_image")
        val input = if (redemption?.isNull("user_input") == false) redemption.optString("user_input").takeIf { it.isNotBlank() } else null
        return ChatMessage(
            userId = if (user?.isNull("id") == false) user.optString("id").takeIf { it.isNotBlank() } else null,
            userLogin = if (user?.isNull("login") == false) user.optString("login").takeIf { it.isNotBlank() } else null,
            userName = if (user?.isNull("display_name") == false) user.optString("display_name").takeIf { it.isNotBlank() } else null,
            message = input,
            reward = ChannelPointReward(
                id = if (reward?.isNull("id") == false) reward.optString("id").takeIf { it.isNotBlank() } else null,
                title = if (reward?.isNull("title") == false) reward.optString("title").takeIf { it.isNotBlank() } else null,
                cost = if (reward?.isNull("cost") == false) reward.optInt("cost") else null,
                url1x = if (rewardImage?.isNull("url_1x") == false) rewardImage.optString("url_1x").takeIf { it.isNotBlank() } else null
                    ?: if (defaultImage?.isNull("url_1x") == false) defaultImage.optString("url_1x").takeIf { it.isNotBlank() } else null,
                url2x = if (rewardImage?.isNull("url_2x") == false) rewardImage.optString("url_2x").takeIf { it.isNotBlank() } else null
                    ?: if (defaultImage?.isNull("url_2x") == false) defaultImage.optString("url_2x").takeIf { it.isNotBlank() } else null,
                url4x = if (rewardImage?.isNull("url_4x") == false) rewardImage.optString("url_4x").takeIf { it.isNotBlank() } else null
                    ?: if (defaultImage?.isNull("url_4x") == false) defaultImage.optString("url_4x").takeIf { it.isNotBlank() } else null,
            ),
            timestamp = if (messageData?.isNull("timestamp") == false) messageData.optString("timestamp").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
            fullMsg = message.toString(),
        )
    }

    fun parsePointsEarned(message: JSONObject): PointsEarned {
        val messageData = message.optJSONObject("data")
        val pointGain = messageData?.optJSONObject("point_gain")
        return PointsEarned(
            pointsGained = pointGain?.optInt("total_points"),
            timestamp = if (messageData?.isNull("timestamp") == false) messageData.optString("timestamp").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
            fullMsg = message.toString()
        )
    }

    fun onRaidUpdate(message: JSONObject, openStream: Boolean): Raid? {
        val raid = message.optJSONObject("raid")
        return if (raid != null) {
            Raid(
                raidId = if (!raid.isNull("id")) raid.optString("id").takeIf { it.isNotBlank() } else null,
                targetId = if (!raid.isNull("target_id")) raid.optString("target_id").takeIf { it.isNotBlank() } else null,
                targetLogin = if (!raid.isNull("target_login")) raid.optString("target_login").takeIf { it.isNotBlank() } else null,
                targetName = if (!raid.isNull("target_display_name")) raid.optString("target_display_name").takeIf { it.isNotBlank() } else null,
                targetProfileImage = if (!raid.isNull("target_profile_image")) raid.optString("target_profile_image").takeIf { it.isNotBlank() }?.replace("profile_image-%s", "profile_image-300x300") else null,
                viewerCount = raid.optInt("viewer_count"),
                openStream = openStream
            )
        } else null
    }

    fun onPollUpdate(message: JSONObject): ChannelPoll? {
        val poll = message.optJSONObject("poll")
        return if (poll != null) {
            ChannelPoll(
                pollId = if (!poll.isNull("poll_id")) poll.optString("poll_id").takeIf { it.isNotBlank() } else null,
                ownedBy = if (!poll.isNull("owned_by")) poll.optString("owned_by").takeIf { it.isNotBlank() } else null,
                createdBy = if (!poll.isNull("created_by")) poll.optString("created_by").takeIf { it.isNotBlank() } else null,
                title = if (!poll.isNull("title")) poll.optString("title").takeIf { it.isNotBlank() } else null,
                startedAt = if (!poll.isNull("started_at")) poll.optString("started_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                endedAt = if (!poll.isNull("ended_at")) poll.optString("ended_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                endedBy = if (!poll.isNull("owned_by")) poll.optString("owned_by").takeIf { it.isNotBlank() } else null,
                durationSeconds = if (!poll.isNull("durationSeconds")) poll.optLong("durationSeconds") else null,
                settings = poll.optJSONObject("settings")?.let { settings ->
                    ChannelPoll.PollSettings(
                        multiChoice = settings.optJSONObject("multi_choice")?.let {
                            ChannelPoll.PollSettings.Setting(
                                isEnabled = it.optBoolean("is_enabled"),
                                cost = if (!it.isNull("cost")) it.optLong("cost") else null,
                            )
                        },
                        subscriberOnly = settings.optJSONObject("subscriber_only")?.let {
                            ChannelPoll.PollSettings.Setting(
                                isEnabled = it.optBoolean("is_enabled"),
                                cost = if (!it.isNull("cost")) it.optLong("cost") else null,
                            )
                        },
                        subscriberMultiplier = settings.optJSONObject("subscriber_multiplier")?.let {
                            ChannelPoll.PollSettings.Setting(
                                isEnabled = it.optBoolean("is_enabled"),
                                cost = if (!it.isNull("cost")) it.optLong("cost") else null,
                            )
                        },
                        bitsVotes = settings.optJSONObject("bits_votes")?.let {
                            ChannelPoll.PollSettings.Setting(
                                isEnabled = it.optBoolean("is_enabled"),
                                cost = if (!it.isNull("cost")) it.optLong("cost") else null,
                            )
                        },
                        channelPointsVotes = settings.optJSONObject("channel_points_votes")?.let {
                            ChannelPoll.PollSettings.Setting(
                                isEnabled = it.optBoolean("is_enabled"),
                                cost = if (!it.isNull("cost")) it.optLong("cost") else null,
                            )
                        },
                    )
                },
                status = if (!poll.isNull("status")) poll.optString("status").takeIf { it.isNotBlank() }?.let { safeValueOf<ChannelPoll.Status>(it) } else null,
                choices = poll.optJSONArray("choices")?.let { choicesJSONArray ->
                    val choices = mutableListOf<ChannelPoll.PollChoice>()
                    for (i in 0 until choicesJSONArray.length()) {
                        val choice = choicesJSONArray.getJSONObject(i)
                        choices.add(
                            ChannelPoll.PollChoice(
                                choiceId = if (!choice.isNull("status")) choice.optString("status").takeIf { it.isNotBlank() } else null,
                                title = if (!choice.isNull("title")) choice.optString("title").takeIf { it.isNotBlank() } else null,
                                votes = choice.optJSONObject("votes")?.let {
                                    ChannelPoll.Votes(
                                        total = if (!it.isNull("total")) it.optLong("total") else null,
                                        bits = if (!it.isNull("bits")) it.optLong("bits") else null,
                                        channelPoints = if (!it.isNull("channel_points")) it.optLong("channel_points") else null,
                                        base = if (!it.isNull("base")) it.optLong("base") else null,
                                    )
                                },
                                tokens = choice.optJSONObject("tokens")?.let {
                                    ChannelPoll.Tokens(
                                        bits = if (!it.isNull("bits")) it.optLong("bits") else null,
                                        channelPoints = if (!it.isNull("channel_points")) it.optLong("channel_points") else null,
                                    )
                                },
                                totalVoters = if (!choice.isNull("total_voters")) choice.optInt("total_voters") else null,
                            )
                        )
                    }
                    choices
                },
                votes = poll.optJSONObject("votes")?.let {
                    ChannelPoll.Votes(
                        total = if (!it.isNull("total")) it.optLong("total") else null,
                        bits = if (!it.isNull("bits")) it.optLong("bits") else null,
                        channelPoints = if (!it.isNull("channel_points")) it.optLong("channel_points") else null,
                        base = if (!it.isNull("base")) it.optLong("base") else null,
                    )
                },
                tokens = poll.optJSONObject("tokens")?.let {
                    ChannelPoll.Tokens(
                        bits = if (!it.isNull("bits")) it.optLong("bits") else null,
                        channelPoints = if (!it.isNull("channel_points")) it.optLong("channel_points") else null,
                    )
                },
                totalVoters = if (!poll.isNull("total_voters")) poll.optInt("total_voters") else null,
                remainingDurationMilliseconds = if (!poll.isNull("remaining_duration_milliseconds")) poll.optLong("remaining_duration_milliseconds") else null,
                topContributor = poll.optJSONObject("top_contributor")?.let {
                    ChannelPoll.Contributor(
                        userId = if (!it.isNull("user_id")) it.optString("user_id").takeIf { it.isNotBlank() } else null,
                        displayName = if (!it.isNull("display_name")) it.optString("display_name").takeIf { it.isNotBlank() } else null,
                        bitsContributed = if (!it.isNull("bits_contributed")) it.optLong("bits_contributed") else null,
                        channelPointsContributed = if (!it.isNull("channel_points_contributed")) it.optLong("channel_points_contributed") else null,
                    )
                },
                topBitsContributor = poll.optJSONObject("top_bits_contributor")?.let {
                    ChannelPoll.Contributor(
                        userId = if (!it.isNull("user_id")) it.optString("user_id").takeIf { it.isNotBlank() } else null,
                        displayName = if (!it.isNull("display_name")) it.optString("display_name").takeIf { it.isNotBlank() } else null,
                        bitsContributed = if (!it.isNull("bits_contributed")) it.optLong("bits_contributed") else null,
                        channelPointsContributed = if (!it.isNull("channel_points_contributed")) it.optLong("channel_points_contributed") else null,
                    )
                },
                topChannelPointsContributor = poll.optJSONObject("top_channel_points_contributor")?.let {
                    ChannelPoll.Contributor(
                        userId = if (!it.isNull("user_id")) it.optString("user_id").takeIf { it.isNotBlank() } else null,
                        displayName = if (!it.isNull("display_name")) it.optString("display_name").takeIf { it.isNotBlank() } else null,
                        bitsContributed = if (!it.isNull("bits_contributed")) it.optLong("bits_contributed") else null,
                        channelPointsContributed = if (!it.isNull("channel_points_contributed")) it.optLong("channel_points_contributed") else null,
                    )
                },
            )
        } else null
    }

    fun onPredictionUpdate(message: JSONObject): ChannelPrediction? {
        val prediction = message.optJSONObject("event")
        return if (prediction != null) {
            ChannelPrediction(
                id = if (!prediction.isNull("id")) prediction.optString("id").takeIf { it.isNotBlank() } else null,
                channelId = if (!prediction.isNull("channel_id")) prediction.optString("channel_id").takeIf { it.isNotBlank() } else null,
                createdAt = if (!prediction.isNull("created_at")) prediction.optString("created_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                createdBy = prediction.optJSONObject("created_by")?.let { by ->
                    ChannelPrediction.PredictionTrigger(
                        type = if (!by.isNull("type")) by.optString("type").takeIf { it.isNotBlank() } else null,
                        userId = if (!by.isNull("user_id")) by.optString("user_id").takeIf { it.isNotBlank() } else null,
                        userDisplayName = if (!by.isNull("user_display_name")) by.optString("user_display_name").takeIf { it.isNotBlank() } else null,
                        extensionClientId = if (!by.isNull("extension_client_id")) by.optString("extension_client_id").takeIf { it.isNotBlank() } else null,
                    )
                },
                endedAt = if (!prediction.isNull("ended_at")) prediction.optString("ended_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                endedBy = prediction.optJSONObject("ended_by")?.let { by ->
                    ChannelPrediction.PredictionTrigger(
                        type = if (!by.isNull("type")) by.optString("type").takeIf { it.isNotBlank() } else null,
                        userId = if (!by.isNull("user_id")) by.optString("user_id").takeIf { it.isNotBlank() } else null,
                        userDisplayName = if (!by.isNull("user_display_name")) by.optString("user_display_name").takeIf { it.isNotBlank() } else null,
                        extensionClientId = if (!by.isNull("extension_client_id")) by.optString("extension_client_id").takeIf { it.isNotBlank() } else null,
                    )
                },
                lockedAt = if (!prediction.isNull("locked_at")) prediction.optString("locked_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                lockedBy = prediction.optJSONObject("locked_by")?.let { by ->
                    ChannelPrediction.PredictionTrigger(
                        type = if (!by.isNull("type")) by.optString("type").takeIf { it.isNotBlank() } else null,
                        userId = if (!by.isNull("user_id")) by.optString("user_id").takeIf { it.isNotBlank() } else null,
                        userDisplayName = if (!by.isNull("user_display_name")) by.optString("user_display_name").takeIf { it.isNotBlank() } else null,
                        extensionClientId = if (!by.isNull("extension_client_id")) by.optString("extension_client_id").takeIf { it.isNotBlank() } else null,
                    )
                },
                outcomes = prediction.optJSONArray("outcomes")?.let { outcomesJSONArray ->
                    val outcomes = mutableListOf<ChannelPrediction.PredictionOutcome>()
                    for (i in 0 until outcomesJSONArray.length()) {
                        val outcome = outcomesJSONArray.getJSONObject(i)

                        val topPredictors = mutableListOf<ChannelPrediction.Prediction>()
                        outcome.optJSONArray("top_predictors")?.let { topPredictorsJSONArray ->
                            for (j in 0 until topPredictorsJSONArray.length()) {
                                val topPredictor = topPredictorsJSONArray.getJSONObject(i)
                                topPredictors.add(ChannelPrediction.Prediction(
                                    id = if (!topPredictor.isNull("id")) topPredictor.optString("id").takeIf { it.isNotBlank() } else null,
                                    eventId = if (!topPredictor.isNull("event_id")) topPredictor.optString("event_id").takeIf { it.isNotBlank() } else null,
                                    outcomeId = if (!topPredictor.isNull("outcome_id")) topPredictor.optString("outcome_id").takeIf { it.isNotBlank() } else null,
                                    channelId = if (!topPredictor.isNull("channel_id")) topPredictor.optString("channel_id").takeIf { it.isNotBlank() } else null,
                                    points = if (!topPredictor.isNull("points")) topPredictor.optInt("points") else null,
                                    predictedAt = if (!topPredictor.isNull("predicted_at")) topPredictor.optString("predicted_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                                    updatedAt = if (!topPredictor.isNull("updated_at")) topPredictor.optString("updated_at").takeIf { it.isNotBlank() }?.let { parseIso8601DateUTC(it) } else null,
                                    userId = if (!topPredictor.isNull("user_id")) topPredictor.optString("user_id").takeIf { it.isNotBlank() } else null,
                                    result =  topPredictor.optJSONObject("result")?.let { result ->
                                        ChannelPrediction.PredictionResult(
                                            type = if (!result.isNull("type")) result.optString("type").takeIf { it.isNotBlank() } else null,
                                            pointsWon = if (!result.isNull("points_won")) result.optInt("points_won") else null,
                                            isAcknowledged = result.optBoolean("is_acknowledged"),
                                        )
                                    },
                                    userDisplayName = if (!topPredictor.isNull("user_display_name")) topPredictor.optString("user_display_name").takeIf { it.isNotBlank() } else null,
                                ))
                            }
                        }

                        outcomes.add(
                            ChannelPrediction.PredictionOutcome(
                                id = if (!outcome.isNull("id")) outcome.optString("id").takeIf { it.isNotBlank() } else null,
                                color = if (!outcome.isNull("color")) outcome.optString("color").takeIf { it.isNotBlank() } else null,
                                title = if (!outcome.isNull("title")) outcome.optString("title").takeIf { it.isNotBlank() } else null,
                                totalPoints = if (!outcome.isNull("total_points")) outcome.optInt("total_points") else null,
                                totalUsers = if (!outcome.isNull("total_points")) outcome.optInt("total_users") else null,
                                topPredictors = topPredictors,
                                badge = outcome.optJSONObject("badge")?.let { badge ->
                                    Badge(
                                        version = badge.optString("version"),
                                        setId = badge.optString("set_id"),
                                    )
                                },
                            )
                        )
                    }
                    outcomes
                },
                predictionWindowSeconds = if (!prediction.isNull("prediction_window_seconds")) prediction.optInt("prediction_window_seconds") else null,
                status = if (!prediction.isNull("status")) prediction.optString("status").takeIf { it.isNotBlank() }?.let { safeValueOf<ChannelPrediction.Status>(it) } else null,
                title = if (!prediction.isNull("title")) prediction.optString("title").takeIf { it.isNotBlank() } else null,
                winningOutcomeId = if (!prediction.isNull("winning_outcome_id")) prediction.optString("winning_outcome_id").takeIf { it.isNotBlank() } else null,
            )
        } else null
    }

    inline fun <reified T : Enum<T>> safeValueOf(type: String?): T? {
        return T::class.java.enumConstants?.firstOrNull { it.name == type?.uppercase() }
    }

    class PlaybackMessage(
        val live: Boolean? = null,
        val serverTime: Long? = null,
        val viewers: Int? = null,
    )

    class StreamInfo(
        val title: String? = null,
        val gameId: String? = null,
        val gameName: String? = null,
    )

    class PointsEarned(
        val pointsGained: Int? = null,
        val timestamp: Long? = null,
        val fullMsg: String? = null,
    )
}
