package com.michael.docscannervectorizer.data.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CamScanner-style document enhancement: illumination normalization,
 * adaptive contrast, paper whitening, and crisp monochrome output.
 */
internal object DocumentImageEnhancer {

    fun removeShadow(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = luminance((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
        }

        val background = boxBlurLarge(gray, w, h, radius = max(8, min(w, h) / 24))
        val out = IntArray(w * h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val bg = max(background[i], 18)
            val scale = (255f / bg).coerceIn(0.85f, 2.6f)
            val nr = ((r * scale).roundToInt() + 12).coerceIn(0, 255)
            val ng = ((g * scale).roundToInt() + 12).coerceIn(0, 255)
            val nb = ((b * scale).roundToInt() + 12).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        return pixelsToBitmap(out, w, h)
    }

    fun magicColor(bitmap: Bitmap): Bitmap {
        val shadowFree = removeShadow(bitmap)
        val w = shadowFree.width
        val h = shadowFree.height
        val pixels = IntArray(w * h)
        shadowFree.getPixels(pixels, 0, w, 0, 0, w, h)
        if (shadowFree !== bitmap) shadowFree.recycle()

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = luminance((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
        }
        val equalized = claheLite(gray, w, h, tileSize = 64, clipLimit = 2.2f)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val l = equalized[i]
            val gain = ((l - 128) * 0.35f + 128).coerceIn(40f, 245f) / 128f
            val nr = ((r * gain).roundToInt()).coerceIn(0, 255)
            val ng = ((g * gain).roundToInt()).coerceIn(0, 255)
            val nb = ((b * gain).roundToInt()).coerceIn(0, 255)
            val satBoost = 1.18f
            val avg = (nr + ng + nb) / 3f
            val sr = (avg + (nr - avg) * satBoost).roundToInt().coerceIn(0, 255)
            val sg = (avg + (ng - avg) * satBoost).roundToInt().coerceIn(0, 255)
            val sb = (avg + (nb - avg) * satBoost).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (whitenPaper(sr) shl 16) or (whitenPaper(sg) shl 8) or whitenPaper(sb)
        }

        return pixelsToBitmap(pixels, w, h)
    }

    fun monochrome(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = luminance((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
        }
        val window = max(15, min(w, h) / 32)
        for (i in gray.indices) {
            val t = sauvolaThreshold(gray, w, h, i, window)
            gray[i] = if (gray[i] < t) 0 else 255
        }
        for (i in pixels.indices) {
            val v = gray[i]
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return pixelsToBitmap(pixels, w, h)
    }

    fun autoEnhance(bitmap: Bitmap): Bitmap = magicColor(bitmap)

    fun grayscale(bitmap: Bitmap): Bitmap {
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        return applyColorMatrix(bitmap, matrix)
    }

    private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun whitenPaper(channel: Int): Int {
        val v = channel / 255f
        val curved = 1f - (1f - v).pow(1.35f)
        return (curved * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun luminance(r: Int, g: Int, b: Int): Int =
        (r * 77 + g * 150 + b * 29) shr 8

    private fun boxBlurLarge(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        var sum: Long
        for (y in 0 until h) {
            sum = 0
            for (x in 0 until w) {
                sum += src[y * w + x]
                if (x >= radius) sum -= src[y * w + (x - radius)]
                tmp[y * w + x] = (sum / min(x + 1, radius)).toInt()
            }
        }
        for (x in 0 until w) {
            sum = 0
            for (y in 0 until h) {
                sum += tmp[y * w + x]
                if (y >= radius) sum -= tmp[(y - radius) * w + x]
                out[y * w + x] = (sum / min(y + 1, radius)).toInt()
            }
        }
        return out
    }

    private fun claheLite(gray: IntArray, w: Int, h: Int, tileSize: Int, clipLimit: Float): IntArray {
        val out = IntArray(w * h)
        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = (h + tileSize - 1) / tileSize

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val x1 = min(w, x0 + tileSize)
                val y1 = min(h, y0 + tileSize)
                val hist = IntArray(256)
                var count = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        hist[gray[y * w + x]]++
                        count++
                    }
                }
                val maxBin = (count * (clipLimit / 256f)).roundToInt().coerceAtLeast(1)
                var excess = 0
                for (i in hist.indices) {
                    if (hist[i] > maxBin) {
                        excess += hist[i] - maxBin
                        hist[i] = maxBin
                    }
                }
                val redistribute = excess / 256
                for (i in hist.indices) hist[i] += redistribute

                val cdf = IntArray(256)
                var running = 0
                for (i in hist.indices) {
                    running += hist[i]
                    cdf[i] = running
                }
                val cdfMax = max(cdf[255], 1)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val v = gray[y * w + x]
                        out[y * w + x] = ((cdf[v] * 255f) / cdfMax).roundToInt()
                    }
                }
            }
        }
        return out
    }

    private fun sauvolaThreshold(gray: IntArray, w: Int, h: Int, index: Int, window: Int): Int {
        val x = index % w
        val y = index / w
        val half = window / 2
        val x0 = max(0, x - half)
        val x1 = min(w - 1, x + half)
        val y0 = max(0, y - half)
        val y1 = min(h - 1, y + half)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (py in y0..y1) {
            for (px in x0..x1) {
                val v = gray[py * w + px].toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        if (n == 0) return 128
        val mean = sum / n
        val variance = max(0.0, sumSq / n - mean * mean)
        val std = sqrt(variance)
        val k = 0.34
        val r = 128.0
        return (mean * (1.0 + k * ((std / r) - 1.0))).roundToInt().coerceIn(0, 255)
    }

    private fun pixelsToBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
