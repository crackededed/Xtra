package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.github.andreyasadchy.xtra.databinding.DialogCustomProxyEditBinding
import com.github.andreyasadchy.xtra.databinding.FragmentCustomProxySettingsBinding
import com.github.andreyasadchy.xtra.databinding.FragmentCustomProxySettingsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.ui.settings.CustomProxySettingsViewModel.Companion.CustomProxySettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections

class CustomProxySettingsFragment : Fragment() {

    private var _binding: FragmentCustomProxySettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CustomProxySettingsViewModel by viewModels { CustomProxySettingsViewModelFactory }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCustomProxySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding) {
            val adapter = CustomProxySettingsAdapter(this@CustomProxySettingsFragment)
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
                showEditDialog(item) { url, addQueryParams ->
                    item.url = url
                    item.addQueryParams = addQueryParams
                    viewModel.updateProxy(item)
                    viewModel.list.value.indexOf(item).takeIf { it != -1 }?.let {
                        adapter.notifyItemChanged(it)
                    }
                    viewModel.updateProxyStatus(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), url)
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
                        viewModel.deleteProxy(item)
                        list.remove(item)
                        index?.let { adapter.notifyItemRemoved(it) }
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            adapter.statusMap = viewModel.statusMap
            itemTouchHelper.attachToRecyclerView(recyclerView)
            viewModel.getProxies(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP))
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.list.collectLatest { list ->
                        adapter.submitList(list)
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.statusChanged.collect { url ->
                        viewModel.list.value.forEachIndexed { index, proxy ->
                            if (proxy.url == url) {
                                (recyclerView.layoutManager?.findViewByPosition(index) as? LinearLayout)?.let {
                                    val binding = FragmentCustomProxySettingsListItemBinding.bind(it)
                                    adapter.updateStatus(binding, requireContext(), proxy)
                                } ?: adapter.notifyItemChanged(index)
                            }
                        }
                    }
                }
            }
            addItem.setOnClickListener {
                val list = viewModel.list.value
                val index = list.lastIndex + 1
                val item = CustomProxy(null, true, index, true)
                showEditDialog(item) { url, addQueryParams ->
                    item.url = url
                    item.addQueryParams = addQueryParams
                    viewModel.saveProxy(item)
                    list.add(item)
                    adapter.notifyItemInserted(index)
                    viewModel.updateProxyStatus(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), url)
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

    private fun showEditDialog(item: CustomProxy, positiveButtonListener: (String?, Boolean) -> Unit) {
        val binding = DialogCustomProxyEditBinding.inflate(layoutInflater)
        binding.editText.editText?.let {
            val url = item.url ?: ""
            it.text.replace(0, it.length(), url, 0, url.length)
        }
        binding.checkBox.isChecked = item.addQueryParams
        requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                positiveButtonListener(
                    binding.editText.editText?.text?.toString(),
                    binding.checkBox.isChecked
                )
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}