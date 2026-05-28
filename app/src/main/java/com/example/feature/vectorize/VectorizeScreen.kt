package com.example.feature.vectorize

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VectorizeScreen(
    documentId: String,
    viewModel: VectorizeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(documentId) {
        viewModel.loadDocument(documentId)
    }

    val inkColors = listOf(
        InkColorOption("#1A56DB", "Royal Blue"),
        InkColorOption("#DC2626", "Crimson Red"),
        InkColorOption("#111827", "Charcoal Black"),
        InkColorOption("#10B981", "Emerald Green"),
        InkColorOption("#7C3AED", "Sovereign Purple")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vectorize SVG/PNG", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back icon button")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Document status card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "Info status icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Vectorization isolates dark strokes (hand-sketches, text, inks) from white paper pages, creating transparent layers and portable vector shapes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Preview Layer Panel with active Transparency checkerboard background
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp)
                    )
                    .drawBehind {
                        // Drawing custom grids for transparent background checkboard
                        val gridSquarePx = 16.dp.toPx()
                        val colNum = (size.width / gridSquarePx).toInt() + 1
                        val rowNum = (size.height / gridSquarePx).toInt() + 1
                        for (i in 0 until colNum) {
                            for (j in 0 until rowNum) {
                                if ((i + j) % 2 == 0) {
                                    drawRect(
                                        color = Color.LightGray.copy(alpha = 0.25f),
                                        topLeft = Offset(i * gridSquarePx, j * gridSquarePx),
                                        size = Size(gridSquarePx, gridSquarePx)
                                    )
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val latest = uiState.latestAsset
                if (latest != null) {
                    val file = File(latest.pngPath)
                    if (file.exists()) {
                        Image(
                            painter = rememberAsyncImagePainter(file),
                            contentDescription = "Vectorized isolated transparent preview drawing",
                            colorFilter = ColorFilter.tint(Color(android.graphics.Color.parseColor(uiState.selectedColorHex))),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        )
                    }
                } else {
                    // Pre-Vectorized Screen State Call to Action
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ElectricBolt,
                            contentDescription = "Not Vectorized status logo icon",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Vector Layer Extractor",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Isolate ink lines, signatures, or drawings into transparent vectors instantly.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = viewModel::triggerVectorization,
                            modifier = Modifier.testTag("extract_vector_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ElectricBolt, "Spark lightning bolt icon")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Isolate & Vectorize", fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }

                if (uiState.isVectorizing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Tracing outlines...", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            val latestAsset = uiState.latestAsset
            if (latestAsset != null) {
                // Ink Colors selection
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SELECT INK OUTLINE COLOR",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("ink_colors_strip"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(inkColors) { inkOpt ->
                        val isSelected = uiState.selectedColorHex == inkOpt.hex
                        val parsedColor = Color(android.graphics.Color.parseColor(inkOpt.hex))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                    shape = CircleShape
                                )
                                .background(parsedColor)
                                .clickable { viewModel.setSelectedColor(inkOpt.hex) }
                                .testTag("ink_color_${inkOpt.hex}"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Select Color indicate icon",
                                    tint = if (inkOpt.hex == "#111827") Color.White else Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Vector Export Actions (Copy SVG block, share transparent PNG file)
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Copy SVG
                    FilledTonalButton(
                        onClick = {
                            val svgXml = latestAsset.svgContent ?: ""
                            // Colorize our SVG group with the chosen hex for maximum accuracy!
                            val finalSvgXml = svgXml.replace("fill=\"currentColor\"", "fill=\"${uiState.selectedColorHex}\"")
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Scanned Vector SVG Markup Raw Code", finalSvgXml)
                            clipboard.setPrimaryClip(clip)

                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("SVG vector markup copied! Ready to paste into Figma or Illustrator.")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_svg_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Icon")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy SVG", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Share PNG
                    Button(
                        onClick = {
                            val file = File(latestAsset.pngPath)
                            if (file.exists()) {
                                try {
                                    val contentUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, contentUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share Isolated Line-Art Vector Layer"))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("share_png_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Share, contentDescription = "Share Image Icon")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share PNG", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

data class InkColorOption(
    val hex: String,
    val name: String
)
