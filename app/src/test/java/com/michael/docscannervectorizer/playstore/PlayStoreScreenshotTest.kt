package com.michael.docscannervectorizer.playstore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w360dp-h640dp-xxhdpi"
private const val TABLET = "w800dp-h1280dp-xhdpi"

@RunWith(AndroidJUnit4::class)
@Category(PlayStoreScreenshotTests::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayStoreScreenshotTest {

    @Test
    @Config(qualifiers = PHONE)
    fun phone_01_dashboard() {
        capturePlayStoreImage("phone/01_dashboard.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.Dashboard)
        }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phone_02_live_boundary_tracker() {
        capturePlayStoreImage("phone/02_live_boundary_tracker.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.Camera)
        }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phone_03_fidelity_filters() {
        capturePlayStoreImage("phone/03_fidelity_filters.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.Filters)
        }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun phone_04_vector_export() {
        capturePlayStoreImage("phone/04_vector_export.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.VectorExport)
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_01_dashboard() {
        capturePlayStoreImage("tablet/01_dashboard.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.Dashboard)
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_02_live_boundary_tracker() {
        capturePlayStoreImage("tablet/02_live_boundary_tracker.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.Camera)
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_03_fidelity_filters() {
        capturePlayStoreImage("tablet/03_fidelity_filters.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.Filters)
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tablet_04_vector_export() {
        capturePlayStoreImage("tablet/04_vector_export.png") {
            PlayStoreScreenshotFrame(PlayStoreScene.VectorExport)
        }
    }
}
