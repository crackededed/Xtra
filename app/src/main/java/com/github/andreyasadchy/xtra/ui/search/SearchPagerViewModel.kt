package com.github.andreyasadchy.xtra.ui.search

import androidx.core.util.Pair
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.repository.ApiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchPagerViewModel @Inject constructor(
    private val repository: ApiRepository) : ViewModel() {

    val userResult = MutableLiveData<Pair<String?, String?>?>()
    private var isLoading = false

    fun loadUserResult(gqlHeaders: Map<String, String>, checkedId: Int, result: String) {
        if (!isLoading) {
            isLoading = true
            userResult.value = null
            viewModelScope.launch {
                try {
                    val get = if (checkedId == 0) {
                        repository.loadUserResult(channelId = result, gqlHeaders = gqlHeaders)
                    } else {
                        repository.loadUserResult(channelLogin = result, gqlHeaders = gqlHeaders)
                    }
                    userResult.postValue(get)
                } catch (e: Exception) {

                } finally {
                    isLoading = false
                }
            }
        }
    }
}
