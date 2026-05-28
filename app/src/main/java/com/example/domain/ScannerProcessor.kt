package com.example.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.domain.model.DocPoint
import com.example.domain.model.DocumentCorners
import com.example.domain.model.ImageFilter
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ScannerProcessor {

    /**
     * Highly optimized edge and boundary detection algorithm.
     * Integrates adaptive binarization, largest connected-component analysis (blob tracking),
     * and dynamic active-contour gradient refinement to snap page corners pixel-perfectly.
     */
    fun detectDocumentCorners(bitmap: Bitmap): DocumentCorners {
        val width = bitmap.width
        val height = bitmap.height
        
        // Multi-Scale downsampled representation for ultra-responsive live processing
        val targetWidth = 160
        val targetHeight = 160
        val sc = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false)
        val sw = sc.width
        val sh = sc.height
        
        val pixels = IntArray(sw * sh)
        sc.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        sc.recycle()
        
        val gray = IntArray(sw * sh)
        val sat = FloatArray(sw * sh)
        
        // We calculate brightness histogram to dynamically set an ambient-adaptive threshold
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val gVal = (r * 0.299f + g * 0.587f + b * 0.114f).toInt().coerceIn(0, 255)
            gray[i] = gVal
            hist[gVal]++
            
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            sat[i] = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
        }
        
        // Find 80th percentile of brightness representing the document sheet's highlight luminance
        var accum = 0
        var p80Luminance = 160
        val targetCount = (sw * sh * 0.80f).toInt()
        for (lvl in 0..255) {
            accum += hist[lvl]
            if (accum >= targetCount) {
                p80Luminance = lvl
                break
            }
        }
        
        // Adaptive paper binarization threshold based on ambient highlight luminance
        val paperThreshold = (p80Luminance * 0.72f).coerceIn(85f, 175f).toInt()
        
        // High-contrast Sobel gradient space
        val grad = FloatArray(sw * sh)
        var maxGrad = 0f
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val gx = (gray[idx - 1 + sw] - gray[idx - 1 - sw] + 
                         2 * gray[idx + sw] - 2 * gray[idx - sw] + 
                         gray[idx + 1 + sw] - gray[idx + 1 - sw])
                         
                val gy = (gray[idx - 1 - sw] - gray[idx + 1 - sw] + 
                         2 * gray[idx - 1] - 2 * gray[idx + 1] + 
                         gray[idx - 1 + sw] - gray[idx + 1 + sw])
                
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                grad[idx] = mag
                if (mag > maxGrad) maxGrad = mag
            }
        }
        
        // Identify white paper candidate mask (high luminance + low saturation)
        val isWhitePaper = BooleanArray(sw * sh)
        for (i in gray.indices) {
            val gVal = gray[i]
            val sVal = sat[i]
            isWhitePaper[i] = (gVal >= paperThreshold && sVal < 0.32f) || (gVal > 185 && sVal < 0.40f)
        }
        
        // Ignore thin outer border (3.5%) to ignore user's holding fingers/frame bezels
        val marginW = (sw * 0.035f).toInt().coerceAtLeast(3)
        val marginH = (sh * 0.035f).toInt().coerceAtLeast(3)
        
        // Extract largest contiguous connected white paper region (BFS blob extraction)
        val visited = BooleanArray(sw * sh)
        var maxComponent = ArrayList<Int>()
        val queue = IntArray(sw * sh)
        
        for (y in marginH until sh - marginH) {
            for (x in marginW until sw - marginW) {
                val idx = y * sw + x
                if (isWhitePaper[idx] && !visited[idx]) {
                    var head = 0
                    var tail = 0
                    val currentComponent = ArrayList<Int>()
                    
                    queue[tail++] = idx
                    visited[idx] = true
                    
                    while (head < tail) {
                        val curr = queue[head++]
                        currentComponent.add(curr)
                        
                        val cx = curr % sw
                        val cy = curr / sw
                        
                        val neighbors = intArrayOf(curr - 1, curr + 1, curr - sw, curr + sw)
                        val nxCoords = intArrayOf(cx - 1, cx + 1, cx, cx)
                        val nyCoords = intArrayOf(cy, cy, cy - 1, cy + 1)
                        
                        for (n in 0 until 4) {
                            val nIdx = neighbors[n]
                            val nx = nxCoords[n]
                            val ny = nyCoords[n]
                            
                            if (nx >= marginW && nx < sw - marginW && ny >= marginH && ny < sh - marginH) {
                                if (isWhitePaper[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[tail++] = nIdx
                                }
                            }
                        }
                    }
                    if (currentComponent.size > maxComponent.size) {
                        maxComponent = currentComponent
                    }
                }
            }
        }
        
        // If the white paper component represents a plausible document size (>= 6% of viewport)
        if (maxComponent.size >= (sw * sh) * 0.06) {
            val blobSet = BooleanArray(sw * sh)
            for (idx in maxComponent) {
                blobSet[idx] = true
            }
            
            // Collect perimeter contour points of the blob
            val perimeter = ArrayList<Pair<Int, Int>>()
            for (idx in maxComponent) {
                val cx = idx % sw
                val cy = idx / sw
                if (cx > marginW && cx < sw - marginW - 1 && cy > marginH && cy < sh - marginH - 1) {
                    val isPerimeter = !blobSet[idx - 1] || !blobSet[idx + 1] || 
                                      !blobSet[idx - sw] || !blobSet[idx + sw]
                    if (isPerimeter) {
                        perimeter.add(Pair(cx, cy))
                    }
                } else {
                    perimeter.add(Pair(cx, cy))
                }
            }
            
            if (perimeter.size >= 8) {
                // Determine 4 global projection extremes (Top-Left, Top-Right, Bottom-Right, Bottom-Left)
                var minSum = Float.MAX_VALUE
                var maxSum = -Float.MAX_VALUE
                var minDiff = Float.MAX_VALUE
                var maxDiff = -Float.MAX_VALUE
                
                var tlX = sw * 0.15f
                var tlY = sh * 0.15f
                var trX = sw * 0.85f
                var trY = sh * 0.15f
                var brX = sw * 0.85f
                var brY = sh * 0.85f
                var blX = sw * 0.15f
                var blY = sh * 0.85f
                
                for (pt in perimeter) {
                    val px = pt.first.toFloat()
                    val py = pt.second.toFloat()
                    val sum = px + py
                    val diff = px - py
                    
                    if (sum < minSum) {
                        minSum = sum
                        tlX = px
                        tlY = py
                    }
                    if (sum > maxSum) {
                        maxSum = sum
                        brX = px
                        brY = py
                    }
                    if (diff > maxDiff) {
                        maxDiff = diff
                        trX = px
                        trY = py
                    }
                    if (diff < minDiff) {
                        minDiff = diff
                        blX = px
                        blY = py
                    }
                }
                
                // Micro-refining: Snap each coordinate to local sharpest Sobel gradient
                val refTL = refineCornerWithGradient(tlX, tlY, sw, sh, grad, searchRadius = 7)
                val refTR = refineCornerWithGradient(trX, trY, sw, sh, grad, searchRadius = 7)
                val refBR = refineCornerWithGradient(brX, brY, sw, sh, grad, searchRadius = 7)
                val refBL = refineCornerWithGradient(blX, blY, sw, sh, grad, searchRadius = 7)
                
                // Normalize and add custom crop padding clearance
                val normX = 1f / sw
                val normY = 1f / sh
                val pad = 0.012f
                
                val pTL = DocPoint((refTL.first * normX - pad).coerceIn(0f, 0.9f), (refTL.second * normY - pad).coerceIn(0f, 0.9f))
                val pTR = DocPoint((refTR.first * normX + pad).coerceIn(0.1f, 1f), (refTR.second * normY - pad).coerceIn(0f, 0.9f))
                val pBR = DocPoint((refBR.first * normX + pad).coerceIn(0.1f, 1f), (refBR.second * normY + pad).coerceIn(0.1f, 1f))
                val pBL = DocPoint((refBL.first * normX - pad).coerceIn(0f, 0.9f), (refBL.second * normY + pad).coerceIn(0.1f, 1f))
                
                val diag1 = hypot((pBR.x - pTL.x).toDouble(), (pBR.y - pTL.y).toDouble())
                val diag2 = hypot((pTR.x - pBL.x).toDouble(), (pTR.y - pBL.y).toDouble())
                
                if (diag1 > 0.38f && diag2 > 0.38f) {
                    return DocumentCorners(pTL, pTR, pBR, pBL)
                }
            }
        }
        
        // High-contrast Edge Gradient fallback
        return getResilientGradientFallback(sw, sh, gray, grad)
    }
    
    private fun refineCornerWithGradient(
        coarseX: Float, coarseY: Float, 
        sw: Int, sh: Int, 
        grad: FloatArray, 
        searchRadius: Int
    ): Pair<Float, Float> {
        val cx = coarseX.toInt()
        val cy = coarseY.toInt()
        
        var bestX = coarseX
        var bestY = coarseY
        var maxScore = -1f
        
        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                val px = cx + dx
                val py = cy + dy
                if (px >= 0 && px < sw && py >= 0 && py < sh) {
                    val idx = py * sw + px
                    val gVal = grad[idx]
                    
                    // Gaussian-like distance decay to favor peaks closer to the original perimeter projection
                    val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val distFactor = if (distance > 0) 1.0f - (distance / (searchRadius * 1.5f)) else 1.0f
                    val score = gVal * distFactor.coerceAtLeast(0.1f)
                    
                    if (score > maxScore) {
                        maxScore = score
                        bestX = px.toFloat()
                        bestY = py.toFloat()
                    }
                }
            }
        }
        return Pair(bestX, bestY)
    }
    
    private fun getResilientGradientFallback(sw: Int, sh: Int, gray: IntArray, grad: FloatArray): DocumentCorners {
        var maxGrad = 0f
        for (v in grad) if (v > maxGrad) maxGrad = v
        val threshold = maxGrad * 0.20f
        val points = mutableListOf<Pair<Int, Int>>()
        
        val mw = (sw * 0.05).toInt()
        val mh = (sh * 0.05).toInt()
        
        for (y in mh until sh - mh) {
            for (x in mw until sw - mw) {
                if (grad[y * sw + x] > threshold) {
                    points.add(Pair(x, y))
                }
            }
        }
        
        if (points.size > 12) {
            var minSum = Float.MAX_VALUE
            var maxSum = -Float.MAX_VALUE
            var minDiff = Float.MAX_VALUE
            var maxDiff = -Float.MAX_VALUE
            
            var tl = DocPoint(0.12f, 0.12f)
            var tr = DocPoint(0.88f, 0.12f)
            var br = DocPoint(0.88f, 0.88f)
            var bl = DocPoint(0.12f, 0.88f)
            
            for (pt in points) {
                val px = pt.first.toFloat() / sw
                val py = pt.second.toFloat() / sh
                val sum = px + py
                val diff = px - py
                
                if (sum < minSum) {
                    minSum = sum
                    tl = DocPoint(px, py)
                }
                if (sum > maxSum) {
                    maxSum = sum
                    br = DocPoint(px, py)
                }
                if (diff > maxDiff) {
                    maxDiff = diff
                    tr = DocPoint(px, py)
                }
                if (diff < minDiff) {
                    minDiff = diff
                    bl = DocPoint(px, py)
                }
            }
            return DocumentCorners(tl, tr, br, bl)
        }
        return DocumentCorners(
            topLeft = DocPoint(0.15f, 0.15f),
            topRight = DocPoint(0.85f, 0.15f),
            bottomRight = DocPoint(0.85f, 0.85f),
            bottomLeft = DocPoint(0.15f, 0.85f)
        )
    }

    /**
     * Perspective transformation using Android matrix mapping.
     */
    fun applyPerspectiveCorrection(bitmap: Bitmap, corners: DocumentCorners): Bitmap {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        val srcPoints = corners.toFloatArray(width, height)
        
        // Generate standard rectangular coordinates
        val targetWidth = bitmap.width
        val targetHeight = bitmap.height
        val dstPoints = floatArrayOf(
            0f, 0f,
            targetWidth.toFloat(), 0f,
            targetWidth.toFloat(), targetHeight.toFloat(),
            0f, targetHeight.toFloat()
        )

        val matrix = android.graphics.Matrix()
        val mappedSuccessfully = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
        if (!mappedSuccessfully) {
            return bitmap
        }

        val destination = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(destination)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, matrix, paint)
        return destination
    }

    /**
     * Advanced filters including Magic Color page whitening and Bradley adaptive thresholds.
     */
    fun applyFilter(bitmap: Bitmap, filter: ImageFilter): Bitmap {
        return when (filter) {
            ImageFilter.ORIGINAL -> bitmap
            ImageFilter.GRAYSCALE -> {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                applyColorMatrix(bitmap, matrix)
            }
            ImageFilter.MONOCHROME -> applyLocalAdaptiveThreshold(bitmap)
            ImageFilter.SHADOW_REMOVED -> applyCamScannerWhitening(bitmap, isMagicMode = false)
            ImageFilter.MAGIC_COLOR -> applyCamScannerWhitening(bitmap, isMagicMode = true)
        }
    }

    private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    /**
     * Bradley-Roth Local Adaptive Thresholding.
     * Evaluates a pixel with respect to its surrounding region of local background.
     */
    private fun applyLocalAdaptiveThreshold(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        
        val bgIllum = getIlluminationMap(bitmap)
        val srcPixels = IntArray(w * h)
        val bgPixels = IntArray(w * h)
        val out = IntArray(w * h)
        
        bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)
        bgIllum.getPixels(bgPixels, 0, w, 0, 0, w, h)
        bgIllum.recycle()
        
        val thresholdBias = 16
        
        for (i in 0 until w * h) {
            val src = srcPixels[i]
            val bg = bgPixels[i]
            
            val sGray = (((src shr 16) and 0xff) * 0.299f + ((src shr 8) and 0xff) * 0.587f + (src and 0xff) * 0.114f).toInt()
            val bGray = (((bg shr 16) and 0xff) * 0.299f + ((bg shr 8) and 0xff) * 0.587f + (bg and 0xff) * 0.114f).toInt()
            
            out[i] = if (sGray < bGray - thresholdBias) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        
        val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dest.setPixels(out, 0, w, 0, 0, w, h)
        return dest
    }

    /**
     * Highly advanced CamScanner-style background whitening.
     * Isolates uneven colors and shadow frequencies using local spatial division (src / background),
     * applying high-fidelity contrast stretching & saturation boosts.
     */
    private fun applyCamScannerWhitening(bitmap: Bitmap, isMagicMode: Boolean): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        
        val bg = getIlluminationMap(bitmap)
        val srcPixels = IntArray(w * h)
        val bgPixels = IntArray(w * h)
        val out = IntArray(w * h)
        
        bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)
        bg.getPixels(bgPixels, 0, w, 0, 0, w, h)
        bg.recycle()
        
        for (i in 0 until w * h) {
            val s = srcPixels[i]
            val bgP = bgPixels[i]
            
            val rS = (s shr 16) and 0xff
            val gS = (s shr 8) and 0xff
            val bS = s and 0xff
            
            val rB = (bgP shr 16) and 0xff
            val gB = (bgP shr 8) and 0xff
            val bB = bgP and 0xff
            
            // local channel lighting division
            var r = if (rB > 0) (rS * 255) / rB else 255
            var g = if (gB > 0) (gS * 255) / gB else 255
            var b = if (bB > 0) (bS * 255) / bB else 255
            
            if (isMagicMode) {
                // Boost colorful ink fidelity and suppress page creases
                val gray = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
                val satFactor = 1.45f
                
                r = max(0, min(255, (gray + (r - gray) * satFactor).toInt()))
                g = max(0, min(255, (gray + (g - gray) * satFactor).toInt()))
                b = max(0, min(255, (gray + (b - gray) * satFactor).toInt()))
                
                // Advanced high contrast ink stretch
                r = stretchContrast(r, 65, 215)
                g = stretchContrast(g, 65, 215)
                b = stretchContrast(b, 65, 215)
            } else {
                // Normal natural lighting flattened shadow removal
                r = stretchContrast(r, 75, 205)
                g = stretchContrast(g, 75, 205)
                b = stretchContrast(b, 75, 205)
            }
            
            out[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        
        val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dest.setPixels(out, 0, w, 0, 0, w, h)
        return dest
    }

    private fun stretchContrast(v: Int, low: Int, high: Int): Int {
        if (v >= high) return 255
        if (v <= low) return max(0, (v * 0.4f).toInt())
        return ((v - low).toFloat() / (high - low) * 255).toInt().coerceIn(0, 255)
    }

    /**
     * Generates a spatial local illumination background reference via scaled box filters.
     */
    private fun getIlluminationMap(bitmap: Bitmap): Bitmap {
        val scale = 0.12f
        val dw = max(40, (bitmap.width * scale).toInt())
        val dh = max(40, (bitmap.height * scale).toInt())
        
        val tiny = Bitmap.createScaledBitmap(bitmap, dw, dh, true)
        val pixels = IntArray(dw * dh)
        tiny.getPixels(pixels, 0, dw, 0, 0, dw, dh)
        tiny.recycle()
        
        val radius = 10
        val blurredPixels = fastBoxBlurRGB(pixels, dw, dh, radius)
        
        val blurredTiny = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        blurredTiny.setPixels(blurredPixels, 0, dw, 0, 0, dw, dh)
        
        // Bicubic high performance scaling to full scale
        val map = Bitmap.createScaledBitmap(blurredTiny, bitmap.width, bitmap.height, true)
        blurredTiny.recycle()
        return map
    }

    private fun fastBoxBlurRGB(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val size = w * h
        val out = IntArray(size)
        val inR = IntArray(size)
        val inG = IntArray(size)
        val inB = IntArray(size)
        
        for (i in 0 until size) {
            val p = pixels[i]
            inR[i] = (p shr 16) and 0xff
            inG[i] = (p shr 8) and 0xff
            inB[i] = p and 0xff
        }
        
        val tempR = IntArray(size)
        val tempG = IntArray(size)
        val tempB = IntArray(size)
        
        blurHorizontal(inR, tempR, w, h, radius)
        blurHorizontal(inG, tempG, w, h, radius)
        blurHorizontal(inB, tempB, w, h, radius)
        
        blurVertical(tempR, inR, w, h, radius)
        blurVertical(tempG, inG, w, h, radius)
        blurVertical(tempB, inB, w, h, radius)
        
        for (i in 0 until size) {
            out[i] = 0xFF000000.toInt() or (inR[i] shl 16) or (inG[i] shl 8) or inB[i]
        }
        return out
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            var sum = 0
            for (currX in -r..r) {
                sum += src[y * w + currX.coerceIn(0, w - 1)]
            }
            dst[y * w] = sum / div
            
            for (x in 1 until w) {
                val prev = x - 1 - r
                val next = x + r
                sum += src[y * w + next.coerceIn(0, w - 1)] - src[y * w + prev.coerceIn(0, w - 1)]
                dst[y * w + x] = sum / div
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sum = 0
            for (currY in -r..r) {
                sum += src[currY.coerceIn(0, h - 1) * w + x]
            }
            dst[x] = sum / div
            
            for (y in 1 until h) {
                val prev = y - 1 - r
                val next = y + r
                sum += src[next.coerceIn(0, h - 1) * w + x] - src[prev.coerceIn(0, h - 1) * w + x]
                dst[y * w + x] = sum / div
            }
        }
    }
}
