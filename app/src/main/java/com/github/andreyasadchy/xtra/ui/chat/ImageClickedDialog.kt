package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChatImageClickBinding
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ImageClickedDialog : BottomSheetDialogFragment(), IntegrityDialog.CallbackListener {

    companion object {
        private const val IMAGE_URL = "image_url"
        private const val IMAGE_NAME = "image_name"
        private const val IMAGE_SOURCE = "image_source"
        private const val IMAGE_FORMAT = "image_format"
        private const val IMAGE_ANIMATED = "image_animated"
        private const val IMAGE_THIRD_PARTY = "image_third_party"
        private const val EMOTE_ID = "emote_id"

        const val PERSONAL_STV = "personal_stv"
        const val CHANNEL_STV = "channel_stv"
        const val CHANNEL_BTTV = "channel_bttv"
        const val CHANNEL_FFZ = "channel_ffz"
        const val GLOBAL_STV = "global_stv"
        const val GLOBAL_BTTV = "global_bttv"
        const val GLOBAL_FFZ = "global_ffz"

        fun newInstance(url: String?, name: String?, source: String?, format: String?, isAnimated: Boolean?, thirdParty: Boolean?, emoteId: String?): ImageClickedDialog {
            return ImageClickedDialog().apply {
                arguments = bundleOf(
                    IMAGE_URL to url,
                    IMAGE_NAME to name,
                    IMAGE_SOURCE to source,
                    IMAGE_FORMAT to format,
                    IMAGE_ANIMATED to isAnimated,
                    IMAGE_THIRD_PARTY to thirdParty,
                    EMOTE_ID to emoteId
                )
            }
        }
    }

    private var _binding: DialogChatImageClickBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ImageClickedViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogChatImageClickBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collectLatest {
                    if (it != null &&
                        it != "done" &&
                        requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                        requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                    ) {
                        IntegrityDialog.show(childFragmentManager, it)
                        viewModel.integrity.value = "done"
                    }
                }
            }
        }
        with(binding) {
            val args = requireArguments()
            Glide.with(this@ImageClickedDialog)
                .load(args.getString(IMAGE_URL))
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        if (resource is Animatable && args.getBoolean(IMAGE_ANIMATED) && requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true)) {
                            (resource as Animatable).start()
                        }
                        image.setImageDrawable(resource)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
            args.getString(IMAGE_NAME)?.let {
                imageName.visibility = View.VISIBLE
                imageName.text = it
            }
            args.getString(IMAGE_SOURCE)?.let {
                imageSource.visibility = View.VISIBLE
                imageSource.text = when (it) {
                    PERSONAL_STV -> requireContext().getString(R.string.personal_stv_emote)
                    CHANNEL_STV -> requireContext().getString(R.string.channel_stv_emote)
                    CHANNEL_BTTV -> requireContext().getString(R.string.channel_bttv_emote)
                    CHANNEL_FFZ -> requireContext().getString(R.string.channel_ffz_emote)
                    GLOBAL_STV -> requireContext().getString(R.string.global_stv_emote)
                    GLOBAL_BTTV -> requireContext().getString(R.string.global_bttv_emote)
                    GLOBAL_FFZ -> requireContext().getString(R.string.global_ffz_emote)
                    else -> it
                }
            }
            args.getString(EMOTE_ID)?.let {
                viewModel.loadEmoteCard(
                    it,
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                    TwitchApiHelper.getGQLHeaders(requireContext()),
                    requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.emoteCard.collectLatest { emoteCard ->
                            if (emoteCard != null) {
                                val name = if (emoteCard.channelLogin != null && !emoteCard.channelLogin.equals(emoteCard.channelName, true)) {
                                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                        "0" -> "${emoteCard.channelName}(${emoteCard.channelLogin})"
                                        "1" -> emoteCard.channelName
                                        else -> emoteCard.channelLogin
                                    }
                                } else {
                                    emoteCard.channelName
                                }
                                when (emoteCard.type) {
                                    "SUBSCRIPTIONS" -> {
                                        imageSource.visibility = View.VISIBLE
                                        imageSource.text = requireContext().getString(R.string.channel_sub_emote, name,
                                            when (emoteCard.subTier) {
                                                "TIER_1" -> "1"
                                                "TIER_2" -> "2"
                                                "TIER_3" -> "3"
                                                else -> emoteCard.subTier
                                            }
                                        )
                                    }
                                    "FOLLOWER" -> {
                                        imageSource.visibility = View.VISIBLE
                                        imageSource.text = requireContext().getString(R.string.channel_follower_emote, name)
                                    }
                                    "BITS_BADGE_TIERS" -> {
                                        imageSource.visibility = View.VISIBLE
                                        imageSource.text = requireContext().getString(R.string.bits_reward_emote, emoteCard.bitThreshold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onIntegrityDialogCallback(callback: String?) {
        if (callback == "refresh") {
            requireArguments().getString(EMOTE_ID)?.let {
                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.loadEmoteCard(
                            it,
                            requireContext().prefs().getString(C.NETWORK_LIBRARY, "OkHttp"),
                            TwitchApiHelper.getGQLHeaders(requireContext()),
                            requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}