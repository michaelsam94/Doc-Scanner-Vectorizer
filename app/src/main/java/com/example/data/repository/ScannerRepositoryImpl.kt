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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ScannerRepositoryImpl : ScannerRepository {

    override suspend fun detectEdges(bitmap: Bitmap): DocumentCorners = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        
        // Downsample the image to a standard small resolution (150x150) for fast boundary detection
        val downsampleSize = 150
        val scaled = Bitmap.createScaledBitmap(bitmap, downsampleSize, downsampleSize, false)
        val sw = scaled.width
        val sh = scaled.height
        
        val gray = IntArray(sw * sh)
        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        scaled.recycle()
        
        // 1. Convert to Grayscale
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
        }
        
        // 2. Compute Sobel Edge Gradients
        val gradients = FloatArray(sw * sh)
        var maxGrad = 0f
        
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                
                val gx = (
                    gray[(y - 1) * sw + (x + 1)] - gray[(y - 1) * sw + (x - 1)] +
                    2 * gray[y * sw + (x + 1)] - 2 * gray[y * sw + (x - 1)] +
                    gray[(y + 1) * sw + (x + 1)] - gray[(y + 1) * sw + (x - 1)]
                )
                
                val gy = (
                    gray[(y + 1) * sw + (x - 1)] - gray[(y - 1) * sw + (x - 1)] +
                    2 * gray[(y + 1) * sw + x] - 2 * gray[(y - 1) * sw + x] +
                    gray[(y + 1) * sw + (x + 1)] - gray[(y - 1) * sw + (x + 1)]
                )
                
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                gradients[idx] = mag
                if (mag > maxGrad) maxGrad = mag
            }
        }
        
        // 3. Extract High-Gradient Edge Coordinates (discarding outer 4% margins to ignore frames/toolbars)
        val edgeThreshold = maxGrad * 0.22f
        val edgePoints = mutableListOf<Pair<Int, Int>>()
        
        val marginW = (sw * 0.04f).toInt()
        val marginH = (sh * 0.04f).toInt()
        
        for (y in marginH until sh - marginH) {
            for (x in marginW until sw - marginW) {
                val idx = y * sw + x
                if (gradients[idx] > edgeThreshold) {
                    edgePoints.add(Pair(x, y))
                }
            }
        }
        
        // 4. Trace the extremities of quadrilateral (L1 metric projection)
        if (edgePoints.size > 15) {
            var minSum = Float.MAX_VALUE
            var maxSum = -Float.MAX_VALUE
            var minDiff = Float.MAX_VALUE
            var maxDiff = -Float.MAX_VALUE
            
            var tl = DocPoint(0.1f, 0.1f)
            var tr = DocPoint(0.9f, 0.1f)
            var br = DocPoint(0.9f, 0.9f)
            var bl = DocPoint(0.1f, 0.9f)
            
            for (pt in edgePoints) {
                val xNorm = pt.first.toFloat() / sw
                val yNorm = pt.second.toFloat() / sh
                
                val sum = xNorm + yNorm
                val diff = xNorm - yNorm
                
                if (sum < minSum) {
                    minSum = sum
                    tl = DocPoint(xNorm, yNorm)
                }
                if (sum > maxSum) {
                    maxSum = sum
                    br = DocPoint(xNorm, yNorm)
                }
                if (diff > maxDiff) {
                    maxDiff = diff
                    tr = DocPoint(xNorm, yNorm)
                }
                if (diff < minDiff) {
                    minDiff = diff
                    bl = DocPoint(xNorm, yNorm)
                }
            }
            
            // Check that the polygon is non-degenerate and substantial in size
            val diag1 = hypot((br.x - tl.x).toDouble(), (br.y - tl.y).toDouble())
            val diag2 = hypot((tr.x - bl.x).toDouble(), (tr.y - bl.y).toDouble())
            
            if (diag1 > 0.35f && diag2 > 0.35f) {
                // Return found corners with slight padding buffering (1.5%) for safety margin
                val pad = 0.015f
                return@withContext DocumentCorners(
                    topLeft = DocPoint(max(0f, tl.x - pad), max(0f, tl.y - pad)),
                    topRight = DocPoint(min(1f, tr.x + pad), max(0f, tr.y - pad)),
                    bottomRight = DocPoint(min(1f, br.x + pad), min(1f, br.y + pad)),
                    bottomLeft = DocPoint(max(0f, bl.x - pad), min(1f, bl.y + pad))
                )
            }
        }
        
        // Graceful default proportional fallback
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

        // Map scanned bounds to a high resolution canvas aligned with inputs
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
            ImageFilter.MONOCHROME -> applyAdvancedAdaptiveThreshold(bitmap)
            ImageFilter.SHADOW_REMOVED -> applyCameraScannerEnhancement(bitmap, isMagicColor = false)
            ImageFilter.MAGIC_COLOR -> applyCameraScannerEnhancement(bitmap, isMagicColor = true)
        }
    }

    override suspend fun removeShadow(bitmap: Bitmap): Bitmap = applyFilter(bitmap, ImageFilter.SHADOW_REMOVED)

    /**
     * Highly advanced CamScanner-like paper whitening.
     * Extracts low-frequency background illumination via a heavily blurred downsampled map,
     * and performs local pixel intensity division to flatten shadows/gradients into pure white paper,
     * while retaining sharp ink drawings and boosted saturation color layers.
     */
    private fun applyCameraScannerEnhancement(bitmap: Bitmap, isMagicColor: Boolean): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val bg = getBackgroundIllumination(bitmap)
        
        val srcPixels = IntArray(width * height)
        val bgPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        bg.getPixels(bgPixels, 0, width, 0, 0, width, height)
        bg.recycle()
        
        for (i in 0 until width * height) {
            val srcP = srcPixels[i]
            val bgP = bgPixels[i]
            
            val rS = (srcP shr 16) and 0xff
            val gS = (srcP shr 8) and 0xff
            val bS = srcP and 0xff
            
            val rB = (bgP shr 16) and 0xff
            val gB = (bgP shr 8) and 0xff
            val bB = bgP and 0xff
            
            // Formula: source * 255 / background
            var r = if (rB > 0) (rS * 255) / rB else 255
            var g = if (gB > 0) (gS * 255) / gB else 255
            var b = if (bB > 0) (bS * 255) / bB else 255
            
            if (isMagicColor) {
                // Boost saturation for color elements
                val gray = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
                val rDiff = r - gray
                val gDiff = g - gray
                val bDiff = b - gray
                
                val satFactor = 1.35f
                r = max(0, min(255, (gray + rDiff * satFactor).toInt()))
                g = max(0, min(255, (gray + gDiff * satFactor).toInt()))
                b = max(0, min(255, (gray + bDiff * satFactor).toInt()))
                
                // Fine contrast stretch: sharp dark lines & whiten grayish backgrounds
                r = contrastStretch(r, low = 65, high = 210)
                g = contrastStretch(g, low = 65, high = 210)
                b = contrastStretch(b, low = 65, high = 210)
            } else {
                // Shadow removed natural look, normal paper whites
                r = contrastStretch(r, low = 75, high = 205)
                g = contrastStretch(g, low = 75, high = 205)
                b = contrastStretch(b, low = 75, high = 205)
            }
            
            outPixels[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Advanced Bradley-Roth Adaptive Thresholding.
     * Uses local background illumination proxy to threshold each pixel dynamically,
     * maintaining high readability of faint handwriting in severe shading or uneven lights.
     */
    private fun applyAdvancedAdaptiveThreshold(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val bg = getBackgroundIllumination(bitmap)
        
        val srcPixels = IntArray(width * height)
        val bgPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        
        bitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)
        bg.getPixels(bgPixels, 0, width, 0, 0, width, height)
        bg.recycle()
        
        val adaptiveBias = 18 // Adaptive threshold constant bias
        
        for (i in 0 until width * height) {
            val srcP = srcPixels[i]
            val bgP = bgPixels[i]
            
            val rS = (srcP shr 16) and 0xff
            val gS = (srcP shr 8) and 0xff
            val bS = srcP and 0xff
            val srcGray = (rS * 0.299f + gS * 0.587f + bS * 0.114f).toInt()
            
            val rB = (bgP shr 16) and 0xff
            val gB = (bgP shr 8) and 0xff
            val bB = bgP and 0xff
            val bgGray = (rB * 0.299f + gB * 0.587f + bB * 0.114f).toInt()
            
            val binarized = if (srcGray < bgGray - adaptiveBias) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            outPixels[i] = binarized
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun getBackgroundIllumination(bitmap: Bitmap): Bitmap {
        // Downsample for blazing fast blur calculation
        val scale = 0.12f
        val dw = max(50, (bitmap.width * scale).toInt())
        val dh = max(50, (bitmap.height * scale).toInt())
        
        val tiny = Bitmap.createScaledBitmap(bitmap, dw, dh, true)
        val pixels = IntArray(dw * dh)
        tiny.getPixels(pixels, 0, dw, 0, 0, dw, dh)
        tiny.recycle()
        
        // Fast horizontal & vertical 1D Box Blur (O(dw * dh)) with high radius (12px)
        val blurredPixels = boxBlurRGB(pixels, dw, dh, radius = 12)
        val blurredTiny = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        blurredTiny.setPixels(blurredPixels, 0, dw, 0, 0, dw, dh)
        
        // Upsample back to original dimensions using high quality bilinear scaling
        val background = Bitmap.createScaledBitmap(blurredTiny, bitmap.width, bitmap.height, true)
        blurredTiny.recycle()
        return background
    }

    private fun boxBlurRGB(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val size = width * height
        val outR = IntArray(size)
        val outG = IntArray(size)
        val outB = IntArray(size)
        
        val inR = IntArray(size)
        val inG = IntArray(size)
        val inB = IntArray(size)
        
        for (i in 0 until size) {
            val p = pixels[i]
            inR[i] = (p shr 16) and 0xff
            inG[i] = (p shr 8) and 0xff
            inB[i] = p and 0xff
        }
        
        // Horizontal Pass
        blurPass(inR, outR, width, height, radius, horizontal = true)
        blurPass(inG, outG, width, height, radius, horizontal = true)
        blurPass(inB, outB, width, height, radius, horizontal = true)
        
        // Vertical Pass
        blurPass(outR, inR, width, height, radius, horizontal = false)
        blurPass(outG, inG, width, height, radius, horizontal = false)
        blurPass(outB, inB, width, height, radius, horizontal = false)
        
        val result = IntArray(size)
        for (i in 0 until size) {
            result[i] = 0xFF000000.toInt() or (inR[i] shl 16) or (inG[i] shl 8) or inB[i]
        }
        return result
    }

    private fun blurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val size = radius * 2 + 1
        if (horizontal) {
            for (y in 0 until height) {
                var sum = 0
                for (x in -radius..radius) {
                    val cx = max(0, min(width - 1, x))
                    sum += src[y * width + cx]
                }
                dst[y * width] = sum / size
                
                for (x in 1 until width) {
                    val prevX = x - 1 - radius
                    val nextX = x + radius
                    val cp = max(0, min(width - 1, prevX))
                    val cn = max(0, min(width - 1, nextX))
                    sum += src[y * width + cn] - src[y * width + cp]
                    dst[y * width + x] = sum / size
                }
            }
        } else {
            for (x in 0 until width) {
                var sum = 0
                for (y in -radius..radius) {
                    val cy = max(0, min(height - 1, y))
                    sum += src[cy * width + x]
                }
                dst[x] = sum / size
                
                for (y in 1 until height) {
                    val prevY = y - 1 - radius
                    val nextY = y + radius
                    val cp = max(0, min(height - 1, prevY))
                    val cn = max(0, min(height - 1, nextY))
                    sum += src[cn * width + x] - src[cp * width + x]
                    dst[y * width + x] = sum / size
                }
            }
        }
    }

    private fun contrastStretch(value: Int, low: Int, high: Int): Int {
        if (value >= high) return 255
        if (value <= low) return max(0, (value * 0.45f).toInt())
        val scaled = ((value - low).toFloat() / (high - low)) * 255
        return max(0, min(255, scaled.toInt()))
    }

    private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }
}
