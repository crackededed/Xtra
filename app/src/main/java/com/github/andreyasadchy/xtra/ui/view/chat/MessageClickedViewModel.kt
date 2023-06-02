package com.github.andreyasadchy.xtra.ui.view.chat

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.repository.ApiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessageClickedViewModel @Inject constructor(private val repository: ApiRepository) : ViewModel() {

    private val user = MutableLiveData<User?>()
    private var isLoading = false

    fun loadUser(channelId: String, targetId: String? = null, helixClientId: String? = null, helixToken: String? = null, gqlHeaders: Map<String, String>): MutableLiveData<User?> {
        if (user.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    val u = repository.loadUserMessageClicked(channelId = channelId, targetId = targetId, helixClientId = helixClientId, helixToken = helixToken, gqlHeaders = gqlHeaders)
                    user.postValue(u)
                } catch (e: Exception) {
                } finally {
                    isLoading = false
                }
            }
        }
        return user
    }
}