package com.michael.docscannervectorizer.domain

import android.graphics.Bitmap
import android.graphics.Color
import com.michael.docscannervectorizer.domain.model.ImageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScannerProcessorTest {

    @Test
    fun magicColor_flattensShadowedPaperWhileKeepingTextDarkAndColorVisible() {
        val input = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val shade = 170 + (x * 55 / (input.width - 1))
                input.setPixel(x, y, Color.rgb(shade, shade - 6, shade - 14))
            }
        }
        for (x in 3..12) {
            input.setPixel(x, 3, Color.rgb(38, 38, 38))
        }
        input.setPixel(5, 5, Color.rgb(35, 85, 210))
        input.setPixel(14, 6, Color.rgb(80, 80, 80))

        val output = ScannerProcessor.applyFilter(input, ImageFilter.MAGIC_COLOR)

        val leftPaper = luma(output.getPixel(1, 1))
        val rightPaper = luma(output.getPixel(14, 1))
        val text = luma(output.getPixel(8, 3))
        val blueMark = output.getPixel(5, 5)
        val isolatedNoise = luma(output.getPixel(14, 6))

        assertTrue("paper should be bright after balanced auto", leftPaper > 215 && rightPaper > 215)
        assertTrue("shadow difference should be reduced", kotlin.math.abs(leftPaper - rightPaper) < 24)
        assertTrue("text should remain darker than paper", text < leftPaper - 90)
        assertTrue("colored marks should remain blue", Color.blue(blueMark) > Color.red(blueMark) + 35)
        assertTrue("isolated camera specks should be softened into paper", isolatedNoise > 180)
    }

    @Test
    fun monochrome_separatesTextFromUnevenPaperIntoBlackAndWhite() {
        val input = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val shade = 182 + (x * 46 / (input.width - 1))
                input.setPixel(x, y, Color.rgb(shade, shade, shade))
            }
        }
        for (x in 2..9) {
            input.setPixel(x, 4, Color.rgb(54, 54, 54))
        }

        val output = ScannerProcessor.applyFilter(input, ImageFilter.MONOCHROME)

        assertEquals("paper becomes white", Color.WHITE, output.getPixel(1, 1))
        assertEquals("paper becomes white", Color.WHITE, output.getPixel(10, 1))
        assertEquals("text becomes black", Color.BLACK, output.getPixel(6, 4))
    }

    @Test
    fun vectorizeToSvg_convertsDarkRunsIntoSvgRectangles() {
        val input = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.WHITE)
        input.setPixel(1, 1, Color.BLACK)
        input.setPixel(2, 1, Color.BLACK)
        input.setPixel(3, 1, Color.BLACK)
        input.setPixel(4, 2, Color.rgb(180, 180, 180))

        val svg = ScannerProcessor.vectorizeToSvg(input)

        assertTrue(svg.contains("""<svg xmlns="http://www.w3.org/2000/svg""""))
        assertTrue(svg.contains("""viewBox="0 0 5 4""""))
        assertTrue(svg.contains("""<rect x="1" y="1" width="3" height="1" />"""))
        assertTrue("light gray paper noise should not be vectorized", !svg.contains("""x="4" y="2""""))
    }

    private fun luma(pixel: Int): Int {
        return (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f).toInt()
    }
}
