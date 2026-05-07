package com.github.andreyasadchy.xtra.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.ChannelViewerList
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewerListViewModel(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

    private val _viewerList = MutableStateFlow<ChannelViewerList?>(null)
    val viewerList: StateFlow<ChannelViewerList?> = _viewerList
    private var isLoading = false

    fun loadViewerList(channelLogin: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (_viewerList.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryUserChatters(networkLibrary, gqlHeaders, login = channelLogin)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            isLoading = false
                            return@launch
                        }
                    }
                    _viewerList.value = response.data?.user?.channel?.chatters?.let { response ->
                        ChannelViewerList(
                            broadcasters = response.broadcasters?.mapNotNull { it.login } ?: emptyList(),
                            moderators = response.moderators?.mapNotNull { it.login } ?: emptyList(),
                            vips = response.vips?.mapNotNull { it.login } ?: emptyList(),
                            viewers = response.viewers?.mapNotNull { it.login } ?: emptyList(),
                            count = response.count
                        )
                    }
                } catch (e: Exception) {
                    try {
                        val response = graphQLRepository.loadChannelViewerList(networkLibrary, gqlHeaders, channelLogin)
                        if (enableIntegrity) {
                            response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                                integrity.emit("refresh")
                                isLoading = false
                                return@launch
                            }
                        }
                        _viewerList.value = response.data?.user?.channel?.chatters?.let { response ->
                            ChannelViewerList(
                                broadcasters = response.broadcasters?.mapNotNull { it.login } ?: emptyList(),
                                moderators = response.moderators?.mapNotNull { it.login } ?: emptyList(),
                                vips = response.vips?.mapNotNull { it.login } ?: emptyList(),
                                viewers = response.viewers?.mapNotNull { it.login } ?: emptyList(),
                                count = response.count
                            )
                        }
                    } catch (e: Exception) {

                    }
                }
                isLoading = false
            }
        }
    }

    companion object {
        val PlayerViewerListViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                PlayerViewerListViewModel(xtraModule.graphQLRepository)
            }
        }
    }
}