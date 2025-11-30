package com.github.andreyasadchy.xtra.ui.player

import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogSleepTimerBinding
import com.github.andreyasadchy.xtra.util.AdminReceiver
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs

class SleepTimerDialog : DialogFragment() {

    companion object {
        private const val KEY_TIME_LEFT = "timeLeft"

        fun newInstance(timeLeft: Long): SleepTimerDialog {
            return SleepTimerDialog().apply {
                arguments = bundleOf(KEY_TIME_LEFT to timeLeft)
            }
        }
    }

    private var _binding: DialogSleepTimerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSleepTimerBinding.inflate(layoutInflater)
        val context = requireContext()
        val builder = context.getAlertDialogBuilder()
            .setTitle(getString(R.string.sleep_timer))
            .setView(binding.root)
        with(binding) {
            hours.apply {
                minValue = 0
                maxValue = 23
            }
            minutes.apply {
                minValue = 0
                maxValue = 59
            }
            val positiveListener: (dialog: DialogInterface, which: Int) -> Unit = { _, _ ->
                (parentFragment as? PlayerFragment)?.onSleepTimerChanged(hours.value * 3600_000L + minutes.value * 60_000L,  hours.value, minutes.value, lockCheckbox.isChecked)
                requireContext().prefs().edit {
                    putInt(C.SLEEP_TIMER_MINUTES, hours.value * 60 + minutes.value)
                }
                dismiss()
            }
            val timeLeft = requireArguments().getLong(KEY_TIME_LEFT)
            if (timeLeft < 0L) {
                val savedValue = requireContext().prefs().getInt(C.SLEEP_TIMER_MINUTES, 15)
                hours.value = savedValue / 60
                minutes.value = savedValue % 60
                builder.setPositiveButton(getString(R.string.start), positiveListener)
                builder.setNegativeButton(android.R.string.cancel) { _, _ -> dismiss() }
            } else {
                val hours = timeLeft / 3600_000L
                binding.hours.value = hours.toInt()
                minutes.value = ((timeLeft - hours * 3600_000L) / 60_000L).toInt()
                builder.setPositiveButton(getString(R.string.set), positiveListener)
                builder.setNegativeButton(getString(R.string.stop)) { _, _ ->
                    (parentFragment as? PlayerFragment)?.onSleepTimerChanged(-1L, 0, 0, lockCheckbox.isChecked)
                    dismiss()
                }
                builder.setNeutralButton(android.R.string.cancel) { _, _ -> dismiss() }
            }
            val receiver = ComponentName(requireContext(), AdminReceiver::class.java)
            val devicePolicyManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (devicePolicyManager.isAdminActive(receiver)) {
                lockCheckbox.apply {
                    isChecked = requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false)
                    text = context.getString(R.string.sleep_timer_lock)
                }
            } else {
                lockCheckbox.apply {
                    isChecked = false
                    text = context.getString(R.string.sleep_timer_lock_permissions)
                    setOnClickListener {
                        val intent = Intent(
                            DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                        ).putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN, receiver
                        )
                        requireContext().startActivity(intent)
                        dismiss()
                    }
                }
            }
        }
        return builder.create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}