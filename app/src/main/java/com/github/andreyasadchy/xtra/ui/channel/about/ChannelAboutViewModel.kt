package com.github.andreyasadchy.xtra.ui.channel.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelAboutViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val description = MutableStateFlow<String?>(null)
    val socialMedias = MutableStateFlow<List<Pair<String?, String?>>?>(null)
    val team = MutableStateFlow<Pair<String?, String?>?>(null)
    val originalName = MutableStateFlow<String?>(null)
    val panels = MutableStateFlow<List<ChannelPanel>?>(null)

    private var isLoading = false

    fun loadAbout(channelId: String?, channelLogin: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if ((description.value == null || team.value == null || socialMedias.value == null || panels.value == null) && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryUserAbout(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() })
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            isLoading = false
                            return@launch
                        }
                    }
                    response.data!!.user?.let { user ->
                        description.value = user.description
                        socialMedias.value = user.channel?.socialMedias?.map {
                            it.title to it.url
                        }
                        team.value = user.primaryTeam?.name to user.primaryTeam?.displayName
                        originalName.value = user.subscriptionProducts?.find { it?.tier == "1000" }?.name?.takeIf { it != channelLogin }
                        panels.value = user.panels?.mapNotNull { item ->
                            item?.onDefaultPanel?.let {
                                ChannelPanel(
                                    title = it.title,
                                    imageUrl = it.imageURL,
                                    linkUrl = it.linkURL,
                                    description = it.description,
                                )
                            }
                        }
                    }
                } catch (e: Exception) {

                }
                isLoading = false
            }
        }
    }
}