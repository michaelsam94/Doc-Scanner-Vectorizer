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
        val targetWidth = 220
        val targetHeight = 220
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
        
        // Calculate Otsu Adaptive threshold for extreme noise binarization
        val total = sw * sh
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
        
        val adaptiveThreshold = min(paperThreshold, thresholdOtsu.coerceIn(80, 180))
        
        // --- TEXT ELIMINATION SMOOTHING ---
        // Apply spatial box blur to gray channel to wash out characters and small letters,
        // leaving ONLY the massive outer document structure boundaries for Sobel!
        val blurredGray = IntArray(sw * sh)
        blurGrayHorizontal(gray, blurredGray, sw, sh, 2)
        val finalGray = IntArray(sw * sh)
        blurGrayVertical(blurredGray, finalGray, sw, sh, 2)
        
        // High-contrast Sobel gradient space calculated on blurred gray
        val grad = FloatArray(sw * sh)
        var maxGrad = 0f
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val gx = (finalGray[idx - 1 + sw] - finalGray[idx - 1 - sw] + 
                         2 * finalGray[idx + sw] - 2 * finalGray[idx - sw] + 
                         finalGray[idx + 1 + sw] - finalGray[idx + 1 - sw])
                         
                val gy = (finalGray[idx - 1 - sw] - finalGray[idx + 1 - sw] + 
                         2 * finalGray[idx - 1] - 2 * finalGray[idx + 1] + 
                         finalGray[idx - 1 + sw] - finalGray[idx + 1 + sw])
                
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
            isWhitePaper[i] = (gVal >= adaptiveThreshold && sVal < 0.32f) || (gVal > 185 && sVal < 0.40f)
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
        
        // Determine document centroid
        var cx = sw / 2f
        var cy = sh / 2f
        val isPaperFound = maxComponent.size >= (sw * sh) * 0.05
        if (isPaperFound) {
            var sumX = 0.0
            var sumY = 0.0
            for (idx in maxComponent) {
                sumX += idx % sw
                sumY += idx / sw
            }
            cx = (sumX / maxComponent.size).toFloat()
            cy = (sumY / maxComponent.size).toFloat()
        }
        
        // Collect candidate boundary points from TWO highly robust sources:
        // 1. Blob outer boundary perimeter
        // 2. Centroid-outwards Active-Contour profile ray-casting
        val boundaryPoints = ArrayList<Pair<Float, Float>>()
        
        val blobSet = BooleanArray(sw * sh)
        if (isPaperFound) {
            for (idx in maxComponent) {
                blobSet[idx] = true
            }
            for (idx in maxComponent) {
                val x = idx % sw
                val y = idx / sw
                if (x > marginW && x < sw - marginW - 1 && y > marginH && y < sh - marginH - 1) {
                    val isPerimeter = !blobSet[idx - 1] || !blobSet[idx + 1] || 
                                      !blobSet[idx - sw] || !blobSet[idx + sw]
                    if (isPerimeter) {
                        boundaryPoints.add(Pair(x.toFloat(), y.toFloat()))
                    }
                }
            }
        }
        
        // Active-Contour Ray-Casting (90 rays = 4-degree angular resolution)
        val numRays = 90
        for (r in 0 until numRays) {
            val angleRad = (r * (360f / numRays)) * (Math.PI / 180.0)
            val cos = Math.cos(angleRad).toFloat()
            val sin = Math.sin(angleRad).toFloat()
            
            val maxSteps = 400
            var actualSteps = 0
            for (step in 1..maxSteps) {
                val rx = cx + step * cos
                val ry = cy + step * sin
                if (rx < marginW.toFloat() || rx >= (sw - marginW).toFloat() || 
                    ry < marginH.toFloat() || ry >= (sh - marginH).toFloat()) {
                    actualSteps = step - 1
                    break
                }
            }
            
            if (actualSteps < 15) continue
            
            val rGray = FloatArray(actualSteps)
            val rGrad = FloatArray(actualSteps)
            val rX = FloatArray(actualSteps)
            val rY = FloatArray(actualSteps)
            
            for (j in 0 until actualSteps) {
                val rx = cx + j * cos
                val ry = cy + j * sin
                rX[j] = rx
                rY[j] = ry
                
                val ix = rx.toInt().coerceIn(0, sw - 1)
                val iy = ry.toInt().coerceIn(0, sh - 1)
                rGray[j] = finalGray[iy * sw + ix].toFloat()
                rGrad[j] = grad[iy * sw + ix]
            }
            
            var bestJ = -1
            var bestScore = -1f
            val windowSize = 4
            for (j in windowSize until actualSteps - windowSize) {
                var insideSum = 0f
                for (k in (j - windowSize) until j) insideSum += rGray[k]
                val avgInside = insideSum / windowSize
                
                var outsideSum = 0f
                for (k in (j + 1)..(j + windowSize)) outsideSum += rGray[k]
                val avgOutside = outsideSum / windowSize
                
                val contrast = Math.abs(avgInside - avgOutside)
                // Score is a weighted combination of gradient spike and intensity boundary contrast
                val score = rGrad[j] * 0.45f + contrast * 0.55f
                if (score > bestScore) {
                    bestScore = score
                    bestJ = j
                }
            }
            
            if (bestJ != -1 && bestScore > 8.5f) {
                boundaryPoints.add(Pair(rX[bestJ], rY[bestJ]))
            }
        }
        
        // Classify candidate boundary points geometrically by angle around the centroid into 4 distinct quadrants (sectors)
        // This fully enforces the rigid rectangle structure, as points on each side are fit independently!
        val sectorTop = ArrayList<Pair<Float, Float>>()
        val sectorRight = ArrayList<Pair<Float, Float>>()
        val sectorBottom = ArrayList<Pair<Float, Float>>()
        val sectorLeft = ArrayList<Pair<Float, Float>>()
        
        for (pt in boundaryPoints) {
            val dx = pt.first - cx
            val dy = pt.second - cy
            val angle = Math.atan2(dy.toDouble(), dx.toDouble())
            
            if (angle >= -3.0 * Math.PI / 4.0 && angle < -Math.PI / 4.0) {
                sectorTop.add(pt)
            } else if (angle >= -Math.PI / 4.0 && angle < Math.PI / 4.0) {
                sectorRight.add(pt)
            } else if (angle >= Math.PI / 4.0 && angle < 3.0 * Math.PI / 4.0) {
                sectorBottom.add(pt)
            } else {
                sectorLeft.add(pt)
            }
        }
        
        // Fit robust boundaries using RANSAC to completely reject clutter, fingers, and background transitions
        val topLine = fitLineYasX(sectorTop, tolerance = 2.0f)
        val bottomLine = fitLineYasX(sectorBottom, tolerance = 2.0f)
        val leftLine = fitLineXasY(sectorLeft, tolerance = 2.0f)
        val rightLine = fitLineXasY(sectorRight, tolerance = 2.0f)
        
        if (topLine != null && bottomLine != null && leftLine != null && rightLine != null) {
            val (mT, cT) = topLine
            val (mB, cB) = bottomLine
            val (mL, cL) = leftLine
            val (mR, cR) = rightLine
            
            // Solve mathematical intersection of 4 straight lines to produce mathematically pure corners
            val denomTL = 1f - mL * mT
            val tlX = if (Math.abs(denomTL) > 1e-4f) (mL * cT + cL) / denomTL else cL
            val tlY = mT * tlX + cT
            
            val denomTR = 1f - mR * mT
            val trX = if (Math.abs(denomTR) > 1e-4f) (mR * cT + cR) / denomTR else cR
            val trY = mT * trX + cT
            
            val denomBL = 1f - mL * mB
            val blX = if (Math.abs(denomBL) > 1e-4f) (mL * cB + cL) / denomBL else cL
            val blY = mB * blX + cB
            
            val denomBR = 1f - mR * mB
            val brX = if (Math.abs(denomBR) > 1e-4f) (mR * cB + cR) / denomBR else cR
            val brY = mB * brX + cB
            
            // Relative normal coordinates within viewport
            val normX = 1f / sw
            val normY = 1f / sh
            val pad = 0.012f
            
            val pTL = DocPoint((tlX * normX - pad).coerceIn(0f, 0.9f), (tlY * normY - pad).coerceIn(0f, 0.9f))
            val pTR = DocPoint((trX * normX + pad).coerceIn(0.1f, 1f), (trY * normY - pad).coerceIn(0f, 0.9f))
            val pBR = DocPoint((brX * normX + pad).coerceIn(0.1f, 1f), (brY * normY + pad).coerceIn(0.1f, 1f))
            val pBL = DocPoint((blX * normX - pad).coerceIn(0f, 0.9f), (blY * normY + pad).coerceIn(0.1f, 1f))
            
            val diag1 = hypot((pBR.x - pTL.x).toDouble(), (pBR.y - pTL.y).toDouble())
            val diag2 = hypot((pTR.x - pBL.x).toDouble(), (pTR.y - pBL.y).toDouble())
            
            // Geometric convexity verification to ensure we have a mathematically valid quadrilateral
            if (diag1 > 0.38f && diag2 > 0.38f && isConvex(pTL, pTR, pBR, pBL)) {
                return DocumentCorners(pTL, pTR, pBR, pBL)
            }
        }
        
        // High-contrast Edge Gradient fallback
        return getResilientGradientFallback(sw, sh, gray, grad)
    }
    
    private fun blurGrayHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
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

    private fun blurGrayVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
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

    private fun isConvex(tl: DocPoint, tr: DocPoint, br: DocPoint, bl: DocPoint): Boolean {
        fun crossProduct(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
            return (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        }
        val cp1 = crossProduct(tl.x, tl.y, tr.x, tr.y, br.x, br.y)
        val cp2 = crossProduct(tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val cp3 = crossProduct(br.x, br.y, bl.x, bl.y, tl.x, tl.y)
        val cp4 = crossProduct(bl.x, bl.y, tl.x, tl.y, tr.x, tr.y)
        
        val allPositive = cp1 > 0f && cp2 > 0f && cp3 > 0f && cp4 > 0f
        val allNegative = cp1 < 0f && cp2 < 0f && cp3 < 0f && cp4 < 0f
        return allPositive || allNegative
    }

    // RANSAC Horizontal Line fitting (y = m * x + c)
    private fun fitLineYasX(points: List<Pair<Float, Float>>, tolerance: Float): Pair<Float, Float>? {
        if (points.size < 2) return null
        var bestM = 0f
        var bestC = 0f
        var maxInliers = -1
        val rand = java.util.Random(42)
        
        val numIterations = min(40, points.size * (points.size - 1) / 2).coerceAtLeast(20)
        for (i in 0 until numIterations) {
            val p1 = points[rand.nextInt(points.size)]
            val p2 = points[rand.nextInt(points.size)]
            if (p1 == p2) continue
            val dx = p2.first - p1.first
            if (Math.abs(dx) < 1e-4f) continue
            
            val m = (p2.second - p1.second) / dx
            if (Math.abs(m) > 1.3f) continue // constrain horizontal lines to proper horizontal-ish boundary
            
            val c = p1.second - m * p1.first
            
            var inliers = 0
            for (p in points) {
                if (Math.abs(p.second - (m * p.first + c)) < tolerance) {
                    inliers++
                }
            }
            if (inliers > maxInliers) {
                maxInliers = inliers
                bestM = m
                bestC = c
            }
        }
        
        if (maxInliers >= 2) {
            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumXX = 0.0
            var count = 0
            for (p in points) {
                if (Math.abs(p.second - (bestM * p.first + bestC)) < tolerance) {
                    sumX += p.first
                    sumY += p.second
                    sumXY += p.first * p.second
                    sumXX += p.first * p.first
                    count++
                }
            }
            val denom = count * sumXX - sumX * sumX
            if (Math.abs(denom) > 1e-4) {
                val m = ((count * sumXY - sumX * sumY) / denom).toFloat()
                val c = ((sumY - m * sumX) / count).toFloat()
                return Pair(m, c)
            }
        }
        return if (maxInliers > 0) Pair(bestM, bestC) else null
    }

    // RANSAC Vertical Line fitting (x = m * y + c)
    private fun fitLineXasY(points: List<Pair<Float, Float>>, tolerance: Float): Pair<Float, Float>? {
        if (points.size < 2) return null
        var bestM = 0f
        var bestC = 0f
        var maxInliers = -1
        val rand = java.util.Random(42)
        
        val numIterations = min(40, points.size * (points.size - 1) / 2).coerceAtLeast(20)
        for (i in 0 until numIterations) {
            val p1 = points[rand.nextInt(points.size)]
            val p2 = points[rand.nextInt(points.size)]
            if (p1 == p2) continue
            val dy = p2.second - p1.second
            if (Math.abs(dy) < 1e-4f) continue
            
            val m = (p2.first - p1.first) / dy
            if (Math.abs(m) > 1.3f) continue // constrain vertical lines to proper vertical-ish boundary
            
            val c = p1.first - m * p1.second
            
            var inliers = 0
            for (p in points) {
                if (Math.abs(p.first - (m * p.second + c)) < tolerance) {
                    inliers++
                }
            }
            if (inliers > maxInliers) {
                maxInliers = inliers
                bestM = m
                bestC = c
            }
        }
        
        if (maxInliers >= 2) {
            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumYY = 0.0
            var count = 0
            for (p in points) {
                if (Math.abs(p.first - (bestM * p.second + bestC)) < tolerance) {
                    sumX += p.first
                    sumY += p.second
                    sumXY += p.first * p.second
                    sumYY += p.second * p.second
                    count++
                }
            }
            val denom = count * sumYY - sumY * sumY
            if (Math.abs(denom) > 1e-4) {
                val m = ((count * sumXY - sumX * sumY) / denom).toFloat()
                val c = ((sumX - m * sumY) / count).toFloat()
                return Pair(m, c)
            }
        }
        return if (maxInliers > 0) Pair(bestM, bestC) else null
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
        val finalOut = if (isMagicMode) {
            applyLaplacianSharpening(out, w, h, strength = 0.35f)
        } else {
            out
        }
        dest.setPixels(finalOut, 0, w, 0, 0, w, h)
        return dest
    }

    private fun applyLaplacianSharpening(pixels: IntArray, w: Int, h: Int, strength: Float): IntArray {
        val out = IntArray(w * h)
        // copy borders of original image
        for (x in 0 until w) {
            out[x] = pixels[x]
            out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            out[y * w] = pixels[y * w]
            out[y * w + (w - 1)] = pixels[y * w + (w - 1)]
        }
        
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                
                val p = pixels[idx]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                
                val pT = pixels[idx - w]
                val rT = (pT shr 16) and 0xff
                val gT = (pT shr 8) and 0xff
                val bT = pT and 0xff
                
                val pB = pixels[idx + w]
                val rB = (pB shr 16) and 0xff
                val gB = (pB shr 8) and 0xff
                val bB = pB and 0xff
                
                val pL = pixels[idx - 1]
                val rL = (pL shr 16) and 0xff
                val gL = (pL shr 8) and 0xff
                val bL = pL and 0xff
                
                val pR = pixels[idx + 1]
                val rR = (pR shr 16) and 0xff
                val gR = (pR shr 8) and 0xff
                val bR = pR and 0xff
                
                // Laplacian component = 4*self - (top + bottom + left + right)
                val lapR = 4 * r - (rT + rB + rL + rR)
                val lapG = 4 * g - (gT + gB + gL + gR)
                val lapB = 4 * b - (bT + bB + bL + bR)
                
                val sR = (r + strength * lapR).toInt().coerceIn(0, 255)
                val sG = (g + strength * lapG).toInt().coerceIn(0, 255)
                val sB = (b + strength * lapB).toInt().coerceIn(0, 255)
                
                out[idx] = 0xFF000000.toInt() or (sR shl 16) or (sG shl 8) or sB
            }
        }
        return out
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
