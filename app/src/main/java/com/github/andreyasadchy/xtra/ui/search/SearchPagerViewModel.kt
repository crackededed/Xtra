package com.github.andreyasadchy.xtra.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.ui.RecentSearch
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.RecentSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchPagerViewModel @Inject constructor(
    private val graphQLRepository: GraphQLRepository,
    private val recentSearchRepository: RecentSearchRepository,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    val userResult = MutableStateFlow<Pair<String?, String?>?>(null)
    private var isLoading = false
    private val _recentSearches = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recentSearches: StateFlow<List<RecentSearch>> = _recentSearches

    init {
        refreshRecentSearches()
    }

    private fun refreshRecentSearches() {
        viewModelScope.launch {
            _recentSearches.value = recentSearchRepository.loadRecentSearches(LIMIT)
        }
    }

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

    fun saveRecentSearch(query: String) {
        viewModelScope.launch {
            val item = recentSearchRepository.find(query)
            val updatedItem = item?.apply {
                lastSearched = System.currentTimeMillis()
            } ?: RecentSearch(query, System.currentTimeMillis())
            recentSearchRepository.save(updatedItem)
            refreshRecentSearches()
        }
    }

    fun deleteRecentSearch(item: RecentSearch) {
        viewModelScope.launch {
            recentSearchRepository.delete(item)
            refreshRecentSearches()
        }
    }

    companion object {
        private const val LIMIT = 5
    }
}
