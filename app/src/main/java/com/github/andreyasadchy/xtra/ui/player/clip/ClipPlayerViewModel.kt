package com.github.andreyasadchy.xtra.ui.player.clip

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClipPlayerViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository,
    repository: ApiRepository,
    localFollowsChannel: LocalFollowChannelRepository) : PlayerViewModel(repository, localFollowsChannel) {

    var result = MutableLiveData<Map<String, String>>()

    fun load(gqlHeaders: Map<String, String>, id: String?) {
        if (result.value == null) {
            viewModelScope.launch {
                try {
                    graphQLRepository.loadClipUrls(gqlHeaders, id)
                } catch (e: Exception) {
                    null
                }.let { result.postValue(it) }
            }
        }
    }
}
