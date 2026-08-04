package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChannelPointsBinding
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.ui.ChannelPoints
import com.github.andreyasadchy.xtra.model.ui.WatchStreak
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToInt

class ChannelPointsDialog : DialogFragment() {

    interface Listener {
        fun channelPointsFlow(): StateFlow<ChannelPoints?>
        fun watchStreakFlow(): StateFlow<WatchStreak?>
        fun activePollFlow(): StateFlow<Poll?>
        fun activePredictionFlow(): StateFlow<Prediction?>
    }

    companion object {
        const val TAG = "channelPointsDialog"
    }

    private var _binding: DialogChannelPointsBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: Listener

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? Listener
            ?: error("ChannelPointsDialog must be shown by a ChannelPointsDialog.Listener")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogChannelPointsBinding.inflate(layoutInflater)
        binding.close.setOnClickListener { dismiss() }
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .create()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    listener.channelPointsFlow(),
                    listener.watchStreakFlow(),
                    listener.activePollFlow(),
                    listener.activePredictionFlow(),
                ) { channelPoints, watchStreak, poll, prediction ->
                    DialogState(channelPoints, watchStreak, poll, prediction)
                }.collectLatest(::render)
            }
        }
        return dialog
    }

    private fun render(state: DialogState) {
        val numberFormat = NumberFormat.getInstance()
        val points = state.channelPoints
        binding.balance.text = points?.let {
            getString(R.string.channel_points_current_balance, numberFormat.format(it.balance))
        } ?: getString(R.string.channel_points_unavailable)

        renderWatchStreak(state.watchStreak, points, numberFormat)
        renderRewards(points, numberFormat)
        renderVoting(state.poll, state.prediction, numberFormat)
    }

    private fun renderWatchStreak(
        streak: WatchStreak?,
        points: ChannelPoints?,
        numberFormat: NumberFormat,
    ) {
        binding.streakNoticeCard.isVisible = false
        binding.streakSummary.isVisible = streak != null
        binding.streakLabel.isVisible = streak != null
        binding.streakStatusLabel.isVisible = false
        binding.streakProgressCard.isVisible = false
        binding.streakEmpty.isVisible = streak == null

        if (streak != null) {
            binding.streakCount.text = numberFormat.format(streak.streakCount)
            val next = streak.nextMilestone?.takeIf { it > 0 }
            if (next != null) {
                val distance = (next - streak.streakCount).coerceAtLeast(0)
                binding.streakNotice.text = if (distance > 0) {
                    getString(R.string.channel_points_streak_notice, distance, next)
                } else {
                    getString(R.string.channel_points_streak_reached)
                }
                binding.streakNoticeCard.isVisible = true
                binding.streakStatusLabel.isVisible = true
                binding.streakStatusLabel.setText(
                    if (distance > 0) {
                        R.string.channel_points_streak_in_progress
                    } else {
                        R.string.channel_points_streak_reached
                    },
                )
                binding.streakProgressCard.isVisible = true
                binding.streakProgressTitle.text = getString(
                    R.string.channel_points_streak_milestone,
                    next,
                )
                binding.streakProgressValue.text = getString(
                    R.string.channel_points_streak_progress,
                    streak.streakCount.coerceAtMost(next),
                    next,
                )
                binding.streakProgress.max = 100
                binding.streakProgress.progress = ((streak.streakCount.toDouble() / next) * 100)
                    .roundToInt()
                    .coerceIn(0, 100)
                binding.streakDescription.text = streak.rewardPoints?.let {
                    getString(
                        R.string.channel_points_streak_description,
                        next,
                        numberFormat.format(it),
                    )
                } ?: getString(R.string.channel_points_streak_description_no_reward, next)
            }
        }

        val streakRewards = points?.watchStreakRewards.orEmpty()
        binding.streakRewardsTitle.isVisible = streakRewards.isNotEmpty()
        binding.streakRewards.isVisible = streakRewards.isNotEmpty()
        binding.streakRewards.removeAllViews()
        streakRewards.forEach { reward ->
            addRow(
                binding.streakRewards,
                reward.streakLength?.let {
                    getString(
                        R.string.channel_points_streak_reward,
                        if (it >= 5) "5+" else it.toString(),
                        numberFormat.format(reward.points),
                    )
                } ?: getString(
                    R.string.channel_points_streak_reward_unknown,
                    numberFormat.format(reward.points),
                ),
            )
        }
    }

    private fun renderRewards(points: ChannelPoints?, numberFormat: NumberFormat) {
        val rewards = points?.rewards.orEmpty()
        binding.rewardsTitle.isVisible = rewards.isNotEmpty()
        binding.rewardsList.isVisible = rewards.isNotEmpty()
        binding.rewardsList.removeAllViews()
        rewards.forEach { reward ->
            addRow(
                binding.rewardsList,
                buildString {
                    append(getString(R.string.channel_points_reward, reward.title, numberFormat.format(reward.cost)))
                    if (!reward.prompt.isNullOrBlank()) {
                        append('\n')
                        append(getString(R.string.channel_points_reward_prompt, reward.prompt))
                    }
                },
            )
        }
    }

    private fun renderVoting(poll: Poll?, prediction: Prediction?, numberFormat: NumberFormat) {
        binding.votingList.removeAllViews()
        poll?.let {
            addRow(binding.votingList, getString(R.string.channel_points_poll, it.title.orEmpty()))
            val totalVotes = max(it.totalVotes ?: 0, 1)
            it.choices.orEmpty().forEach { choice ->
                addRow(
                    binding.votingList,
                    getString(
                        R.string.poll_choice,
                        (((choice.totalVotes ?: 0).toLong() * 100.0) / totalVotes).roundToInt(),
                        numberFormat.format(choice.totalVotes ?: 0),
                        choice.title,
                    ),
                )
            }
        }
        prediction?.let {
            addRow(binding.votingList, getString(R.string.channel_points_prediction, it.title.orEmpty()))
            val totalPoints = max(it.outcomes.orEmpty().sumOf { outcome -> outcome.totalPoints ?: 0 }, 1)
            it.outcomes.orEmpty().forEach { outcome ->
                addRow(
                    binding.votingList,
                    getString(
                        R.string.prediction_outcome,
                        (((outcome.totalPoints ?: 0).toLong() * 100.0) / totalPoints).roundToInt(),
                        numberFormat.format(outcome.totalPoints ?: 0),
                        numberFormat.format(outcome.totalUsers ?: 0),
                        outcome.title,
                    ),
                )
            }
        }
        binding.votingTitle.isVisible = binding.votingList.childCount > 0
        binding.votingList.isVisible = binding.votingList.childCount > 0
    }

    private fun addRow(container: LinearLayout, text: CharSequence) {
        val density = resources.displayMetrics.density
        val padding = (12 * density).roundToInt()
        val bottomMargin = (4 * density).roundToInt()
        container.addView(TextView(requireContext()).apply {
            this.text = text
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.bg_channel_points_row)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                this.bottomMargin = bottomMargin
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class DialogState(
        val channelPoints: ChannelPoints?,
        val watchStreak: WatchStreak?,
        val poll: Poll?,
        val prediction: Prediction?,
    )
}
