package com.github.andreyasadchy.xtra.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchPagerViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val userResult = MutableStateFlow<Pair<String?, String?>?>(null)
    private var isLoading = false

    fun loadUserResult(checkedId: Int, result: String, networkLibrary: String?, gqlHeaders: Map<String, String>, enableIntegrity: Boolean) {
        if (userResult.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    userResult.value = if (checkedId == 0) {
                        val response = graphQLRepository.loadQueryUserResultID(networkLibrary, gqlHeaders, result)
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "refresh"
                                isLoading = false
                                return@launch
                            }
                        }
                        response.data!!.userResultByID?.let {
                            when {
                                it.onUser != null -> Pair(null, null)
                                it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                                it.onUserError != null -> Pair(it.__typename, null)
                                else -> null
                            }
                        }
                    } else {
                        val response = graphQLRepository.loadQueryUserResultLogin(networkLibrary, gqlHeaders, result)
                        if (enableIntegrity && integrity.value == null) {
                            response.errors?.find { it.message == "failed integrity check" }?.let {
                                integrity.value = "refresh"
                                isLoading = false
                                return@launch
                            }
                        }
                        response.data!!.userResultByLogin?.let {
                            when {
                                it.onUser != null -> Pair(null, null)
                                it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                                it.onUserError != null -> Pair(it.__typename, null)
                                else -> null
                            }
                        }
                    }
                } catch (e: Exception) {

                } finally {
                    isLoading = false
                }
            }
        }
    }
}
