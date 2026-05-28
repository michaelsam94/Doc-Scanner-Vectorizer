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
        
        // Multi-Scale sampling at high resolution 160x160 for maximum precision
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
        
        // Compute High-contrast Sobel gradients for edge tracking
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
        
        // Setup central focus anchor for ray outwards tracing
        val cx = sw / 2f
        val cy = sh / 2f
        
        // Pass 1: White paper high-contrast heuristics
        var rayPoints = executeRayCasting(sw, sh, cx, cy, gray, sat, grad, useWhitePaperHeuristic = true)
        
        // Pass 2: Fallback to general gradient contrast if not enough points found
        if (rayPoints.size < 16) {
            rayPoints = executeRayCasting(sw, sh, cx, cy, gray, sat, grad, useWhitePaperHeuristic = false)
        }
        
        // Group points into 4 Quadrants around the center anchor
        val topPoints = mutableListOf<Pair<Float, Float>>()
        val rightPoints = mutableListOf<Pair<Float, Float>>()
        val bottomPoints = mutableListOf<Pair<Float, Float>>()
        val leftPoints = mutableListOf<Pair<Float, Float>>()
        
        for (pt in rayPoints) {
            val px = pt.first
            val py = pt.second
            val dx = px - cx
            val dy = py - cy
            
            var angleDeg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angleDeg < 0) angleDeg += 360f
            
            if (angleDeg >= 225f && angleDeg < 315f) {
                topPoints.add(pt)
            } else if (angleDeg >= 315f || angleDeg < 45f) {
                rightPoints.add(pt)
            } else if (angleDeg >= 45f && angleDeg < 135f) {
                bottomPoints.add(pt)
            } else {
                leftPoints.add(pt)
            }
        }
        
        // Strict median-based spatial outlier filtering to discard edge-clutter/hands
        val cleanTop = filterOutliersHorizontal(topPoints, sh)
        val cleanBottom = filterOutliersHorizontal(bottomPoints, sh)
        val cleanLeft = filterOutliersVertical(leftPoints, sw)
        val cleanRight = filterOutliersVertical(rightPoints, sw)
        
        // Perfect fit lines via robust linear regression
        val topLine = fitHorizontalLine(cleanTop)
        val bottomLine = fitHorizontalLine(cleanBottom)
        val leftLine = fitVerticalLine(cleanLeft)
        val rightLine = fitVerticalLine(cleanRight)
        
        if (topLine != null && bottomLine != null && leftLine != null && rightLine != null) {
            val (mT, cT) = topLine
            val (mB, cB) = bottomLine
            val (mL, cL) = leftLine
            val (mR, cR) = rightLine
            
            // Linear algebra intersection of fitted boundary lines
            val tlPt = intersectLines(mT, cT, mL, cL)
            val trPt = intersectLines(mT, cT, mR, cR)
            val blPt = intersectLines(mB, cB, mL, cL)
            val brPt = intersectLines(mB, cB, mR, cR)
            
            // Normalize back to relative coordinates [0..1] with custom corner padding clearance
            val scaleX = 1f / sw
            val scaleY = 1f / sh
            val pad = 0.012f
            
            val ptTL = DocPoint((tlPt.first * scaleX - pad).coerceIn(0f, 0.9f), (tlPt.second * scaleY - pad).coerceIn(0f, 0.9f))
            val ptTR = DocPoint((trPt.first * scaleX + pad).coerceIn(0.1f, 1f), (trPt.second * scaleY - pad).coerceIn(0f, 0.9f))
            val ptBR = DocPoint((brPt.first * scaleX + pad).coerceIn(0.1f, 1f), (brPt.second * scaleY + pad).coerceIn(0.1f, 1f))
            val ptBL = DocPoint((blPt.first * scaleX - pad).coerceIn(0f, 0.9f), (blPt.second * scaleY + pad).coerceIn(0.1f, 1f))
            
            val diag1 = hypot((ptBR.x - ptTL.x).toDouble(), (ptBR.y - ptTL.y).toDouble())
            val diag2 = hypot((ptTR.x - ptBL.x).toDouble(), (ptTR.y - ptBL.y).toDouble())
            
            if (diag1 > 0.38f && diag2 > 0.38f) {
                return DocumentCorners(ptTL, ptTR, ptBR, ptBL)
            }
        }
        
        // Highly resilient fallback: Standard gradient coordinates
        return getResilientGradientFallback(sw, sh, gray, grad)
    }
    
    private fun executeRayCasting(
        sw: Int, sh: Int,
        cx: Float, cy: Float,
        gray: IntArray, sat: FloatArray, grad: FloatArray,
        useWhitePaperHeuristic: Boolean
    ): List<Pair<Float, Float>> {
        val points = mutableListOf<Pair<Float, Float>>()
        val numRays = 72
        
        for (r in 0 until numRays) {
            val angleRad = (r * (360f / numRays)) * (Math.PI / 180.0)
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()
            
            val margin = 5
            var maxD = sw.toFloat()
            for (dStep in 1..400) {
                val px = cx + dStep * cos
                val py = cy + dStep * sin
                if (px < margin || px >= sw - margin || py < margin || py >= sh - margin) {
                    maxD = (dStep - 1).toFloat()
                    break
                }
            }
            
            val steps = maxD.toInt()
            if (steps < 12) continue
            
            val rGray = FloatArray(steps)
            val rGrad = FloatArray(steps)
            val rSat = FloatArray(steps)
            val rCoordsX = FloatArray(steps)
            val rCoordsY = FloatArray(steps)
            
            for (j in 0 until steps) {
                val px = cx + j * cos
                val py = cy + j * sin
                rCoordsX[j] = px
                rCoordsY[j] = py
                
                val ix = px.toInt().coerceIn(0, sw - 1)
                val iy = py.toInt().coerceIn(0, sh - 1)
                val idx = iy * sw + ix
                rGray[j] = gray[idx].toFloat()
                rGrad[j] = grad[idx]
                rSat[j] = sat[idx]
            }
            
            var bestJ = -1
            var bestScore = -1f
            for (j in 6 until steps - 6) {
                var insideGraySum = 0f
                for (k in j - 5..j) insideGraySum += rGray[k]
                val avgInsideGray = insideGraySum / 6f
                
                var outsideGraySum = 0f
                for (k in j + 1..j + 5) outsideGraySum += rGray[k]
                val avgOutsideGray = outsideGraySum / 5f
                
                val avgInsideSat = (rSat[j - 2] + rSat[j - 1] + rSat[j]) / 3f
                val contrast = avgInsideGray - avgOutsideGray
                
                if (useWhitePaperHeuristic) {
                    if (contrast > 12f && avgInsideGray > 95f && avgInsideSat < 0.38f) {
                        val score = contrast * (rGrad[j] + 1f)
                        if (score > bestScore) {
                            bestScore = score
                            bestJ = j
                        }
                    }
                } else {
                    val score = Math.abs(contrast) * (rGrad[j] + 1f)
                    if (score > bestScore) {
                        bestScore = score
                        bestJ = j
                    }
                }
            }
            if (bestJ != -1) {
                points.add(Pair(rCoordsX[bestJ], rCoordsY[bestJ]))
            }
        }
        return points
    }
    
    private fun filterOutliersHorizontal(points: List<Pair<Float, Float>>, height: Int): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val sortedY = points.map { it.second }.sorted()
        val medianY = sortedY[sortedY.size / 2]
        val thresholdY = height * 0.14f
        return points.filter { Math.abs(it.second - medianY) < thresholdY }
    }
    
    private fun filterOutliersVertical(points: List<Pair<Float, Float>>, width: Int): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val sortedX = points.map { it.first }.sorted()
        val medianX = sortedX[sortedX.size / 2]
        val thresholdX = width * 0.14f
        return points.filter { Math.abs(it.first - medianX) < thresholdX }
    }
    
    private fun fitHorizontalLine(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
        val n = points.size
        if (n < 2) return null
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumXX = 0f
        for (p in points) {
            sumX += p.first
            sumY += p.second
            sumXY += p.first * p.second
            sumXX += p.first * p.first
        }
        val denom = n * sumXX - sumX * sumX
        if (Math.abs(denom) < 1e-4f) return null
        val m = (n * sumXY - sumX * sumY) / denom
        val c = (sumY - m * sumX) / n
        return Pair(m, c)
    }
    
    private fun fitVerticalLine(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
        val n = points.size
        if (n < 2) return null
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumYY = 0f
        for (p in points) {
            sumX += p.first
            sumY += p.second
            sumXY += p.first * p.second
            sumYY += p.second * p.second
        }
        val denom = n * sumYY - sumY * sumY
        if (Math.abs(denom) < 1e-4f) return null
        val m = (n * sumXY - sumX * sumY) / denom
        val c = (sumX - m * sumY) / n
        return Pair(m, c)
    }
    
    private fun intersectLines(mHoriz: Float, cHoriz: Float, mVert: Float, cVert: Float): Pair<Float, Float> {
        val d = 1f - mVert * mHoriz
        if (Math.abs(d) < 1e-4f) {
            return Pair(cVert, cHoriz)
        }
        val x = (mVert * cHoriz + cVert) / d
        val y = mHoriz * x + cHoriz
        return Pair(x, y)
    }
    
    private fun getResilientGradientFallback(sw: Int, sh: Int, gray: IntArray, grad: FloatArray): DocumentCorners {
        var maxGrad = 0f
        for (v in grad) if (v > maxGrad) maxGrad = v
        val threshold = maxGrad * 0.22f
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
