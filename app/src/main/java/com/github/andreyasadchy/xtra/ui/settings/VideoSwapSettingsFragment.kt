package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogVideoSwapEditBinding
import com.github.andreyasadchy.xtra.databinding.FragmentProxySettingsBinding
import com.github.andreyasadchy.xtra.model.ui.VideoSwap
import com.github.andreyasadchy.xtra.ui.settings.VideoSwapSettingsViewModel.Companion.VideoSwapSettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections

class VideoSwapSettingsFragment : Fragment() {

    private var _binding: FragmentProxySettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VideoSwapSettingsViewModel by viewModels { VideoSwapSettingsViewModelFactory }
    private var mEditText: EditText? = null
    private val mShowSoftInputRunnable = Runnable { scheduleShowSoftInputInner() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProxySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding) {
            addItem.text = getString(R.string.add_item)
            val adapter = VideoSwapSettingsAdapter(this@VideoSwapSettingsFragment)
            val itemTouchHelper = ItemTouchHelper(
                object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                        return if (viewHolder.bindingAdapterPosition == 0 || target.bindingAdapterPosition == 0) {
                            false
                        } else {
                            val list = viewModel.list.value
                            Collections.swap(list, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                            adapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                            viewModel.updateVideoSwapItems()
                            true
                        }
                    }

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                    override fun isLongPressDragEnabled(): Boolean {
                        return false
                    }
                }
            )
            adapter.itemTouchHelper = itemTouchHelper
            recyclerView.adapter = adapter
            adapter.checkListener = { item ->
                viewModel.updateVideoSwap(item)
            }
            adapter.editListener = { item ->
                showEditDialog(item) { platform, playerType ->
                    item.platform = platform
                    item.playerType = playerType
                    if (item.position == -1) {
                        requireContext().prefs().edit {
                            putString(C.TOKEN_PLATFORM, platform)
                            putString(C.TOKEN_PLAYER_TYPE, playerType)
                        }
                    } else {
                        viewModel.updateVideoSwap(item)
                    }
                    viewModel.list.value.indexOf(item).takeIf { it != -1 }?.let {
                        adapter.notifyItemChanged(it)
                    }
                }
            }
            adapter.deleteListener = { item ->
                val delete = getString(R.string.delete)
                requireContext().getAlertDialogBuilder()
                    .setTitle(delete)
                    .setMessage(getString(R.string.delete_item_message))
                    .setPositiveButton(delete) { _, _ ->
                        val list = viewModel.list.value
                        val index = list.indexOf(item).takeIf { it != -1 }
                        list.remove(item)
                        index?.let { adapter.notifyItemRemoved(it) }
                        viewModel.deleteVideoSwap(item)
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            itemTouchHelper.attachToRecyclerView(recyclerView)
            viewModel.getVideoSwapItems(
                VideoSwap(
                    platform = requireContext().prefs().getString(C.TOKEN_PLATFORM, "web"),
                    playerType = requireContext().prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                    position = -1,
                )
            )
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.list.collectLatest { list ->
                        adapter.submitList(list)
                    }
                }
            }
            addItem.setOnClickListener {
                val list = viewModel.list.value
                val index = list.lastIndex + 1
                val item = VideoSwap(position = index)
                showEditDialog(item) { platform, playerType ->
                    item.platform = platform
                    item.playerType = playerType
                    list.add(item)
                    adapter.notifyItemInserted(index)
                    viewModel.saveVideoSwap(item)
                }
            }
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.let { appBar ->
                if (requireContext().prefs().getBoolean(C.UI_THEME_APPBAR_LIFT, true)) {
                    recyclerView.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                } else {
                    appBar.setLiftable(false)
                    appBar.background = null
                }
            }
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                recyclerView.updatePadding(bottom = insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    private fun showEditDialog(item: VideoSwap, positiveButtonListener: (String?, String?) -> Unit) {
        val binding = DialogVideoSwapEditBinding.inflate(layoutInflater)
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                positiveButtonListener(
                    binding.platformInput.editText?.text?.toString(),
                    binding.playerTypeInput.editText?.text?.toString(),
                )
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()
        binding.platformInput.editText?.apply {
            val string = item.platform ?: ""
            text.replace(0, length(), string, 0, string.length)
            if (requestFocus()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dialog.window?.decorView?.windowInsetsController?.show(WindowInsets.Type.ime())
                } else {
                    mEditText = this
                    scheduleShowSoftInputInner()
                }
            }
        }
        binding.playerTypeInput.editText?.apply {
            val string = item.playerType ?: ""
            text.replace(0, length(), string, 0, string.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    positiveButtonListener(
                        binding.platformInput.editText?.text?.toString(),
                        binding.playerTypeInput.editText?.text?.toString(),
                    )
                    dialog.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        dialog.show()
    }

    private fun scheduleShowSoftInputInner() {
        mEditText?.let { mEditText ->
            if (mEditText.isFocused) {
                val imm = mEditText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (!imm.showSoftInput(mEditText, 0)) {
                    mEditText.removeCallbacks(mShowSoftInputRunnable)
                    mEditText.postDelayed(mShowSoftInputRunnable, 50)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}