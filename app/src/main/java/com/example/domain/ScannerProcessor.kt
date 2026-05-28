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
     * Integrates intelligent Otsu binarization and saturation filters to isolate 
     * white paper sheets, using connected-component analysis for extreme noise immunity.
     * Gracefully falls back to high-fidelity Sobel gradient calculation.
     */
    fun detectDocumentCorners(bitmap: Bitmap): DocumentCorners {
        val width = bitmap.width
        val height = bitmap.height
        
        // Standard small scaling for ultra-fast, stutter-free real-time calculations
        val targetSize = 140
        val sc = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, false)
        val sw = sc.width
        val sh = sc.height
        
        val pixels = IntArray(sw * sh)
        sc.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        sc.recycle()
        
        val gray = IntArray(sw * sh)
        val sat = FloatArray(sw * sh)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
            
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            sat[i] = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
        }
        
        // 1. Dynamic Otsu Adaptive Thresholding on Grayscale values to isolate paper
        val hist = IntArray(256)
        for (v in gray) {
            hist[v.coerceIn(0, 255)]++
        }
        val total = gray.size
        var sum = 0f
        for (t in 0..255) {
            sum += t * hist[t]
        }
        var sumB = 0f
        var wB = 0
        var varMax = 0f
        var thresholdOtsu = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t].toFloat()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val varBetween = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
            if (varBetween > varMax) {
                varMax = varBetween
                thresholdOtsu = t
            }
        }
        
        val targetThreshold = thresholdOtsu.coerceIn(90, 195)
        
        // Define White Paper Mask (bright + low-to-moderate saturation for neutral colors)
        val isWhitePaper = BooleanArray(sw * sh)
        for (i in gray.indices) {
            val gVal = gray[i]
            val sVal = sat[i]
            isWhitePaper[i] = (gVal >= targetThreshold && sVal < 0.35f) || (gVal > 185 && sVal < 0.45f)
        }
        
        val mw = (sw * 0.04).toInt()
        val mh = (sh * 0.04).toInt()
        
        // 2. Extract largest contiguous connected component of white paper pixels (removes table elements, hands, clutter)
        val visited = BooleanArray(sw * sh)
        var maxComponentPoints = ArrayList<Int>()
        val queue = IntArray(sw * sh)
        
        for (y in mh until sh - mh) {
            for (x in mw until sw - mw) {
                val idx = y * sw + x
                if (isWhitePaper[idx] && !visited[idx]) {
                    var head = 0
                    var tail = 0
                    val component = ArrayList<Int>()
                    
                    queue[tail++] = idx
                    visited[idx] = true
                    
                    while (head < tail) {
                        val curr = queue[head++]
                        component.add(curr)
                        
                        val cx = curr % sw
                        val cy = curr / sw
                        
                        // 4-connected scan neighbors
                        val neighbors = intArrayOf(curr - 1, curr + 1, curr - sw, curr + sw)
                        val nxCoords = intArrayOf(cx - 1, cx + 1, cx, cx)
                        val nyCoords = intArrayOf(cy, cy, cy - 1, cy + 1)
                        
                        for (n in 0 until 4) {
                            val nIdx = neighbors[n]
                            val nx = nxCoords[n]
                            val ny = nyCoords[n]
                            
                            if (nx >= mw && nx < sw - mw && ny >= mh && ny < sh - mh) {
                                if (isWhitePaper[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[tail++] = nIdx
                                }
                            }
                        }
                    }
                    if (component.size > maxComponentPoints.size) {
                        maxComponentPoints = component
                    }
                }
            }
        }
        
        // If the white paper component represents a plausible document size (> 5.5% of the viewport)
        if (maxComponentPoints.size > (sw * sh) * 0.055) {
            val largestSet = BooleanArray(sw * sh)
            for (idx in maxComponentPoints) {
                largestSet[idx] = true
            }
            
            val isBoundary = BooleanArray(sw * sh)
            for (idx in maxComponentPoints) {
                val cx = idx % sw
                val cy = idx / sw
                if (cx > mw && cx < sw - mw - 1 && cy > mh && cy < sh - mh - 1) {
                    if (!largestSet[idx - 1] || !largestSet[idx + 1] || 
                        !largestSet[idx - sw] || !largestSet[idx + sw]) {
                        isBoundary[idx] = true
                    }
                } else {
                    isBoundary[idx] = true
                }
            }
            
            var minSum = Float.MAX_VALUE
            var maxSum = -Float.MAX_VALUE
            var minDiff = Float.MAX_VALUE
            var maxDiff = -Float.MAX_VALUE
            
            var tl = DocPoint(0.15f, 0.15f)
            var tr = DocPoint(0.85f, 0.15f)
            var br = DocPoint(0.85f, 0.85f)
            var bl = DocPoint(0.15f, 0.85f)
            
            var pointsFound = false
            for (y in mh until sh - mh) {
                for (x in mw until sw - mw) {
                    val idx = y * sw + x
                    if (isBoundary[idx]) {
                        val px = x.toFloat() / sw
                        val py = y.toFloat() / sh
                        val sum = px + py
                        val diff = px - py
                        
                        if (sum < minSum) {
                            minSum = sum
                            tl = DocPoint(px, py)
                            pointsFound = true
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
                }
            }
            
            if (pointsFound) {
                val diag1 = hypot((br.x - tl.x).toDouble(), (br.y - tl.y).toDouble())
                val diag2 = hypot((tr.x - bl.x).toDouble(), (tr.y - bl.y).toDouble())
                
                if (diag1 > 0.35f && diag2 > 0.35f) {
                    val pad = 0.01f // Clean padding buffer for cropping
                    return DocumentCorners(
                        topLeft = DocPoint(max(0.01f, tl.x - pad), max(0.01f, tl.y - pad)),
                        topRight = DocPoint(min(0.99f, tr.x + pad), max(0.01f, tr.y - pad)),
                        bottomRight = DocPoint(min(0.99f, br.x + pad), min(0.99f, br.y + pad)),
                        bottomLeft = DocPoint(max(0.01f, bl.x - pad), min(0.99f, bl.y + pad))
                    )
                }
            }
        }
        
        // 3. Fallback: Sobel Intensity Gradients (for high-contrast/colored sheets or dark/shadowed setups)
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
        
        val threshold = maxGrad * 0.22f
        val points = mutableListOf<Pair<Int, Int>>()
        
        for (y in mh until sh - mh) {
            for (x in mw until sw - mw) {
                if (grad[y * sw + x] > threshold) {
                    points.add(Pair(x, y))
                }
            }
        }
        
        if (points.size > 15) {
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
            
            // Check viability of diagonal distances
            val diag1 = hypot((br.x - tl.x).toDouble(), (br.y - tl.y).toDouble())
            val diag2 = hypot((tr.x - bl.x).toDouble(), (tr.y - bl.y).toDouble())
            
            if (diag1 > 0.35f && diag2 > 0.35f) {
                val pad = 0.015f
                return DocumentCorners(
                    topLeft = DocPoint(max(0f, tl.x - pad), max(0f, tl.y - pad)),
                    topRight = DocPoint(min(1f, tr.x + pad), max(0f, tr.y - pad)),
                    bottomRight = DocPoint(min(1f, br.x + pad), min(1f, br.y + pad)),
                    bottomLeft = DocPoint(max(0f, bl.x - pad), min(1f, bl.y + pad))
                )
            }
        }
        
        // Return clear proportional default layout
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
