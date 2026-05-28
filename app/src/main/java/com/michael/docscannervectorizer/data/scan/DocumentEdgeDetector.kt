package com.michael.docscannervectorizer.data.scan

import android.graphics.Bitmap
import com.michael.docscannervectorizer.domain.model.DocPoint
import com.michael.docscannervectorizer.domain.model.DocumentCorners
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Automatic document boundary detection using a classic vision pipeline:
 * resize → grayscale → blur → Canny → contour extraction → quadrilateral fit.
 */
internal object DocumentEdgeDetector {

    private const val MAX_PROCESSING_EDGE = 960
    private const val CANNY_LOW_RATIO = 0.33
    private const val CANNY_HIGH_RATIO = 0.66
    private const val MIN_QUAD_AREA_RATIO = 0.08
    private const val POLY_APPROX_EPSILON = 0.02

    fun detect(bitmap: Bitmap): DocumentCorners {
        val scale = min(
            1f,
            MAX_PROCESSING_EDGE.toFloat() / max(bitmap.width, bitmap.height).toFloat()
        )
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        val working = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        val gray = toGrayscale(working)
        val blurred = gaussianBlur5x5(gray, w, h)
        val edges = canny(blurred, w, h)
        val closed = dilate(erode(edges, w, h), w, h)

        val quad = findLargestQuadrilateral(closed, w, h)
            ?: return fallbackCorners()

        if (working !== bitmap) working.recycle()

        return orderAndNormalize(quad, w, h)
    }

    private fun fallbackCorners() = DocumentCorners(
        topLeft = DocPoint(0.08f, 0.08f),
        topRight = DocPoint(0.92f, 0.08f),
        bottomRight = DocPoint(0.92f, 0.92f),
        bottomLeft = DocPoint(0.08f, 0.92f)
    )

    private fun toGrayscale(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        return gray
    }

