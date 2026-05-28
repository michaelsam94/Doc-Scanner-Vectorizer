package com.michael.docscannervectorizer.feature.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.michael.docscannervectorizer.domain.model.DocPoint
import com.michael.docscannervectorizer.domain.model.DocumentCorners
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onNavigateToAdjust: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Observe Navigation Event
    LaunchedEffect(viewModel.navigationEvent) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is ScanNavigationEvent.NavigateToAdjust -> {
                    onNavigateToAdjust(event.documentId)
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onImageSelected(context, uri)
        }
    }

    var documentTitle by remember { mutableStateOf("") }
    var showCropDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Boundaries & Crop", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.capturedBitmap != null) {
                            viewModel.cancelScan()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back icon button")
                    }
                },
                actions = {
                    if (uiState.capturedBitmap != null) {
                        IconButton(
                            onClick = { showCropDialog = true },
                            modifier = Modifier.testTag("confirm_crop_button")
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "Check confirm button", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            if (uiState.capturedBitmap == null) {
                // Viewfinder / Import Selection Panel
                if (hasCameraPermission) {
                    CameraViewfinder(
                        onBitmapCaptured = { bmp ->
                            viewModel.onBitmapCaptured(context, bmp)
                        },
                        onOpenGallery = {
                            photoPickerLauncher.launch("image/*")
                        }
                    )
                } else {
                    PermissionFallbackUI(
                        onGrantPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onOpenGallery = { photoPickerLauncher.launch("image/*") }
                    )
                }
            } else {
                // Cropping Interactive Mode with bounding handles
                val bitmap = uiState.capturedBitmap!!
                val corners = uiState.corners

                if (corners != null) {
                    InteractiveCropperBox(
                        bitmap = bitmap,
                        corners = corners,
                        onCornersChanged = viewModel::onCornersChanged,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (uiState.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Detecting document edges...", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Error notifications
            uiState.error?.let { err ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::clearError) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(err)
                }
            }
        }
    }

    if (showCropDialog) {
        AlertDialog(
            onDismissRequest = { showCropDialog = false },
            title = { Text("Save Page Scan") },
            text = {
                Column {
                    Text("Enter a file name for this scanned page:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = documentTitle,
                        onValueChange = { documentTitle = it },
                        placeholder = { Text("e.g. Sketch Draft 1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("add_scanned_doc_title")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmDocumentCrop(context, documentTitle)
                        showCropDialog = false
                    }
                ) {
                    Text("Crop & Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCropDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CameraViewfinder(
    onBitmapCaptured: (Bitmap) -> Unit,
    onOpenGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Camera capture UI action controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 40.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "Import Scans from Photo Album",
                    tint = Color.White
                )
            }

            IconButton(
                onClick = {
                    val captureUseCase = imageCapture ?: return@IconButton
                    captureUseCase.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.capacity())
                                buffer.get(bytes)
                                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                
                                // Rotate bitmap based on image rotationDegrees orientation if needed
                                val rotation = image.imageInfo.rotationDegrees
                                if (rotation != 0) {
                                    val matrix = Matrix()
                                    matrix.postRotate(rotation.toFloat())
                                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                }
                                
                                image.close()
                                if (bitmap != null) {
                                    onBitmapCaptured(bitmap)
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                            }
                        }
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .testTag("take_photo_trigger_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Camera,
                    contentDescription = "Trigger Camera Shutter Button icon",
                    modifier = Modifier.size(36.dp),
                    tint = Color.Black
                )
            }

            // Decorative mirror spacer to balance alignment
            Box(modifier = Modifier.size(56.dp))
        }
    }
}

@Composable
fun InteractiveCropperBox(
    bitmap: Bitmap,
    corners: DocumentCorners,
    onCornersChanged: (DocumentCorners) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        // Calculate aspect ratios for drawing margins
        val containerAspect = containerWidth.value / containerHeight.value
        val imageAspect = imageWidth / imageHeight

        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val (drawWidthDp, drawHeightDp) = if (imageAspect > containerAspect) {
            containerWidth to (containerWidth / imageAspect)
        } else {
            (containerHeight * imageAspect) to containerHeight
        }

        Box(
            modifier = Modifier
                .size(drawWidthDp, drawHeightDp)
        ) {
            // Draw original source frame inside viewport
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Cropper original context",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Map and bind drag handles
            Box(modifier = Modifier.fillMaxSize()) {
                val ptTl = Offset(corners.topLeft.x * widthPx, corners.topLeft.y * heightPx)
                val ptTr = Offset(corners.topRight.x * widthPx, corners.topRight.y * heightPx)
                val ptBr = Offset(corners.bottomRight.x * widthPx, corners.bottomRight.y * heightPx)
                val ptBl = Offset(corners.bottomLeft.x * widthPx, corners.bottomLeft.y * heightPx)

                // Drawn boundary connectings
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(Color(0xFF1A56DB), ptTl, ptTr, strokeWidth = 6f)
                    drawLine(Color(0xFF1A56DB), ptTr, ptBr, strokeWidth = 6f)
                    drawLine(Color(0xFF1A56DB), ptBr, ptBl, strokeWidth = 6f)
                    drawLine(Color(0xFF1A56DB), ptBl, ptTl, strokeWidth = 6f)
                }

                // Interactive drag circles
                DragHandleCircle(Offset(corners.topLeft.x, corners.topLeft.y), widthPx, heightPx) { offset ->
                    onCornersChanged(corners.copy(topLeft = DocPoint(offset.x, offset.y)))
                }
                DragHandleCircle(Offset(corners.topRight.x, corners.topRight.y), widthPx, heightPx) { offset ->
                    onCornersChanged(corners.copy(topRight = DocPoint(offset.x, offset.y)))
                }
                DragHandleCircle(Offset(corners.bottomRight.x, corners.bottomRight.y), widthPx, heightPx) { offset ->
                    onCornersChanged(corners.copy(bottomRight = DocPoint(offset.x, offset.y)))
                }
                DragHandleCircle(Offset(corners.bottomLeft.x, corners.bottomLeft.y), widthPx, heightPx) { offset ->
                    onCornersChanged(corners.copy(bottomLeft = DocPoint(offset.x, offset.y)))
                }
            }
        }
    }
}

@Composable
fun DragHandleCircle(
    fractionPos: Offset,
    parentWidthPx: Float,
    parentHeightPx: Float,
    onDrag: (Offset) -> Unit
) {
    val handleRadius = 22.dp
    val handleDiameter = 44.dp

    var currentXFraction by remember(fractionPos.x) { mutableStateOf(fractionPos.x) }
    var currentYFraction by remember(fractionPos.y) { mutableStateOf(fractionPos.y) }

    val leftOffset = (currentXFraction * parentWidthPx) - 60f
    val topOffset = (currentYFraction * parentHeightPx) - 120f

    Box(
        modifier = Modifier
            .offset { IntOffset(leftOffset.roundToInt(), topOffset.roundToInt()) }
            .size(handleDiameter)
            .clip(CircleShape)
            .background(Color(0xFF1A56DB).copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val nextX = ((currentXFraction * parentWidthPx) + dragAmount.x).coerceIn(0f, parentWidthPx)
                    val nextY = ((currentYFraction * parentHeightPx) + dragAmount.y).coerceIn(0f, parentHeightPx)
                    currentXFraction = nextX / parentWidthPx
                    currentYFraction = nextY / parentHeightPx
                    onDrag(Offset(currentXFraction, currentYFraction))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun PermissionFallbackUI(
    onGrantPermission: () -> Unit,
    onOpenGallery: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.DocumentScanner,
                contentDescription = "Scan Icon info",
                modifier = Modifier.size(44.dp),
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Camera Access Required",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "DocVect requires camera authority to scan boundaries, crop, and filter document layouts directly.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onGrantPermission,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Grant Camera Access", fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onOpenGallery) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Photo, contentDescription = "Gallery item", tint = Color.LightGray)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Document from Library Instead", color = Color.LightGray)
            }
        }
    }
}
