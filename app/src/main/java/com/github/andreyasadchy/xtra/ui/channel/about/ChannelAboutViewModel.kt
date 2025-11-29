package com.github.andreyasadchy.xtra.ui.channel.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.ChannelPanel
import com.github.andreyasadchy.xtra.model.ui.PrimaryTeam
import com.github.andreyasadchy.xtra.model.ui.RootAbout
import com.github.andreyasadchy.xtra.model.ui.SocialMedia
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelAboutViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    private val _rootAbout = MutableStateFlow<RootAbout?>(null)
    val rootAbout: StateFlow<RootAbout?> = _rootAbout

    private val _panelList = MutableStateFlow<List<ChannelPanel>?>(null)
    val panelList: StateFlow<List<ChannelPanel>?> = _panelList

    private var isLoadingRoot = false
    private var isLoadingPanels = false

    fun loadRootAbout(channelLogin: String, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (_rootAbout.value == null && !isLoadingRoot) {
            isLoadingRoot = true
            viewModelScope.launch {
                try {
                    val response = graphQLRepository.loadQueryChannelAboutUser(
                        networkLibrary,
                        gqlHeaders,
                        login = channelLogin
                    )

                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            isLoadingRoot = false
                            return@launch
                        }
                    }

                    _rootAbout.value = response.data?.user?.let { user ->
                        RootAbout(
                            id = user.id,
                            description = user.description,
                            socialMedias = user.channel?.socialMedias?.map { socialMedia ->
                                SocialMedia(
                                    id = socialMedia.id,
                                    name = socialMedia.name,
                                    title = socialMedia.title,
                                    url = socialMedia.url
                                )
                            },
                            primaryTeam = user.primaryTeam?.let {
                                PrimaryTeam(
                                    id = it.id,
                                    name = it.name,
                                    displayName = it.displayName

                                )
                            }
                        )
                    }
                } catch (e: Exception) {

                    try {
                        val response = graphQLRepository.loadChannelRootAboutPanel(
                            networkLibrary,
                            gqlHeaders,
                            channelLogin
                        )

                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }
                                ?.let {
                                    integrity.value = "refresh"
                                    isLoadingRoot = false
                                    return@launch
                                }
                        }

                        _rootAbout.value = response.data?.user?.let { user ->
                            RootAbout(
                                id = user.id,
                                description = user.description,
                                socialMedias = user.channel?.socialMedias?.map {
                                    SocialMedia(
                                        id = it.id,
                                        name = it.name,
                                        title = it.title,
                                        url = it.url
                                    )
                                },
                                primaryTeam = user.primaryTeam?.let {
                                    PrimaryTeam(
                                        id = it.id,
                                        name = it.name,
                                        displayName = it.displayName

                                    )
                                }
                            )
                        }
                    } catch (e: Exception) {

                    }
                } finally {
                    isLoadingRoot = false
                }
            }
        }
    }

    fun loadPanelList(channelId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (_panelList.value == null && !isLoadingPanels) {
            isLoadingPanels = true
            viewModelScope.launch {
                try {
                    val response =
                        graphQLRepository.loadChannelPanels(networkLibrary, gqlHeaders, channelId)

                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            isLoadingPanels = false
                            return@launch
                        }
                    }

                    _panelList.value = response.data?.user?.panels.orEmpty().filter { it.type != "EXTENSION" }
                        .map { panel ->
                            ChannelPanel(
                                id = panel.id,
                                type = panel.type,
                                title = panel.title,
                                imageURL = panel.imageURL,
                                linkURL = panel.linkURL,
                                description = panel.description,
                                altText = panel.altText
                            )
                        }


                } catch (_: Exception) {

                } finally {
                    isLoadingPanels = false
                }
            }
        }
    }
}