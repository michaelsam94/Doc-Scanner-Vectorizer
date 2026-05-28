package com.michael.docscannervectorizer.playstore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Category(PlayStoreScreenshotTests::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1024dp-h500dp-mdpi")
class PlayStoreFeatureGraphicTest {

    @Test
    fun feature_graphic() {
        capturePlayStoreImage("feature-graphic.png") {
            FeatureGraphicContent()
        }
    }
}
