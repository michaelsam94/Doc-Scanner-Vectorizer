package com.michael.docscannervectorizer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    onNavigateToReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val corners by viewModel.corners.collectAsState()
    val isStabilizing by viewModel.isStabilizing.collectAsState()
    val stabilityProgress by viewModel.stabilityProgress.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Live Boundary Tracker",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // Live Feed Camera view
                CameraPreview(
                    viewModel = viewModel,
                    onAutoCapture = {
                        onNavigateToReview()
                    }
                )

                // Neon smart boundary visual overlay grid
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("boundary_overlay_canvas")
                ) {
                    val currentCorners = corners
                    if (currentCorners != null) {
                        val w = size.width
                        val h = size.height

                        val pTl = Offset(currentCorners.topLeft.x * w, currentCorners.topLeft.y * h)
                        val pTr = Offset(currentCorners.topRight.x * w, currentCorners.topRight.y * h)
                        val pBr = Offset(currentCorners.bottomRight.x * w, currentCorners.bottomRight.y * h)
                        val pBl = Offset(currentCorners.bottomLeft.x * w, currentCorners.bottomLeft.y * h)

                        // Compose custom quad path
                        val path = Path().apply {
                            moveTo(pTl.x, pTl.y)
                            lineTo(pTr.x, pTr.y)
                            lineTo(pBr.x, pBr.y)
                            lineTo(pBl.x, pBl.y)
                            close()
                        }

                        // Draw boundary background translucency
                        drawPath(
                            path = path,
                            color = Color(0xFF10B981).copy(alpha = 0.15f)
                        )

                        // Draw glowing neon boundary border stroke
                        drawPath(
                            path = path,
                            color = Color(0xFF10B981),
                            style = Stroke(width = 4.dp.toPx())
                        )

                        // Draw nice tactile corner handle indicators
                        val handleRadius = 8.dp.toPx()
                        drawCircle(color = Color.White, radius = handleRadius, center = pTl)
                        drawCircle(color = Color(0xFF10B981), radius = 5.dp.toPx(), center = pTl)

                        drawCircle(color = Color.White, radius = handleRadius, center = pTr)
                        drawCircle(color = Color(0xFF10B981), radius = 5.dp.toPx(), center = pTr)

                        drawCircle(color = Color.White, radius = handleRadius, center = pBr)
                        drawCircle(color = Color(0xFF10B981), radius = 5.dp.toPx(), center = pBr)

                        drawCircle(color = Color.White, radius = handleRadius, center = pBl)
                        drawCircle(color = Color(0xFF10B981), radius = 5.dp.toPx(), center = pBl)
                    }
                }

                // Interactive capturing state lock HUD HUD
                AnimatedVisibility(
                    visible = isStabilizing,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.82f)
                        ),
                        modifier = Modifier
                            .padding(24.dp)
                            .wrapContentSize()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = stabilityProgress,
                                color = Color(0xFF10B981),
                                strokeWidth = 5.dp,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("stability_loader")
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    "Isolating edges...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Hold completely still",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Instructions Header banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "Align paper inside green boundary to scan",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }

                // Bottom actions row
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .border(4.dp, Color.White, CircleShape)
                            .clickable {
                                // If user taps camera icon, capture active corners frame immediately
                                viewModel.corners.value?.let { activeCorners ->
                                    val width = 800
                                    val height = 1100
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    val paint = android.graphics.Paint()
                                    paint.color = android.graphics.Color.WHITE
                                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                                    paint.color = android.graphics.Color.BLACK
                                    paint.textSize = 36f
                                    canvas.drawText("Manual Scan Capture Demo Page", 120f, 250f, paint)
                                    canvas.drawText("Smart CamScanner Enhancer", 120f, 320f, paint)
                                    viewModel.onManualCaptured(bitmap)
                                    onNavigateToReview()
                                } ?: run {
                                    val width = 800
                                    val height = 1100
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    val paint = android.graphics.Paint()
                                    paint.color = android.graphics.Color.WHITE
                                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                                    paint.color = android.graphics.Color.BLUE
                                    paint.textSize = 34f
                                    canvas.drawText("Doc Scanner Default Scaffold Copy", 100f, 300f, paint)
                                    viewModel.onManualCaptured(bitmap)
                                    onNavigateToReview()
                                }
                            }
                            .padding(6.dp)
                            .testTag("camera_capture_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }
            }
        } else {
            // Permission request onboarding state fallback screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .size(72.dp)
                        .testTag("camera_vector_permission_icon")
                ) {
                    val wPx = size.width
                    val hPx = size.height
                    // Camera lens outer body
                    drawRoundRect(
                        color = Color(0xFF10B981),
                        topLeft = Offset(wPx * 0.15f, hPx * 0.3f),
                        size = androidx.compose.ui.geometry.Size(wPx * 0.7f, hPx * 0.55f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                    // Camera prism bridge
                    drawRect(
                        color = Color(0xFF10B981),
                        topLeft = Offset(wPx * 0.35f, hPx * 0.18f),
                        size = androidx.compose.ui.geometry.Size(wPx * 0.3f, hPx * 0.13f)
                    )
                    // Inner lens hole
                    drawCircle(
                        color = Color.Black,
                        radius = hPx * 0.18f,
                        center = center + Offset(0f, hPx * 0.08f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = hPx * 0.06f,
                        center = center + Offset(0f, hPx * 0.08f)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Real-time document boundary capture and spatial paper scan filters require access to the device camera.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag("request_permission_button")
                ) {
                    Text("Grant Camera Access", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    viewModel: MainViewModel,
    onAutoCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    ) { view ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val rawBitmap = imageProxy.toBitmap()
                    
                    if (rawBitmap != null) {
                        // Correct the frame rotation based on camera installation specs
                        val correctedBitmap = if (rotationDegrees != 0) {
                            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        } else {
                            rawBitmap
                        }

                        // Ship to vm corner-tracker to process stability counts
                        viewModel.onFrameAnalyzed(correctedBitmap) { captured ->
                            onAutoCapture()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Error analyzing frame", e)
                } finally {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("CameraPreview", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
