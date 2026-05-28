package com.michael.docscannervectorizer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michael.docscannervectorizer.domain.ScannerProcessor
import com.michael.docscannervectorizer.domain.model.DocPoint
import com.michael.docscannervectorizer.domain.model.DocumentCorners
import com.michael.docscannervectorizer.domain.model.ImageFilter
import com.michael.docscannervectorizer.domain.model.ScannedDocument
import java.io.File
import java.io.FileOutputStream
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

    // Scanned documents history persistence
    private val _scannedDocuments = MutableStateFlow<List<ScannedDocument>>(emptyList())
    val scannedDocuments: StateFlow<List<ScannedDocument>> = _scannedDocuments.asStateFlow()

    private val _isScannedDocumentsLoaded = MutableStateFlow(false)
    val isScannedDocumentsLoaded: StateFlow<Boolean> = _isScannedDocumentsLoaded.asStateFlow()

    private val _currentEditingDocId = MutableStateFlow<String?>(null)
    val currentEditingDocId: StateFlow<String?> = _currentEditingDocId.asStateFlow()

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
        _currentEditingDocId.value = null
    }

    fun loadScannedDocuments(context: Context) {
        viewModelScope.launch {
            _isScannedDocumentsLoaded.value = false
            val docs = withContext(Dispatchers.IO) {
                val list = ArrayList<ScannedDocument>()
                val scansDir = File(context.filesDir, "saved_scans")
                if (scansDir.exists()) {
                    val files = scansDir.listFiles() ?: emptyArray()
                    val imgFiles = files.filter { it.extension == "png" }.sortedByDescending { it.lastModified() }
                    for (imgFile in imgFiles) {
                        val baseName = imgFile.nameWithoutExtension
                        val metaFile = File(scansDir, "$baseName.txt")
                        var timestamp = imgFile.lastModified()
                        var filterName = ImageFilter.MAGIC_COLOR.name
                        var note = ""
                        if (metaFile.exists()) {
                            try {
                                val lines = metaFile.readLines()
                                timestamp = lines.getOrNull(0)?.toLongOrNull() ?: imgFile.lastModified()
                                filterName = lines.getOrNull(1) ?: ImageFilter.MAGIC_COLOR.name
                                note = lines.drop(2).joinToString("\n")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        list.add(
                            ScannedDocument(
                                id = baseName,
                                imagePath = imgFile.absolutePath,
                                note = note,
                                timestamp = timestamp,
                                filterName = filterName
                            )
                        )
                    }
                }
                list
            }
            _scannedDocuments.value = docs
            _isScannedDocumentsLoaded.value = true
        }
    }

    fun saveScannedDocument(context: Context, onComplete: () -> Unit) {
        val activeBitmap = _filteredBitmap.value ?: _croppedBitmap.value ?: return
        val currentId = _currentEditingDocId.value
        val docId = currentId ?: "Doc_${System.currentTimeMillis()}"
        val note = _noteText.value
        val filter = _selectedFilter.value
        val timestamp = System.currentTimeMillis()

        viewModelScope.launch {
            _isProcessing.value = true
            withContext(Dispatchers.IO) {
                try {
                    val scansDir = File(context.filesDir, "saved_scans")
                    if (!scansDir.exists()) scansDir.mkdirs()

                    val imgFile = File(scansDir, "$docId.png")
                    val metaFile = File(scansDir, "$docId.txt")

                    val out = FileOutputStream(imgFile)
                    activeBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                    out.close()

                    metaFile.writeText("$timestamp\n${filter.name}\n$note")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            loadScannedDocuments(context)
            _isProcessing.value = false
            reset()
            onComplete()
        }
    }

    fun deleteScannedDocument(context: Context, doc: ScannedDocument) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val imgFile = File(doc.imagePath)
                    if (imgFile.exists()) imgFile.delete()

                    val scansDir = File(context.filesDir, "saved_scans")
                    val metaFile = File(scansDir, "${doc.id}.txt")
                    if (metaFile.exists()) metaFile.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            loadScannedDocuments(context)
        }
    }

    fun onDocumentSelectedForViewing(doc: ScannedDocument, context: Context, onComplete: () -> Unit) {
        viewModelScope.launch {
            _isProcessing.value = true
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(doc.imagePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (bitmap != null) {
                _croppedBitmap.value = bitmap
                _filteredBitmap.value = bitmap
                _noteText.value = doc.note
                _currentEditingDocId.value = doc.id
                
                val matchedFilter = try {
                    ImageFilter.valueOf(doc.filterName)
                } catch (e: Exception) {
                    ImageFilter.MAGIC_COLOR
                }
                _selectedFilter.value = matchedFilter
                _isProcessing.value = false
                onComplete()
            } else {
                _isProcessing.value = false
            }
        }
    }

    fun loadGallerySelectedBitmap(context: Context, uri: Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            _isProcessing.value = true
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (bitmap != null) {
                val maxDim = 1600
                val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else {
                    bitmap
                }
                _capturedBitmap.value = scaled
                
                val activeCorners = withContext(Dispatchers.Default) {
                    ScannerProcessor.detectDocumentCorners(scaled)
                }
                _corners.value = activeCorners
                
                val cropped = withContext(Dispatchers.Default) {
                    ScannerProcessor.applyPerspectiveCorrection(scaled, activeCorners)
                }
                _croppedBitmap.value = cropped
                
                val transformed = withContext(Dispatchers.Default) {
                    ScannerProcessor.applyFilter(cropped, _selectedFilter.value)
                }
                _filteredBitmap.value = transformed
                _currentEditingDocId.value = null
                
                _isProcessing.value = false
                onComplete()
            } else {
                _isProcessing.value = false
            }
        }
    }
}
