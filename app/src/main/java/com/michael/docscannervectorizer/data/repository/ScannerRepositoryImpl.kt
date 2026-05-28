package com.michael.docscannervectorizer.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.michael.docscannervectorizer.data.scan.DocumentEdgeDetector
import com.michael.docscannervectorizer.data.scan.DocumentImageEnhancer
import com.michael.docscannervectorizer.domain.model.DocumentCorners
import com.michael.docscannervectorizer.domain.model.ImageFilter
import com.michael.docscannervectorizer.domain.repository.ScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class ScannerRepositoryImpl : ScannerRepository {

    override suspend fun detectEdges(bitmap: Bitmap): DocumentCorners = withContext(Dispatchers.Default) {
        DocumentEdgeDetector.detect(bitmap)
    }

    override suspend fun applyPerspectiveCorrection(bitmap: Bitmap, corners: DocumentCorners): Bitmap =
        withContext(Dispatchers.Default) {
            val width = bitmap.width
            val height = bitmap.height

            val srcPoints = floatArrayOf(
                corners.topLeft.x * width, corners.topLeft.y * height,
                corners.topRight.x * width, corners.topRight.y * height,
                corners.bottomRight.x * width, corners.bottomRight.y * height,
                corners.bottomLeft.x * width, corners.bottomLeft.y * height
            )

            val topWidth = hypot(
                (srcPoints[2] - srcPoints[0]).toDouble(),
                (srcPoints[3] - srcPoints[1]).toDouble()
            )
            val bottomWidth = hypot(
                (srcPoints[4] - srcPoints[6]).toDouble(),
                (srcPoints[5] - srcPoints[7]).toDouble()
            )
            val leftHeight = hypot(
                (srcPoints[6] - srcPoints[0]).toDouble(),
                (srcPoints[7] - srcPoints[1]).toDouble()
            )
            val rightHeight = hypot(
                (srcPoints[4] - srcPoints[2]).toDouble(),
                (srcPoints[5] - srcPoints[3]).toDouble()
            )

            val targetWidth = max(topWidth, bottomWidth).roundToInt().coerceIn(1, width * 2)
            val targetHeight = max(leftHeight, rightHeight).roundToInt().coerceIn(1, height * 2)

            val dstPoints = floatArrayOf(
                0f, 0f,
                targetWidth.toFloat(), 0f,
                targetWidth.toFloat(), targetHeight.toFloat(),
                0f, targetHeight.toFloat()
            )

            val matrix = Matrix()
            val success = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
            if (!success) return@withContext bitmap

            val warpedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(warpedBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, matrix, paint)
            warpedBitmap
        }

    override suspend fun applyFilter(bitmap: Bitmap, filter: ImageFilter): Bitmap = withContext(Dispatchers.Default) {
        when (filter) {
            ImageFilter.ORIGINAL -> bitmap
            ImageFilter.GRAYSCALE -> DocumentImageEnhancer.grayscale(bitmap)
            ImageFilter.MONOCHROME -> DocumentImageEnhancer.monochrome(bitmap)
            ImageFilter.SHADOW_REMOVED -> DocumentImageEnhancer.removeShadow(bitmap)
            ImageFilter.MAGIC_COLOR -> DocumentImageEnhancer.magicColor(bitmap)
            ImageFilter.AUTO_ENHANCE -> DocumentImageEnhancer.autoEnhance(bitmap)
        }
    }

    override suspend fun removeShadow(bitmap: Bitmap): Bitmap =
        applyFilter(bitmap, ImageFilter.SHADOW_REMOVED)
}
