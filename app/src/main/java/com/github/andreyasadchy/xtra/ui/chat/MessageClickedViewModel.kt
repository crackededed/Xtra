package com.github.andreyasadchy.xtra.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessageClickedViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val user = MutableStateFlow<Pair<User?, Boolean?>?>(null)
    private var isLoading = false

    fun loadUser(channelId: String?, channelLogin: String?, targetId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (user.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                val response = try {
                    val response = graphQLRepository.loadQueryUserMessageClicked(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() }, targetId)
                    if (enableIntegrity && integrity.value == null) {
                        response.errors?.find { it.message == "failed integrity check" }?.let {
                            integrity.value = "refresh"
                            isLoading = false
                            return@launch
                        }
                    }
                    response.data!!.user?.let {
                        User(
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            profileImageUrl = it.profileImageURL,
                            bannerImageURL = it.bannerImageURL,
                            createdAt = it.createdAt?.toString(),
                            followedAt = it.follow?.followedAt?.toString()
                        )
                    }
                } catch (e: Exception) {
                    if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        try {
                            helixRepository.getUsers(
                                networkLibrary = networkLibrary,
                                headers = helixHeaders,
                                ids = channelId?.let { listOf(it) },
                                logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
                            ).data.firstOrNull()?.let {
                                User(
                                    channelId = it.channelId,
                                    channelLogin = it.channelLogin,
                                    channelName = it.channelName,
                                    type = it.type,
                                    broadcasterType = it.broadcasterType,
                                    profileImageUrl = it.profileImageUrl,
                                    createdAt = it.createdAt,
                                )
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
                user.value = Pair(response, response == null)
                isLoading = false
            }
        }
    }
}