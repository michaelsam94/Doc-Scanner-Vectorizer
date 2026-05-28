package com.example.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.ScannerProcessor
import com.example.domain.model.DocPoint
import com.example.domain.model.DocumentCorners
import com.example.domain.model.ImageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    private val _corners = MutableStateFlow<DocumentCorners?>(null)
    val corners: StateFlow<DocumentCorners?> = _corners.asStateFlow()

    private val _isStabilizing = MutableStateFlow(false)
    val isStabilizing: StateFlow<Boolean> = _isStabilizing.asStateFlow()

    private val _stabilityProgress = MutableStateFlow(0f)
    val stabilityProgress: StateFlow<Float> = _stabilityProgress.asStateFlow()

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap.asStateFlow()

    private val _croppedBitmap = MutableStateFlow<Bitmap?>(null)
    val croppedBitmap: StateFlow<Bitmap?> = _croppedBitmap.asStateFlow()

    private val _filteredBitmap = MutableStateFlow<Bitmap?>(null)
    val filteredBitmap: StateFlow<Bitmap?> = _filteredBitmap.asStateFlow()

    private val _selectedFilter = MutableStateFlow(ImageFilter.MAGIC_COLOR)
    val selectedFilter: StateFlow<ImageFilter> = _selectedFilter.asStateFlow()

    private val _noteText = MutableStateFlow("")
    val noteText: StateFlow<String> = _noteText.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Trigger navigation event upon auto capture success
    private val _autoCaptureTriggered = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val autoCaptureTriggered: SharedFlow<Bitmap> = _autoCaptureTriggered.asSharedFlow()

    private var lastCorners: DocumentCorners? = null
    private var stableFrameCount = 0
    private var isAnalyzing = false

    private val STABILITY_THRESHOLD = 0.05f
    private val REQUIRED_STABLE_FRAMES = 8

    fun onFrameAnalyzed(bitmap: Bitmap, onAutoCapture: (Bitmap) -> Unit) {
        if (isAnalyzing || _capturedBitmap.value != null || _isProcessing.value) {
            bitmap.recycle() // Clean up discarded frame immediately
            return
        }
        isAnalyzing = true
        
        viewModelScope.launch {
            val detected = withContext(Dispatchers.Default) {
                ScannerProcessor.detectDocumentCorners(bitmap)
            }
            
            val last = lastCorners
            val finalCorners = if (last != null) {
                val d1 = dist(detected.topLeft, last.topLeft)
                val d2 = dist(detected.topRight, last.topRight)
                val d3 = dist(detected.bottomRight, last.bottomRight)
                val d4 = dist(detected.bottomLeft, last.bottomLeft)
                val totalDelta = d1 + d2 + d3 + d4
                
                // Adaptive temporal smoothing factor: smaller delta -> smoother transition, larger delta -> fast response
                val alpha = if (totalDelta < 0.08f) 0.18f else if (totalDelta < 0.25f) 0.45f else 0.85f
                smoothCorners(detected, last, alpha)
            } else {
                detected
            }
            
            _corners.value = finalCorners
            
            if (last != null) {
                val d1 = dist(finalCorners.topLeft, last.topLeft)
                val d2 = dist(finalCorners.topRight, last.topRight)
                val d3 = dist(finalCorners.bottomRight, last.bottomRight)
                val d4 = dist(finalCorners.bottomLeft, last.bottomLeft)
                val totalDelta = d1 + d2 + d3 + d4

                val thresholdMultiplier = 0.9f
                if (totalDelta < STABILITY_THRESHOLD * thresholdMultiplier) {
                    stableFrameCount++
                    _isStabilizing.value = true
                    _stabilityProgress.value = stableFrameCount.toFloat() / REQUIRED_STABLE_FRAMES
                    
                    if (stableFrameCount >= REQUIRED_STABLE_FRAMES) {
                        stableFrameCount = 0
                        _stabilityProgress.value = 1.0f
                        _isStabilizing.value = false
                        // Keep a reference to the captured bitmap
                        _capturedBitmap.value = bitmap
                        
                        // Crop immediately using high-fidelity smoothed corners
                        val cropped = withContext(Dispatchers.Default) {
                            ScannerProcessor.applyPerspectiveCorrection(bitmap, finalCorners)
                        }
                        _croppedBitmap.value = cropped
                        
                        // Apply active magic mode initial
                        val transformed = withContext(Dispatchers.Default) {
                            ScannerProcessor.applyFilter(cropped, _selectedFilter.value)
                        }
                        _filteredBitmap.value = transformed
                        
                        onAutoCapture(bitmap)
                    } else {
                        bitmap.recycle()
                    }
                } else {
                    stableFrameCount = maxOf(0, stableFrameCount - 1)
                    _stabilityProgress.value = stableFrameCount.toFloat() / REQUIRED_STABLE_FRAMES
                    if (stableFrameCount == 0) {
                        _isStabilizing.value = false
                    }
                    bitmap.recycle()
                }
            } else {
                bitmap.recycle()
            }
            lastCorners = finalCorners
            isAnalyzing = false
        }
    }

    private fun smoothCorners(newCorners: DocumentCorners, historicalCorners: DocumentCorners?, alpha: Float): DocumentCorners {
        if (historicalCorners == null) return newCorners
        return DocumentCorners(
            topLeft = lerp(historicalCorners.topLeft, newCorners.topLeft, alpha),
            topRight = lerp(historicalCorners.topRight, newCorners.topRight, alpha),
            bottomRight = lerp(historicalCorners.bottomRight, newCorners.bottomRight, alpha),
            bottomLeft = lerp(historicalCorners.bottomLeft, newCorners.bottomLeft, alpha)
        )
    }

    private fun lerp(p1: DocPoint, p2: DocPoint, alpha: Float): DocPoint {
        return DocPoint(
            x = p1.x + alpha * (p2.x - p1.x),
            y = p1.y + alpha * (p2.y - p1.y)
        )
    }

    private fun dist(p1: DocPoint, p2: DocPoint): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun onManualCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            _isProcessing.value = true
            _capturedBitmap.value = bitmap
            val activeCorners = _corners.value ?: ScannerProcessor.detectDocumentCorners(bitmap)
            
            val cropped = withContext(Dispatchers.Default) {
                ScannerProcessor.applyPerspectiveCorrection(bitmap, activeCorners)
            }
            _croppedBitmap.value = cropped
            
            val transformed = withContext(Dispatchers.Default) {
                ScannerProcessor.applyFilter(cropped, _selectedFilter.value)
            }
            _filteredBitmap.value = transformed
            _isProcessing.value = false
        }
    }

    fun onFilterSelected(filter: ImageFilter) {
        _selectedFilter.value = filter
        val crop = _croppedBitmap.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val transformed = withContext(Dispatchers.Default) {
                ScannerProcessor.applyFilter(crop, filter)
            }
            _filteredBitmap.value = transformed
            _isProcessing.value = false
        }
    }

    fun onNoteChanged(text: String) {
        _noteText.value = text
    }

    fun reset() {
        _capturedBitmap.value = null
        _croppedBitmap.value = null
        _filteredBitmap.value = null
        _corners.value = null
        _isStabilizing.value = false
        _stabilityProgress.value = 0f
        stableFrameCount = 0
        lastCorners = null
        _noteText.value = ""
    }
}
