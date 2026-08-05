package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChannelPointsBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.ui.ChannelPoints
import com.github.andreyasadchy.xtra.model.ui.ChannelPointReward
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRewardInput
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRewardRedemption
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRedemptionResult
import com.github.andreyasadchy.xtra.model.ui.WatchStreak
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
        fun channelName(): String?
        fun channelEmotePickerItems(): List<Emote>
        fun channelEmotePickerUpdates(): Flow<Unit>
        fun channelPointModifiedEmotePickerItems(): List<Emote>
        fun channelPointModifiedEmotePickerUpdates(): Flow<Unit>
        fun redeemChannelPointReward(reward: ChannelPointReward, textInput: String?, emoteId: String?)
        fun channelPointRedemptionFlow(): Flow<ChannelPointRedemptionResult>
    }

    companion object {
        const val TAG = "channelPointsDialog"
        private const val REWARD_COLUMNS = 3
    }

    private var _binding: DialogChannelPointsBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: Listener

    private data class RewardInputContent(
        val input: EditText,
        val view: View,
        val pickerUpdates: Flow<Unit>? = null,
        val refreshPicker: (() -> Unit)? = null,
        val selectedEmoteId: (() -> String?)? = null,
    )

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                listener.channelPointRedemptionFlow().collectLatest(::showRedemptionResult)
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val density = resources.displayMetrics.density
            val maxWidth = (640 * density).roundToInt()
            val screenWidth = resources.displayMetrics.widthPixels
            val width = (screenWidth * 0.94f).roundToInt().coerceAtMost(maxWidth)
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun render(state: DialogState) {
        val numberFormat = NumberFormat.getInstance()
        val points = state.channelPoints
        binding.headerTitle.text = listener.channelName()?.let {
            getString(R.string.channel_points_rewards_for, it)
        } ?: getString(R.string.channel_points_watch_streak)
        binding.balance.text = points?.let {
            getString(R.string.channel_points_current_balance, numberFormat.format(it.balance))
        } ?: getString(R.string.channel_points_unavailable)
        setChannelPointsIcon(
            binding.balanceIcon,
            points?.iconUrl,
            MaterialColors.getColor(binding.balanceIcon, androidx.appcompat.R.attr.colorControlNormal),
        )

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
        rewards.forEachIndexed { index, reward ->
            addRewardCard(index, reward, numberFormat, points?.iconUrl)
        }
    }

    private fun addRewardCard(
        index: Int,
        reward: ChannelPointReward,
        numberFormat: NumberFormat,
        pointsIconUrl: String?,
    ) {
        val density = resources.displayMetrics.density
        val card = MaterialCardView(requireContext()).apply {
            radius = 4 * density
            strokeWidth = 0
            setCardBackgroundColor(parseColor(reward.backgroundColor, R.color.channel_points_reward_default))
            isClickable = true
            isFocusable = true
            contentDescription = reward.title
            setOnClickListener { showRewardRedemptionDialog(reward) }
        }
        val content = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val image = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_channel_points)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        reward.imageUrl?.let { imageUrl ->
            image.imageTintList = null
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(imageUrl)
                    .crossfade(true)
                    .target(image)
                    .build(),
            )
        }
        content.addView(image)
        content.addView(TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36),
            ).apply {
                topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            text = reward.title
            textSize = 12f
        })
        val cost = LinearLayout(requireContext()).apply {
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_channel_points_pill)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        cost.addView(ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            setChannelPointsIcon(this, pointsIconUrl, Color.WHITE)
        })
        cost.addView(TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(3)
            }
            setTextColor(Color.WHITE)
            text = numberFormat.format(reward.cost)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(cost)
        card.addView(content)
        binding.rewardsList.addView(card, android.widget.GridLayout.LayoutParams().apply {
            width = 0
            height = dp(132)
            rowSpec = android.widget.GridLayout.spec(index / REWARD_COLUMNS)
            columnSpec = android.widget.GridLayout.spec(index % REWARD_COLUMNS, 1f)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
    }

    private fun showRewardRedemptionDialog(reward: ChannelPointReward) {
        val inputContent = createRewardInputContent(reward)
        val dialog = requireContext().getAlertDialogBuilder()
            .setTitle(reward.title)
            .setMessage(reward.prompt ?: getString(R.string.channel_points_reward_confirm))
            .apply { inputContent?.let { setView(it.view) } }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.channel_points_reward_redeem) { _, _ ->
                val textInput = inputContent?.input?.text?.toString()
                    ?.takeIf { reward.inputType == ChannelPointRewardInput.TEXT }
                listener.redeemChannelPointReward(
                    reward,
                    textInput,
                    inputContent?.selectedEmoteId?.invoke(),
                )
            }
            .create()
        val pickerUpdatesJob: Job? = inputContent?.let { content ->
            content.pickerUpdates?.let { updates ->
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        updates.collectLatest { content.refreshPicker?.invoke() }
                    }
                }
            }
        }
        dialog.setOnDismissListener { pickerUpdatesJob?.cancel() }
        dialog.show()
        inputContent?.let { content ->
            val redeemButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            fun updateRedeemButton() {
                redeemButton.isEnabled = when (reward.inputType) {
                    ChannelPointRewardInput.NONE -> true
                    ChannelPointRewardInput.TEXT -> content.input.text.isNotBlank()
                    ChannelPointRewardInput.EMOTE -> content.selectedEmoteId?.invoke()?.isNotBlank() == true
                }
            }
            content.input.addTextChangedListener { updateRedeemButton() }
            updateRedeemButton()
        }
    }

    private fun createRewardInputContent(reward: ChannelPointReward): RewardInputContent? {
        return when (reward.inputType) {
            ChannelPointRewardInput.NONE -> null
            ChannelPointRewardInput.TEXT -> {
                val input = EditText(requireContext()).apply {
                    hint = getString(R.string.channel_points_reward_text_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    maxLines = 4
                    gravity = Gravity.TOP or Gravity.START
                }
                RewardInputContent(
                    input = input,
                    view = LinearLayout(requireContext()).apply {
                        setPadding(dp(24), 0, dp(24), 0)
                        addView(input, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                    },
                )
            }
            ChannelPointRewardInput.EMOTE -> {
                var selectedEmoteId: String? = null
                val modified = reward.redemptionType == ChannelPointRewardRedemption.CHOSEN_MODIFIED_SUB_EMOTE
                val input = EditText(requireContext()).apply {
                    hint = getString(R.string.channel_points_reward_emote_hint)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                }
                var allEmotes = if (modified) {
                    listener.channelPointModifiedEmotePickerItems()
                } else {
                    listener.channelEmotePickerItems()
                }
                val adapter = EmotesAdapter(
                    this,
                    { emote ->
                        selectedEmoteId = emote.id
                        input.setText(emote.name.orEmpty())
                        input.setSelection(input.text.length)
                    },
                    requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                    requireContext().prefs().getString(C.CHAT_IMAGE_LIBRARY, "0"),
                )
                val picker = RecyclerView(requireContext()).apply {
                    itemAnimator = null
                    this.adapter = adapter
                    layoutManager = GridAutofitLayoutManager(requireContext(), dp(50))
                }
                fun updatePicker() {
                    val query = input.text.toString()
                    adapter.submitList(
                        allEmotes.filter { it.name.orEmpty().contains(query, ignoreCase = true) },
                    )
                }
                val refreshPicker: () -> Unit = {
                    allEmotes = if (modified) {
                        listener.channelPointModifiedEmotePickerItems()
                    } else {
                        listener.channelEmotePickerItems()
                    }
                    updatePicker()
                }
                input.addTextChangedListener { text ->
                    selectedEmoteId = allEmotes.firstOrNull {
                        it.name.equals(text?.toString(), ignoreCase = true)
                    }?.id
                    updatePicker()
                }
                updatePicker()
                RewardInputContent(
                    input = input,
                    view = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(24), 0, dp(24), 0)
                        addView(input, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                        addView(picker, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(180),
                        ).apply {
                            topMargin = dp(8)
                        })
                    },
                    pickerUpdates = if (modified) {
                        listener.channelPointModifiedEmotePickerUpdates()
                    } else {
                        listener.channelEmotePickerUpdates()
                    },
                    refreshPicker = refreshPicker,
                    selectedEmoteId = { selectedEmoteId },
                )
            }
        }
    }

    private fun setChannelPointsIcon(image: ImageView, imageUrl: String?, fallbackTint: Int) {
        image.setImageResource(R.drawable.ic_channel_points)
        image.imageTintList = ColorStateList.valueOf(fallbackTint)
        if (!imageUrl.isNullOrBlank()) {
            image.imageTintList = null
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(imageUrl)
                    .crossfade(true)
                    .target(image)
                    .build(),
            )
        }
    }

    private fun showRedemptionResult(result: ChannelPointRedemptionResult) {
        val message = if (result.success) {
            getString(R.string.channel_points_reward_redeemed, result.rewardTitle)
        } else {
            getString(R.string.channel_points_reward_failed, result.rewardTitle, result.message.orEmpty())
        }
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun parseColor(value: String?, fallback: Int): Int {
        return runCatching { Color.parseColor(value ?: "") }.getOrElse {
            ContextCompat.getColor(requireContext(), fallback)
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
