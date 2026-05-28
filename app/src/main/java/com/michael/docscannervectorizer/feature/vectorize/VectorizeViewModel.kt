package com.michael.docscannervectorizer.feature.vectorize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michael.docscannervectorizer.domain.model.ScannedDocument
import com.michael.docscannervectorizer.domain.model.VectorizedAsset
import com.michael.docscannervectorizer.domain.repository.DocumentRepository
import com.michael.docscannervectorizer.domain.repository.VectorizerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VectorizeViewModel(
    private val documentRepository: DocumentRepository,
    private val vectorizerRepository: VectorizerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VectorizeUiState())
    val uiState: StateFlow<VectorizeUiState> = _uiState.asStateFlow()

    fun loadDocument(documentId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            documentRepository.getDocumentById(documentId).collect { doc ->
                if (doc != null) {
                    _uiState.update { it.copy(document = doc) }
                    loadAssets(documentId)
                }
            }
        }
    }

    private fun loadAssets(documentId: String) {
        viewModelScope.launch {
            vectorizerRepository.getAssetsForDocument(documentId).collect { assets ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        vectorizedAssets = assets,
                        latestAsset = assets.firstOrNull()
                    )
                }
            }
        }
    }

    fun triggerVectorization() {
        val state = _uiState.value
        val doc = state.document ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isVectorizing = true) }
            try {
                val bitmapFile = File(doc.processedPath ?: doc.originalPath)
                if (bitmapFile.exists()) {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(bitmapFile.absolutePath)
                    }
                    if (bitmap != null) {
                        val asset = vectorizerRepository.vectorize(bitmap, doc.id)
                        _uiState.update {
                            it.copy(
                                isVectorizing = false,
                                latestAsset = asset,
                                error = null
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isVectorizing = false, error = "Failed to decode source bitmap.") }
                    }
                } else {
                    _uiState.update { it.copy(isVectorizing = false, error = "Processed image file is missing on disk.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isVectorizing = false, error = e.localizedMessage) }
            }
        }
    }

    fun setSelectedColor(hexColor: String) {
        _uiState.update { it.copy(selectedColorHex = hexColor) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class VectorizeUiState(
    val isLoading: Boolean = false,
    val isVectorizing: Boolean = false,
    val document: ScannedDocument? = null,
    val vectorizedAssets: List<VectorizedAsset> = emptyList(),
    val latestAsset: VectorizedAsset? = null,
    val selectedColorHex: String = "#1A56DB", // Core Blue Ink hex representation
    val error: String? = null
)
