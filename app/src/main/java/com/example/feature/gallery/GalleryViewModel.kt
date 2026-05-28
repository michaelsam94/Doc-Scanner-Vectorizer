package com.example.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ScannedDocument
import com.example.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    private val repository: DocumentRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val state: StateFlow<GalleryUiState> = combine(
        repository.getAllDocuments(),
        _searchQuery
    ) { docs, query ->
        val filtered = if (query.isBlank()) {
            docs
        } else {
            docs.filter {
                it.title.contains(query, ignoreCase = true) || 
                it.note.contains(query, ignoreCase = true)
            }
        }
        
        GalleryUiState.Success(
            documents = filtered,
            totalCount = docs.size,
            vectorizedCount = docs.count { it.processedPath != null } 
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GalleryUiState.Loading
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            repository.deleteDocument(id)
        }
    }
}

sealed interface GalleryUiState {
    object Loading : GalleryUiState
    data class Success(
        val documents: List<ScannedDocument>,
        val totalCount: Int,
        val vectorizedCount: Int
    ) : GalleryUiState
}
