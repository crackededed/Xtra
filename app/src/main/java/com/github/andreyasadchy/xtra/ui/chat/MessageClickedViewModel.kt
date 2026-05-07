package com.github.andreyasadchy.xtra.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MessageClickedViewModel(
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val integrity = MutableSharedFlow<String?>()

    val user = MutableStateFlow<Pair<User?, Boolean?>?>(null)
    private var isLoading = false

    fun loadUser(channelId: String?, channelLogin: String?, targetId: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (user.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                val response = try {
                    val response = graphQLRepository.loadQueryUserMessageClicked(networkLibrary, gqlHeaders, channelId, channelLogin.takeIf { channelId.isNullOrBlank() }, targetId)
                    if (enableIntegrity) {
                        response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let {
                            integrity.emit("refresh")
                            isLoading = false
                            return@launch
                        }
                    }
                    response.data!!.user?.let {
                        User(
                            id = it.id,
                            login = it.login,
                            name = it.displayName,
                            profileImageURL = it.profileImageURL,
                            bannerImageURL = it.bannerImageURL,
                            createdAt = it.createdAt?.toString(),
                            followedAt = it.follow?.followedAt?.toString(),
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
                                    id = it.id,
                                    login = it.login,
                                    name = it.displayName,
                                    profileImageURL = it.profileImageURL,
                                    type = it.type,
                                    broadcasterType = it.broadcasterType,
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

    companion object {
        val MessageClickedViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                MessageClickedViewModel(xtraModule.graphQLRepository, xtraModule.helixRepository)
            }
        }
    }
}