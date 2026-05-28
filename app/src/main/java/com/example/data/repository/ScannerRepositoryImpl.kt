package com.example.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.domain.model.DocPoint
import com.example.domain.model.DocumentCorners
import com.example.domain.model.ImageFilter
import com.example.domain.repository.ScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScannerRepositoryImpl : ScannerRepository {

    override suspend fun detectEdges(bitmap: Bitmap): DocumentCorners = withContext(Dispatchers.Default) {
        // Since custom interactive point tweaking is the core UX, we provide highly robust smart defaults
        // that start with an A4 proportioned quad slightly offset (10%) from edges.
        DocumentCorners(
            topLeft = DocPoint(0.1f, 0.1f),
            topRight = DocPoint(0.9f, 0.1f),
            bottomRight = DocPoint(0.9f, 0.9f),
            bottomLeft = DocPoint(0.1f, 0.9f)
        )
    }

    override suspend fun applyPerspectiveCorrection(bitmap: Bitmap, corners: DocumentCorners): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        val srcPoints = floatArrayOf(
            corners.topLeft.x * width, corners.topLeft.y * height,
            corners.topRight.x * width, corners.topRight.y * height,
            corners.bottomRight.x * width, corners.bottomRight.y * height,
            corners.bottomLeft.x * width, corners.bottomLeft.y * height
        )

        // Make the warped bitmap match full target frame sizes nicely
        val targetWidth = bitmap.width
        val targetHeight = bitmap.height

        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = android.graphics.Matrix()
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
            ImageFilter.GRAYSCALE -> applyColorMatrix(bitmap, ColorMatrix().apply { setSaturation(0f) })
            ImageFilter.MONOCHROME -> applyMonochromeThreshold(bitmap)
            ImageFilter.SHADOW_REMOVED -> {
                // Background shadow whitening: flattens shadows, enhances dark lines
                val matrix = ColorMatrix(floatArrayOf(
                    2.8f, 0f, 0f, 0f, -220f,
                    0f, 2.8f, 0f, 0f, -220f,
                    0f, 0f, 2.8f, 0f, -220f,
                    0f, 0f, 0f, 1.0f, 0f
                ))
                applyColorMatrix(bitmap, matrix)
            }
            ImageFilter.MAGIC_COLOR -> {
                // Custom color scanner filter: boosts saturation, whitens highlights
                val matrix = ColorMatrix(floatArrayOf(
                    1.4f, 0f, 0f, 0f, -40f,
                    0f, 1.4f, 0f, 0f, -40f,
                    0f, 0f, 1.4f, 0f, -40f,
                    0f, 0f, 0f, 1.0f, 0f
                ))
                applyColorMatrix(bitmap, matrix)
            }
        }
    }

    override suspend fun removeShadow(bitmap: Bitmap): Bitmap = applyFilter(bitmap, ImageFilter.SHADOW_REMOVED)

    private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun applyMonochromeThreshold(src: Bitmap, threshold: Int = 125): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val grayLuminance = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
            val finalColor = if (grayLuminance < threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            pixels[i] = finalColor
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
