package com.michael.docscannervectorizer

import android.graphics.Bitmap
import android.graphics.Color
import com.michael.docscannervectorizer.data.scan.DocumentEdgeDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentEdgeDetectorTest {

    @Test
    fun detect_findsWhiteDocumentOnDarkBackground() {
        val w = 400
        val h = 600
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 30, 35))
        for (y in 120 until 480) {
            for (x in 80 until 320) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }

        val corners = DocumentEdgeDetector.detect(bitmap)

        assertTrue(corners.topLeft.x < 0.35f)
        assertTrue(corners.topRight.x > 0.65f)
        assertTrue(corners.bottomLeft.y > 0.55f)
        assertTrue(corners.topLeft.y < 0.45f)
    }
}
