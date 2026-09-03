package com.github.andreyasadchy.xtra.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.VideoSwap
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class VideoSwapSettingsViewModel(
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val list = MutableStateFlow<MutableList<VideoSwap>>(mutableListOf())

    fun getVideoSwapItems(defaultItem: VideoSwap) {
        viewModelScope.launch {
            list.value = mutableListOf<VideoSwap>().apply {
                add(defaultItem)
                addAll(playerRepository.getVideoSwapItems().sortedBy { it.position })
            }
        }
    }

    fun updateVideoSwapItems() {
        val items = list.value.drop(1).onEachIndexed { index, proxy ->
            proxy.position = index
        }
        viewModelScope.launch {
            playerRepository.updateVideoSwapItems(items)
        }
    }

    fun saveVideoSwap(item: VideoSwap) {
        viewModelScope.launch {
            val id = playerRepository.saveVideoSwap(item)
            item.id = id.toInt()
        }
    }

    fun deleteVideoSwap(item: VideoSwap) {
        viewModelScope.launch {
            playerRepository.deleteVideoSwap(item)
            updateVideoSwapItems()
        }
    }

    fun updateVideoSwap(item: VideoSwap) {
        viewModelScope.launch {
            playerRepository.updateVideoSwap(item)
        }
    }

    companion object {
        val VideoSwapSettingsViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                VideoSwapSettingsViewModel(xtraModule.playerRepository)
            }
        }
    }
}
