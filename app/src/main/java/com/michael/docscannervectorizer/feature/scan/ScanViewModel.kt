package com.michael.docscannervectorizer.feature.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michael.docscannervectorizer.domain.model.DocPoint
import com.michael.docscannervectorizer.domain.model.DocumentCorners
import com.michael.docscannervectorizer.domain.model.ImageFilter
import com.michael.docscannervectorizer.domain.model.ScannedDocument
import com.michael.docscannervectorizer.domain.repository.DocumentRepository
import com.michael.docscannervectorizer.domain.repository.ScannerRepository
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
import java.util.UUID

class ScanViewModel(
    private val scannerRepository: ScannerRepository,
    private val documentRepository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<ScanNavigationEvent>()
    val navigationEvent: SharedFlow<ScanNavigationEvent> = _navigationEvent.asSharedFlow()

    fun onImageSelected(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap != null) {
                    launchImageSetup(context, bitmap)
                } else {
                    _uiState.update { it.copy(isProcessing = false, error = "Failed to decode selected image.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.localizedMessage) }
            }
        }
    }

    fun onBitmapCaptured(context: Context, bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            launchImageSetup(context, bitmap)
        }
    }

    private suspend fun launchImageSetup(context: Context, bitmap: Bitmap) {
        val rawFileId = UUID.randomUUID().toString()
        val originalDir = File(context.filesDir, "originals").apply { mkdirs() }
        val originalFile = File(originalDir, "$rawFileId.jpg")

        withContext(Dispatchers.IO) {
            FileOutputStream(originalFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }

        val defaultCorners = scannerRepository.detectEdges(bitmap)

        _uiState.update {
            it.copy(
                isProcessing = false,
                capturedBitmap = bitmap,
                originalPath = originalFile.absolutePath,
                corners = defaultCorners,
                error = null
            )
        }
    }

    fun onCornersChanged(corners: DocumentCorners) {
        _uiState.update { it.copy(corners = corners) }
    }

    fun confirmDocumentCrop(context: Context, title: String) {
        val state = _uiState.value
        val bitmap = state.capturedBitmap ?: return
        val corners = state.corners ?: return
        val origPath = state.originalPath ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            try {
                val warpedBitmap = scannerRepository.applyPerspectiveCorrection(bitmap, corners)
                val enhancedBitmap = scannerRepository.applyFilter(warpedBitmap, ImageFilter.AUTO_ENHANCE)
                if (enhancedBitmap !== warpedBitmap) warpedBitmap.recycle()

                val processedDir = File(context.filesDir, "processed").apply { mkdirs() }
                val docId = UUID.randomUUID().toString()
                val processedFile = File(processedDir, "$docId.jpg")

                withContext(Dispatchers.IO) {
                    FileOutputStream(processedFile).use { out ->
                        enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }
                if (enhancedBitmap !== bitmap) enhancedBitmap.recycle()

                val doc = ScannedDocument(
                    id = docId,
                    title = title.ifBlank { "Scanned Document" },
                    originalPath = origPath,
                    processedPath = processedFile.absolutePath,
                    corners = corners,
                    filter = ImageFilter.AUTO_ENHANCE,
                    createdAt = System.currentTimeMillis()
                )

                documentRepository.saveDocument(doc)
                _uiState.update { it.copy(isProcessing = false) }
                _navigationEvent.emit(ScanNavigationEvent.NavigateToAdjust(docId))
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = "Perspective cropping failed: ${e.localizedMessage}") }
            }
        }
    }

    fun cancelScan() {
        _uiState.update { ScanUiState() }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class ScanUiState(
    val isProcessing: Boolean = false,
    val capturedBitmap: Bitmap? = null,
    val originalPath: String? = null,
    val corners: DocumentCorners? = null,
    val error: String? = null
)

sealed interface ScanNavigationEvent {
    data class NavigateToAdjust(val documentId: String) : ScanNavigationEvent
}
