package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChannelRewardsBinding
import com.github.andreyasadchy.xtra.model.gql.chat.ChannelRewardsResponse.Reward
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

class ChannelRewardsDialog : DialogFragment() {
    private val viewModel: ChatViewModel by viewModels({ requireParentFragment() }) {
        ChatViewModel.ChatViewModelFactory
    }
    private var operation: Job? = null
    private var confirmation: AlertDialog? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val channelLogin = requireArguments().getString(CHANNEL_LOGIN).orEmpty()
        val binding = DialogChannelRewardsBinding.inflate(layoutInflater)
        val dialog = requireContext().getAlertDialogBuilder()
            .setTitle(R.string.channel_points)
            .setView(binding.root)
            .setNeutralButton(R.string.channel_points_refresh, null)
            .setPositiveButton(android.R.string.cancel, null)
            .create()

        fun setBusy(busy: Boolean, preventDismiss: Boolean = false) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !busy
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !preventDismiss
            dialog.setCanceledOnTouchOutside(!preventDismiss)
            isCancelable = !preventDismiss
            binding.rewards.isEnabled = !busy
        }

        fun status(message: String?) {
            binding.status.text = message
            binding.status.isVisible = !message.isNullOrBlank()
        }

        fun showCatalog(catalog: ChatViewModel.RewardCatalog, onSelect: (Reward) -> Unit) {
            binding.balance.text = getString(R.string.channel_points_balance, catalog.balance)
            binding.balance.isVisible = true
            val rows = catalog.rewards.map { reward ->
                val blocked = when {
                    reward.isPaused || !reward.isInStock ||
                        Instant.parseOrNull(reward.cooldownExpiresAt.orEmpty())?.let { it > Clock.System.now() } == true ->
                        R.string.channel_points_reward_unavailable
                    reward.isSubOnly && !catalog.subscribed -> R.string.channel_points_sub_only
                    requireNotNull(reward.cost) > catalog.balance -> R.string.channel_points_unaffordable
                    else -> null
                }
                val label = getString(R.string.channel_points_reward_row, reward.title, reward.cost)
                if (blocked == null) label to true else "$label\n${getString(blocked)}" to false
            }
            binding.rewards.adapter = object : ArrayAdapter<String>(
                requireContext(), android.R.layout.simple_list_item_1, rows.map { it.first },
            ) {
                override fun areAllItemsEnabled() = false
                override fun isEnabled(position: Int) = rows[position].second
            }
            binding.rewards.setOnItemClickListener { _, _, position, _ ->
                if (rows[position].second && operation?.isActive != true) {
                    onSelect(catalog.rewards[position])
                }
            }
        }

        suspend fun showRewards(message: String? = null) {
            val catalog = try {
                viewModel.loadRewards(channelLogin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? ChatViewModel.ChannelPointsException)?.message
                    ?: getString(R.string.channel_points_load_error)
                status(listOfNotNull(message, error).joinToString("\n"))
                return
            }
            status(message ?: getString(R.string.channel_points_empty).takeIf { catalog.rewards.isEmpty() })
            showCatalog(catalog) { reward ->
                confirm(reward) { input ->
                    if (operation?.isActive == true) return@confirm
                    operation = lifecycleScope.launch {
                        setBusy(true, preventDismiss = true)
                        status(getString(R.string.channel_points_redeeming))
                        try {
                            val result = try {
                                viewModel.redeemReward(catalog.channelId, reward, input)
                                getString(R.string.channel_points_redeem_success)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                (e as? ChatViewModel.ChannelPointsException)?.message
                                    ?: getString(R.string.channel_points_redeem_unknown)
                            }
                            binding.balance.isVisible = false
                            binding.rewards.adapter = null
                            showRewards(result)
                        } finally {
                            setBusy(false)
                        }
                    }
                }
            }
        }

        fun refresh() {
            if (operation?.isActive == true) return
            operation = lifecycleScope.launch {
                setBusy(true)
                binding.balance.isVisible = false
                binding.rewards.adapter = null
                status(getString(R.string.channel_points_loading))
                try {
                    showRewards()
                } finally {
                    setBusy(false)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
            refresh()
        }
        return dialog
    }

    private fun confirm(reward: Reward, redeem: (String?) -> Unit) {
        val cost = requireNotNull(reward.cost)
        val builder = requireContext().getAlertDialogBuilder()
            .setTitle(reward.title)
            .setMessage(listOfNotNull(
                reward.prompt?.takeIf { it.isNotBlank() },
                getString(R.string.channel_points_spend_confirm, cost),
            ).joinToString("\n\n"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.channel_points_redeem, null)
        val input = if (reward.isUserInputRequired) EditText(requireContext()).apply {
            hint = getString(R.string.channel_points_reward_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            val container = FrameLayout(context)
            val padding = (24 * resources.displayMetrics.density).toInt()
            container.setPadding(padding, 0, padding, 0)
            container.addView(this)
            builder.setView(container)
        } else null
        confirmation = builder.create().apply {
            setOnDismissListener { confirmation = null }
            setOnShowListener {
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val text = input?.text?.toString()?.trim()
                    if (input != null && text.isNullOrBlank()) {
                        input.error = getString(R.string.channel_points_reward_input)
                    } else {
                        dismiss()
                        redeem(text)
                    }
                }
            }
            show()
        }
    }

    override fun onDestroyView() {
        operation?.cancel()
        confirmation?.dismiss()
        super.onDestroyView()
    }

    companion object {
        const val TAG = "channelRewards"
        private const val CHANNEL_LOGIN = "channelLogin"

        fun newInstance(channelLogin: String) = ChannelRewardsDialog().apply {
            arguments = Bundle().apply { putString(CHANNEL_LOGIN, channelLogin) }
        }
    }
}
