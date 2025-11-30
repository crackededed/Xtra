package com.github.andreyasadchy.xtra.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.os.bundleOf
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.PlayerVolumeBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider

class PlayerVolumeDialog : BottomSheetDialogFragment() {

    companion object {
        private const val VOLUME = "volume"

        fun newInstance(volume: Float?): PlayerVolumeDialog {
            return PlayerVolumeDialog().apply {
                arguments = bundleOf(VOLUME to volume)
            }
        }
    }

    private var _binding: PlayerVolumeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = PlayerVolumeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            val volume = (requireArguments().getFloat(VOLUME, 1f) * 100)
            setVolume(volume)
            volumeBar.value = volume
            volumeBar.addOnChangeListener { _, value, _ ->
                (parentFragment as? PlayerFragment)?.changeVolume((value / 100f))
                setVolume(value)
            }
            volumeBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    requireContext().prefs().edit { putInt(C.PLAYER_VOLUME, slider.value.toInt()) }
                }
            })
        }
    }

    private fun setVolume(volume: Float) {
        with(binding) {
            volumeText.text = volume.toInt().toString()
            if (volume == 0f) {
                volumeMute.setImageResource(R.drawable.baseline_volume_off_black_24)
                volumeMute.setOnClickListener {
                    volumeBar.value = 100f
                    requireContext().prefs().edit { putInt(C.PLAYER_VOLUME, 100) }
                }
            } else {
                volumeMute.setImageResource(R.drawable.baseline_volume_up_black_24)
                volumeMute.setOnClickListener {
                    volumeBar.value = 0f
                    requireContext().prefs().edit { putInt(C.PLAYER_VOLUME, 0) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
