package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.app.ActivityCompat
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
import com.github.andreyasadchy.xtra.databinding.DialogStreamProxyEditBinding
import com.github.andreyasadchy.xtra.databinding.FragmentProxySettingsBinding
import com.github.andreyasadchy.xtra.model.ui.StreamProxy
import com.github.andreyasadchy.xtra.ui.settings.StreamProxySettingsViewModel.Companion.StreamProxySettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections

class StreamProxySettingsFragment : Fragment() {

    private var _binding: FragmentProxySettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StreamProxySettingsViewModel by viewModels { StreamProxySettingsViewModelFactory }
    private var mEditText: EditText? = null
    private val mShowSoftInputRunnable = Runnable { scheduleShowSoftInputInner() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProxySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding) {
            val adapter = StreamProxySettingsAdapter()
            val itemTouchHelper = ItemTouchHelper(
                object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                        val list = viewModel.list.value
                        Collections.swap(list, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                        adapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                        viewModel.updateProxies()
                        return true
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
                viewModel.updateProxy(item)
            }
            adapter.editListener = { item ->
                showEditDialog(item) { host, port, user, password, proxyPlaybackAccessToken, proxyMultivariantPlaylist, proxyMediaPlaylist ->
                    item.host = host
                    item.port = port?.toIntOrNull()
                    item.username = user
                    item.password = password
                    item.proxyPlaybackAccessToken = proxyPlaybackAccessToken
                    item.proxyMultivariantPlaylist = proxyMultivariantPlaylist
                    item.proxyMediaPlaylist = proxyMediaPlaylist
                    viewModel.updateProxy(item)
                    viewModel.list.value.indexOf(item).takeIf { it != -1 }?.let {
                        adapter.notifyItemChanged(it)
                    }
                }
            }
            adapter.deleteListener = { item ->
                val delete = getString(R.string.delete)
                requireContext().getAlertDialogBuilder()
                    .setTitle(delete)
                    .setMessage(getString(R.string.delete_proxy_message))
                    .setPositiveButton(delete) { _, _ ->
                        val list = viewModel.list.value
                        val index = list.indexOf(item).takeIf { it != -1 }
                        list.remove(item)
                        index?.let { adapter.notifyItemRemoved(it) }
                        viewModel.deleteProxy(item)
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            itemTouchHelper.attachToRecyclerView(recyclerView)
            viewModel.getProxies()
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
                val item = StreamProxy(position = index)
                showEditDialog(item) { host, port, user, password, proxyPlaybackAccessToken, proxyMultivariantPlaylist, proxyMediaPlaylist ->
                    item.host = host
                    item.port = port?.toIntOrNull()
                    item.username = user
                    item.password = password
                    item.proxyPlaybackAccessToken = proxyPlaybackAccessToken
                    item.proxyMultivariantPlaylist = proxyMultivariantPlaylist
                    item.proxyMediaPlaylist = proxyMediaPlaylist
                    list.add(item)
                    adapter.notifyItemInserted(index)
                    viewModel.saveProxy(item)
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

    private fun showEditDialog(item: StreamProxy, positiveButtonListener: (String?, String?, String?, String?, Boolean, Boolean, Boolean) -> Unit) {
        val binding = DialogStreamProxyEditBinding.inflate(layoutInflater)
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                positiveButtonListener(
                    binding.hostInput.editText?.text?.toString(),
                    binding.portInput.editText?.text?.toString(),
                    binding.userInput.editText?.text?.toString(),
                    binding.passwordInput.editText?.text?.toString(),
                    binding.proxyPlaybackAccessTokenCheckBox.isChecked,
                    binding.proxyMultivariantPlaylistCheckBox.isChecked,
                    binding.proxyMediaPlaylistCheckBox.isChecked,
                )
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()
        binding.hostInput.editText?.apply {
            val string = item.host ?: ""
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
        binding.portInput.editText?.apply {
            val string = item.port?.toString() ?: ""
            text.replace(0, length(), string, 0, string.length)
        }
        binding.userInput.editText?.apply {
            val string = item.username ?: ""
            text.replace(0, length(), string, 0, string.length)
        }
        binding.passwordInput.editText?.apply {
            val string = item.password ?: ""
            text.replace(0, length(), string, 0, string.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    positiveButtonListener(
                        binding.hostInput.editText?.text?.toString(),
                        binding.portInput.editText?.text?.toString(),
                        binding.userInput.editText?.text?.toString(),
                        binding.passwordInput.editText?.text?.toString(),
                        binding.proxyPlaybackAccessTokenCheckBox.isChecked,
                        binding.proxyMultivariantPlaylistCheckBox.isChecked,
                        binding.proxyMediaPlaylistCheckBox.isChecked,
                    )
                    dialog.dismiss()
                    true
                } else {
                    false
                }
            }
        }
        binding.proxyPlaybackAccessTokenCheckBox.isChecked = item.proxyPlaybackAccessToken
        binding.proxyMultivariantPlaylistCheckBox.isChecked = item.proxyMultivariantPlaylist
        binding.proxyMediaPlaylistCheckBox.isChecked = item.proxyMediaPlaylist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionButton.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 1)
                }
            }
            binding.permissionText.visibility = View.VISIBLE
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