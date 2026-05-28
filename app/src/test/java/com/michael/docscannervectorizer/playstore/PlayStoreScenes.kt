package com.michael.docscannervectorizer.playstore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michael.docscannervectorizer.ScannerTheme

enum class PlayStoreScene {
    Dashboard,
    Camera,
    Filters,
    VectorExport,
}

private val emerald = Color(0xFF10B981)
private val teal = Color(0xFF14B8A6)
private val paper = Color(0xFFF8FAFC)

@Composable
fun PlayStoreScreenshotFrame(scene: PlayStoreScene) {
    ScannerTheme(darkTheme = scene == PlayStoreScene.Camera) {
        when (scene) {
            PlayStoreScene.Dashboard -> DashboardScene()
            PlayStoreScene.Camera -> CameraScene()
            PlayStoreScene.Filters -> FiltersScene()
            PlayStoreScene.VectorExport -> VectorExportScene()
        }
    }
}

@Composable
fun FeatureGraphicContent() {
    ScannerTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF0F172A), Color(0xFF064E3B), Color(0xFF0D9488)),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 54.dp, end = 520.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Doc Scanner & Vectorizer",
                    color = Color.White,
                    fontSize = 46.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Scan paper, clean it up, and export sharp SVG copies.",
                    color = Color.White.copy(alpha = 0.84f),
                    fontSize = 23.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            PhoneMock(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 70.dp)
                    .width(260.dp)
                    .height(454.dp),
            ) {
                PlayStoreScreenshotFrame(PlayStoreScene.Filters)
            }
        }
    }
}

@Composable
private fun DashboardScene() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header("Doc Scanner Vectorizer", "Recent clean scans") }
        item { SummaryCard() }
        item { SectionLabel("RECENT RETRIEVED SCANS") }
        items(4) { index ->
            ScanRow(
                title = listOf(
                    "Tax receipt - high contrast",
                    "Meeting sketch vector pass",
                    "Rental agreement page 2",
                    "Whiteboard capture cleanup",
                )[index],
                filter = listOf("MAGIC COLOR", "MONOCHROME", "SHADOW REMOVED", "GRAYSCALE")[index],
            )
        }
    }
}

@Composable
private fun CameraScene() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF020617), Color(0xFF111827), Color(0xFF020617)),
                ),
            ),
    ) {
        ComposeCanvas(Modifier.fillMaxSize()) {
            val page = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.18f)
                lineTo(size.width * 0.78f, size.height * 0.14f)
                lineTo(size.width * 0.72f, size.height * 0.72f)
                lineTo(size.width * 0.28f, size.height * 0.76f)
                close()
            }
            drawPath(page, Color.White.copy(alpha = 0.9f))
            drawPath(page, emerald.copy(alpha = 0.26f))
            drawPath(page, emerald, style = Stroke(width = 5.dp.toPx()))
            listOf(
                Offset(size.width * 0.22f, size.height * 0.18f),
                Offset(size.width * 0.78f, size.height * 0.14f),
                Offset(size.width * 0.72f, size.height * 0.72f),
                Offset(size.width * 0.28f, size.height * 0.76f),
            ).forEach {
                drawCircle(Color.White, 13.dp.toPx(), it)
                drawCircle(emerald, 7.dp.toPx(), it)
            }
            repeat(9) { row ->
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(0f, size.height * row / 9f),
                    end = Offset(size.width, size.height * row / 9f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        ) {
            Text(
                text = "Align paper inside green boundary to scan",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .size(92.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
                .border(5.dp, Color.White, CircleShape)
                .padding(9.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun FiltersScene() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header("Fidelity Adjustments", "Magic Color filter selected")
        DocumentPreview(Modifier.weight(1f))
        NoteCard()
        SectionLabel("CAMSCANNER PAPER FILTERS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("ORIGINAL", false)
            FilterChip("MAGIC COLOR", true)
            FilterChip("SVG", false)
        }
        Button(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = emerald),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Save Scan", color = Color.Black, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun VectorExportScene() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header("Vector Export", "Turn scans into portable SVG outlines")
        DocumentPreview(Modifier.weight(1f))
        StatGrid()
        OutlinedButton(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Share Vector SVG", fontWeight = FontWeight.Black)
        }
        Button(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = emerald),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Save Clean PNG", color = Color.Black, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun SummaryCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cabinet Storage Summary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Clean high-contrast document sheets saved",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(emerald.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("24", fontWeight = FontWeight.Black, color = emerald, fontSize = 21.sp)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = emerald,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun ScanRow(title: String, filter: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = rememberDocumentBitmap(title).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("May 28, 2026 - 15:42", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Spacer(Modifier.height(7.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = emerald.copy(alpha = 0.12f), contentColor = emerald) {
                    Text(filter, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun DocumentPreview(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111827))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = rememberDocumentBitmap("vector preview").asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun NoteCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Add Keywords / Text Note", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "Client invoice, signed copy, archive as SVG",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean) {
    Surface(
        color = if (selected) emerald else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun StatGrid() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("PNG", "clean copy", Modifier.weight(1f))
        StatCard("SVG", "portable vector", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = teal.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = emerald, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun PhoneMock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(34.dp))
            .background(Color(0xFF020617))
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(26.dp))
                .background(paper),
        ) {
            content()
        }
    }
}

@Composable
private fun rememberDocumentBitmap(seed: String): Bitmap = remember(seed) {
    Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(AndroidColor.rgb(248, 250, 252))
        paint.color = AndroidColor.rgb(226, 232, 240)
        canvas.drawRoundRect(42f, 38f, 678f, 962f, 22f, 22f, paint)
        paint.color = AndroidColor.WHITE
        canvas.drawRoundRect(62f, 58f, 658f, 942f, 18f, 18f, paint)
        paint.color = AndroidColor.rgb(15, 23, 42)
        paint.textSize = 38f
        paint.isFakeBoldText = true
        canvas.drawText("Document Scan", 110f, 150f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 24f
        val lines = listOf(
            "Adaptive paper cleanup",
            "Boundary corrected copy",
            seed.take(28),
            "Export: PNG + SVG",
            "Notes preserved locally",
        )
        lines.forEachIndexed { index, line ->
            paint.color = AndroidColor.rgb(51, 65, 85)
            canvas.drawText(line, 110f, 230f + index * 86f, paint)
            paint.color = AndroidColor.rgb(203, 213, 225)
            canvas.drawRect(110f, 252f + index * 86f, 580f - index * 22f, 262f + index * 86f, paint)
        }
        paint.color = AndroidColor.rgb(16, 185, 129)
        canvas.drawRoundRect(110f, 760f, 465f, 824f, 18f, 18f, paint)
        paint.color = AndroidColor.WHITE
        paint.textSize = 24f
        paint.isFakeBoldText = true
        canvas.drawText("MAGIC COLOR", 145f, 802f, paint)
    }
}
