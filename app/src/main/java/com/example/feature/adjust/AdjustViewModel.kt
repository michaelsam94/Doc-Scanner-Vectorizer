package com.example.feature.adjust

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ImageFilter
import com.example.domain.model.ScannedDocument
import com.example.domain.repository.DocumentRepository
import com.example.domain.repository.ScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AdjustViewModel(
    private val documentRepository: DocumentRepository,
    private val scannerRepository: ScannerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdjustUiState())
    val uiState: StateFlow<AdjustUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<AdjustNavigationEvent>()
    val navigationEvent: SharedFlow<AdjustNavigationEvent> = _navigationEvent.asSharedFlow()

    private var originalBitmapLoaded: Bitmap? = null

    fun loadDocument(documentId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            documentRepository.getDocumentById(documentId).collect { doc ->
                if (doc != null) {
                    val rawBmp = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(doc.processedPath ?: doc.originalPath)
                    }
                    originalBitmapLoaded = rawBmp
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            document = doc,
                            selectedFilter = doc.filter,
                            noteText = doc.note,
                            currentBitmap = rawBmp
                        )
                    }
                    if (rawBmp != null) {
                        applyFilterLocally(doc.filter, rawBmp)
                    }
                }
            }
        }
    }

    fun onFilterSelected(filter: ImageFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        val baseBmp = originalBitmapLoaded ?: return
        applyFilterLocally(filter, baseBmp)
    }

    private fun applyFilterLocally(filter: ImageFilter, baseBmp: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingFilter = true) }
            val filtered = scannerRepository.applyFilter(baseBmp, filter)
            _uiState.update {
                it.copy(
                    isApplyingFilter = false,
                    currentBitmap = filtered
                )
            }
        }
    }

    fun onRotateClockwise() {
        val bmp = _uiState.value.currentBitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingFilter = true) }
            val rotated = withContext(Dispatchers.Default) {
                val matrix = Matrix().apply { postRotate(90f) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            originalBitmapLoaded = rotated
            _uiState.update {
                it.copy(
                    isApplyingFilter = false,
                    currentBitmap = rotated
                )
            }
        }
    }

    fun onNoteChanged(note: String) {
        _uiState.update { it.copy(noteText = note) }
    }

    fun saveChanges(context: Context) {
        val state = _uiState.value
        val doc = state.document ?: return
        val bitmap = state.currentBitmap ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val file = File(doc.processedPath ?: doc.originalPath)
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }

                val updatedDoc = doc.copy(
                    filter = state.selectedFilter,
                    note = state.noteText
                )
                documentRepository.saveDocument(updatedDoc)

                _uiState.update { it.copy(isSaving = false) }
                _navigationEvent.emit(AdjustNavigationEvent.NavigateToGallery)
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "Failed to update documentation: ${e.localizedMessage}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class AdjustUiState(
    val isSaving: Boolean = false,
    val isApplyingFilter: Boolean = false,
    val document: ScannedDocument? = null,
    val selectedFilter: ImageFilter = ImageFilter.ORIGINAL,
    val noteText: String = "",
    val currentBitmap: Bitmap? = null,
    val error: String? = null
)

sealed interface AdjustNavigationEvent {
    object NavigateToGallery : AdjustNavigationEvent
}