    private fun gaussianBlur5x5(src: IntArray, w: Int, h: Int): IntArray {
        val kernel = intArrayOf(1, 4, 6, 4, 1)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val kSum = 16

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (kx in -2..2) {
                    val px = (x + kx).coerceIn(0, w - 1)
                    sum += src[y * w + px] * kernel[kx + 2]
                }
                tmp[y * w + x] = sum / kSum
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (ky in -2..2) {
                    val py = (y + ky).coerceIn(0, h - 1)
                    sum += tmp[py * w + x] * kernel[ky + 2]
                }
                out[y * w + x] = sum / kSum
            }
        }
        return out
    }

    private fun canny(gray: IntArray, w: Int, h: Int): BooleanArray {
        val magnitude = FloatArray(w * h)
        val direction = IntArray(w * h)
        var maxMag = 0f

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (-gray[(y - 1) * w + (x - 1)] + gray[(y - 1) * w + (x + 1)]
                    - 2 * gray[y * w + (x - 1)] + 2 * gray[y * w + (x + 1)]
                    - gray[(y + 1) * w + (x - 1)] + gray[(y + 1) * w + (x + 1)]).toFloat()
                val gy = (-gray[(y - 1) * w + (x - 1)] - 2 * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)]
                    + gray[(y + 1) * w + (x - 1)] + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)]).toFloat()
                val mag = hypot(gx.toDouble(), gy.toDouble()).toFloat()
                magnitude[y * w + x] = mag
                if (mag > maxMag) maxMag = mag
                direction[y * w + x] = when {
                    abs(gx) < abs(gy) * 0.4f -> 0
                    abs(gy) < abs(gx) * 0.4f -> 1
                    gx * gy > 0 -> 2
                    else -> 3
                }
            }
        }

        val suppressed = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val m = magnitude[idx]
                val n1: Float
                val n2: Float
                when (direction[idx]) {
                    0 -> { n1 = magnitude[idx - w]; n2 = magnitude[idx + w] }
                    1 -> { n1 = magnitude[idx - 1]; n2 = magnitude[idx + 1] }
                    2 -> { n1 = magnitude[idx - w - 1]; n2 = magnitude[idx + w + 1] }
                    else -> { n1 = magnitude[idx - w + 1]; n2 = magnitude[idx + w - 1] }
                }
                suppressed[idx] = if (m >= n1 && m >= n2) m else 0f
            }
        }

        val high = maxMag * CANNY_HIGH_RATIO
        val low = maxMag * CANNY_LOW_RATIO
        val strong = BooleanArray(w * h)
        val weak = BooleanArray(w * h)

        for (i in suppressed.indices) {
            when {
                suppressed[i] >= high -> strong[i] = true
                suppressed[i] >= low -> weak[i] = true
            }
        }

        val edges = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        for (i in strong.indices) {
            if (strong[i]) {
                edges[i] = true
                stack.addLast(i)
            }
        }
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            val x = idx % w
            val y = idx / w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (!edges[ni] && weak[ni]) {
                        edges[ni] = true
                        stack.addLast(ni)
                    }
                }
            }
        }
        return edges
    }

    private fun erode(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var all = true
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (!src[(y + dy) * w + (x + dx)]) {
                            all = false
                            break@loop
                        }
                    }
                }
                out[y * w + x] = all
            }
        }
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var any = false
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (src[(y + dy) * w + (x + dx)]) {
                            any = true
                            break@loop
                        }
                    }
                }
                out[y * w + x] = any
            }
        }
        return out
    }

    private fun findLargestQuadrilateral(edges: BooleanArray, w: Int, h: Int): List<PointF>? {
        val visited = BooleanArray(w * h)
        val imageArea = w * h
        var bestQuad: List<PointF>? = null
        var bestArea = 0.0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val start = y * w + x
                if (!edges[start] || visited[start]) continue
                val contour = traceContour(edges, visited, w, h, x, y)
                if (contour.size < 40) continue

                val hull = convexHull(contour)
                if (hull.size < 4) continue

                val approx = douglasPeucker(hull, POLY_APPROX_EPSILON * perimeter(hull))
                if (approx.size != 4) continue

                val area = quadrilateralArea(approx)
                if (area < imageArea * MIN_QUAD_AREA_RATIO) continue
                if (!isConvex(approx)) continue
                if (area > bestArea) {
                    bestArea = area
                    bestQuad = approx
                }
            }
        }
        return bestQuad
    }

    private fun traceContour(
        edges: BooleanArray,
        visited: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int
    ): List<PointF> {
        val points = mutableListOf<PointF>()
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(startX to startY)
        visited[startY * w + startX] = true

        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            points.add(PointF(x.toFloat(), y.toFloat()))
            if (points.size > 2500) break

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val idx = ny * w + nx
                    if (edges[idx] && !visited[idx]) {
                        visited[idx] = true
                        stack.addLast(nx to ny)
                    }
                }
            }
        }
        return points
    }

    private fun convexHull(points: List<PointF>): List<PointF> {
        if (points.size <= 3) return points
        val sorted = points.sortedWith(compareBy({ it.y }, { it.x }))
        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(p)
        }
        val upper = mutableListOf<PointF>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(p)
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun cross(o: PointF, a: PointF, b: PointF): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun perimeter(points: List<PointF>): Double {
        var p = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            p += hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }
        return p
    }

    private fun douglasPeucker(points: List<PointF>, epsilon: Double): List<PointF> {
        if (points.size < 3) return points
        var maxDist = 0.0
        var index = 0
        val end = points.lastIndex
        for (i in 1 until end) {
            val d = perpendicularDistance(points[i], points[0], points[end])
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, index + 1), epsilon)
            val right = douglasPeucker(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(points[0], points[end])
        }
    }

    private fun perpendicularDistance(p: PointF, lineStart: PointF, lineEnd: PointF): Double {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        if (dx == 0f && dy == 0f) return hypot((p.x - lineStart.x).toDouble(), (p.y - lineStart.y).toDouble())
        val num = abs((dy * p.x - dx * p.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x).toDouble())
        val den = hypot(dx.toDouble(), dy.toDouble())
        return num / den
    }

    private fun quadrilateralArea(points: List<PointF>): Double {
        if (points.size != 4) return 0.0
        var area = 0.0
        for (i in points.indices) {
            val j = (i + 1) % 4
            area += points[i].x * points[j].y
            area -= points[j].x * points[i].y
        }
        return abs(area) / 2.0
    }

    private fun isConvex(points: List<PointF>): Boolean {
        if (points.size < 4) return false
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val c = points[(i + 2) % points.size]
            val z = cross(a, b, c)
            val s = when {
                z > 0f -> 1
                z < 0f -> -1
                else -> 0
            }
            if (s == 0) continue
            if (sign == 0) sign = s
            else if (s != sign) return false
        }
        return true
    }

    private fun orderAndNormalize(quad: List<PointF>, w: Int, h: Int): DocumentCorners {
        val sorted = orderCorners(quad)
        return DocumentCorners(
            topLeft = DocPoint(sorted[0].x / w, sorted[0].y / h),
            topRight = DocPoint(sorted[1].x / w, sorted[1].y / h),
            bottomRight = DocPoint(sorted[2].x / w, sorted[2].y / h),
            bottomLeft = DocPoint(sorted[3].x / w, sorted[3].y / h)
        )
    }

    /** TL, TR, BR, BL */
    private fun orderCorners(points: List<PointF>): List<PointF> {
        val sum = points.map { it.x + it.y }
        val diff = points.map { it.x - it.y }
        val tl = points[sum.indexOf(sum.minOrNull()!!)]
        val br = points[sum.indexOf(sum.maxOrNull()!!)]
        val tr = points[diff.indexOf(diff.maxOrNull()!!)]
        val bl = points[diff.indexOf(diff.minOrNull()!!)]
        return listOf(tl, tr, br, bl)
    }

    private data class PointF(val x: Float, val y: Float)
}
