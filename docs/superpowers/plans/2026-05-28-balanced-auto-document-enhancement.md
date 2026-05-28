# Balanced Auto Document Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the default `MAGIC_COLOR` filter into a native CamScanner-like Balanced Auto enhancement and improve `MONOCHROME` for crisp black-and-white text.

**Architecture:** Keep the existing capture/gallery/review flow unchanged. Add focused, primitive-array image-processing helpers inside `ScannerProcessor`, route `MAGIC_COLOR` to the new Balanced Auto pipeline, and route `MONOCHROME` to adaptive thresholding plus cleanup. Add Robolectric JVM tests for synthetic bitmap behavior.

**Tech Stack:** Kotlin, Android `Bitmap`, Gradle Android plugin, JUnit 4, Robolectric.

---

## File Structure

- Modify `gradle/libs.versions.toml`: add a Robolectric version and library alias.
- Modify `app/build.gradle.kts`: enable unit-test Android resources and add `testImplementation(libs.robolectric)`.
- Create `app/src/test/java/com/example/domain/ScannerProcessorTest.kt`: synthetic bitmap tests for Balanced Auto and Monochrome behavior.
- Modify `app/src/main/java/com/example/domain/ScannerProcessor.kt`: add the Balanced Auto pipeline, improve monochrome cleanup, and keep public API unchanged.

## Task 1: Add Image-Processing Test Harness

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/example/domain/ScannerProcessorTest.kt`

- [ ] **Step 1: Add failing tests for the desired filter behavior**

Create `app/src/test/java/com/example/domain/ScannerProcessorTest.kt`:

```kotlin
package com.example.domain

import android.graphics.Bitmap
import android.graphics.Color
import com.example.domain.model.ImageFilter
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

        val output = ScannerProcessor.applyFilter(input, ImageFilter.MAGIC_COLOR)

        val leftPaper = luma(output.getPixel(1, 1))
        val rightPaper = luma(output.getPixel(14, 1))
        val text = luma(output.getPixel(8, 3))
        val blueMark = output.getPixel(5, 5)

        assertTrue("paper should be bright after balanced auto", leftPaper > 215 && rightPaper > 215)
        assertTrue("shadow difference should be reduced", kotlin.math.abs(leftPaper - rightPaper) < 24)
        assertTrue("text should remain darker than paper", text < leftPaper - 90)
        assertTrue("colored marks should remain blue", Color.blue(blueMark) > Color.red(blueMark) + 35)
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

    private fun luma(pixel: Int): Int {
        return (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f).toInt()
    }
}
```

- [ ] **Step 2: Add Robolectric dependency and unit test resource setting**

In `gradle/libs.versions.toml`, add:

```toml
[versions]
robolectric = "4.12.2"

[libraries]
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
```

Keep the existing version and library entries. Insert the new `robolectric` lines into the existing `[versions]` and `[libraries]` sections.

In `app/build.gradle.kts`, add this inside the existing `android { ... }` block:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

Add this inside `dependencies { ... }` next to the existing `testImplementation(libs.junit)`:

```kotlin
    testImplementation(libs.robolectric)
```

- [ ] **Step 3: Run the tests and verify they fail for behavior**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.domain.ScannerProcessorTest
```

Expected: the tests compile and at least one assertion fails because the existing `MAGIC_COLOR` and/or `MONOCHROME` implementation is not strong enough for the new behavior.

## Task 2: Implement Balanced Auto for `MAGIC_COLOR`

**Files:**
- Modify: `app/src/main/java/com/example/domain/ScannerProcessor.kt`
- Test: `app/src/test/java/com/example/domain/ScannerProcessorTest.kt`

- [ ] **Step 1: Route `MAGIC_COLOR` to a new Balanced Auto function**

In `ScannerProcessor.applyFilter`, change only the `MAGIC_COLOR` branch:

```kotlin
ImageFilter.MAGIC_COLOR -> applyBalancedAutoEnhancement(bitmap)
```

- [ ] **Step 2: Add the Balanced Auto implementation**

Add these helpers near the existing filter helpers in `ScannerProcessor.kt`:

```kotlin
private fun applyBalancedAutoEnhancement(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val size = w * h

    val bg = getIlluminationMap(bitmap)
    val src = IntArray(size)
    val bgPixels = IntArray(size)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)
    bg.getPixels(bgPixels, 0, w, 0, 0, w, h)
    bg.recycle()

    val flattened = IntArray(size)
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    for (i in 0 until size) {
        val s = src[i]
        val b = bgPixels[i]

        val rS = (s shr 16) and 0xff
        val gS = (s shr 8) and 0xff
        val bS = s and 0xff
        val rB = ((b shr 16) and 0xff).coerceAtLeast(48)
        val gB = ((b shr 8) and 0xff).coerceAtLeast(48)
        val bB = (b and 0xff).coerceAtLeast(48)

        val r = (rS * 232 / rB + 18).coerceIn(0, 255)
        val g = (gS * 232 / gB + 18).coerceIn(0, 255)
        val blue = (bS * 232 / bB + 18).coerceIn(0, 255)

        flattened[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or blue
        sumR += r
        sumG += g
        sumB += blue
    }

    val avgR = (sumR / size).toFloat().coerceAtLeast(1f)
    val avgG = (sumG / size).toFloat().coerceAtLeast(1f)
    val avgB = (sumB / size).toFloat().coerceAtLeast(1f)
    val neutral = (avgR + avgG + avgB) / 3f
    val gainR = (neutral / avgR).coerceIn(0.86f, 1.16f)
    val gainG = (neutral / avgG).coerceIn(0.86f, 1.16f)
    val gainB = (neutral / avgB).coerceIn(0.86f, 1.16f)

    val balanced = IntArray(size)
    val histogram = IntArray(256)
    for (i in 0 until size) {
        val p = flattened[i]
        val r = (((p shr 16) and 0xff) * gainR).toInt().coerceIn(0, 255)
        val g = (((p shr 8) and 0xff) * gainG).toInt().coerceIn(0, 255)
        val b = ((p and 0xff) * gainB).toInt().coerceIn(0, 255)
        val y = luminance(r, g, b)
        histogram[y]++
        balanced[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    val low = percentile(histogram, size, 0.03f).coerceAtMost(115)
    val high = percentile(histogram, size, 0.92f).coerceAtLeast(low + 42)
    val leveled = IntArray(size)
    for (i in 0 until size) {
        val p = balanced[i]
        var r = stretchAutoLevel((p shr 16) and 0xff, low, high)
        var g = stretchAutoLevel((p shr 8) and 0xff, low, high)
        var b = stretchAutoLevel(p and 0xff, low, high)

        val y = luminance(r, g, b)
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC

        if (y > 150 && saturation < 0.22f) {
            val lift = ((255 - y) * 0.74f).toInt()
            r = (r + lift).coerceIn(0, 255)
            g = (g + lift).coerceIn(0, 255)
            b = (b + lift).coerceIn(0, 255)
        } else if (y < 118) {
            r = (r * 0.72f).toInt().coerceIn(0, 255)
            g = (g * 0.72f).toInt().coerceIn(0, 255)
            b = (b * 0.72f).toInt().coerceIn(0, 255)
        } else if (saturation >= 0.22f) {
            val boost = 1.18f
            r = (y + (r - y) * boost).toInt().coerceIn(0, 255)
            g = (y + (g - y) * boost).toInt().coerceIn(0, 255)
            b = (y + (b - y) * boost).toInt().coerceIn(0, 255)
        }

        leveled[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    val denoised = applyConservativeDenoise(leveled, w, h)
    val sharpened = applyTextAwareSharpening(denoised, w, h, strength = 0.48f)
    val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    dest.setPixels(sharpened, 0, w, 0, 0, w, h)
    return dest
}

private fun luminance(r: Int, g: Int, b: Int): Int {
    return (r * 0.299f + g * 0.587f + b * 0.114f).toInt().coerceIn(0, 255)
}

private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
    val target = (total * fraction).toInt().coerceIn(0, total - 1)
    var count = 0
    for (i in histogram.indices) {
        count += histogram[i]
        if (count > target) return i
    }
    return 255
}

private fun stretchAutoLevel(value: Int, low: Int, high: Int): Int {
    if (value <= low) return (value * 0.42f).toInt().coerceIn(0, 255)
    if (value >= high) return 255
    val t = (value - low).toFloat() / (high - low).toFloat()
    val curved = Math.pow(t.toDouble(), 0.78).toFloat()
    return (curved * 255f).toInt().coerceIn(0, 255)
}
```

- [ ] **Step 3: Add denoise and text-aware sharpening helpers**

Add these helpers below the Balanced Auto helpers:

```kotlin
private fun applyConservativeDenoise(pixels: IntArray, w: Int, h: Int): IntArray {
    val out = pixels.copyOf()
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            val p = pixels[idx]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val centerY = luminance(r, g, b)

            var sumR = r * 2
            var sumG = g * 2
            var sumB = b * 2
            var weight = 2

            val neighbors = intArrayOf(idx - 1, idx + 1, idx - w, idx + w)
            for (nIdx in neighbors) {
                val n = pixels[nIdx]
                val nr = (n shr 16) and 0xff
                val ng = (n shr 8) and 0xff
                val nb = n and 0xff
                val ny = luminance(nr, ng, nb)
                if (kotlin.math.abs(ny - centerY) < 22) {
                    sumR += nr
                    sumG += ng
                    sumB += nb
                    weight++
                }
            }

            out[idx] = 0xFF000000.toInt() or
                ((sumR / weight).coerceIn(0, 255) shl 16) or
                ((sumG / weight).coerceIn(0, 255) shl 8) or
                (sumB / weight).coerceIn(0, 255)
        }
    }
    return out
}

private fun applyTextAwareSharpening(pixels: IntArray, w: Int, h: Int, strength: Float): IntArray {
    val out = pixels.copyOf()
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            val p = pixels[idx]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff

            val l = pixels[idx - 1]
            val rr = pixels[idx + 1]
            val t = pixels[idx - w]
            val bb = pixels[idx + w]

            val edge = kotlin.math.abs(luminance(r, g, b) - luminance((l shr 16) and 0xff, (l shr 8) and 0xff, l and 0xff)) +
                kotlin.math.abs(luminance(r, g, b) - luminance((rr shr 16) and 0xff, (rr shr 8) and 0xff, rr and 0xff)) +
                kotlin.math.abs(luminance(r, g, b) - luminance((t shr 16) and 0xff, (t shr 8) and 0xff, t and 0xff)) +
                kotlin.math.abs(luminance(r, g, b) - luminance((bb shr 16) and 0xff, (bb shr 8) and 0xff, bb and 0xff))

            if (edge < 34) continue

            val lapR = 4 * r - (((l shr 16) and 0xff) + ((rr shr 16) and 0xff) + ((t shr 16) and 0xff) + ((bb shr 16) and 0xff))
            val lapG = 4 * g - (((l shr 8) and 0xff) + ((rr shr 8) and 0xff) + ((t shr 8) and 0xff) + ((bb shr 8) and 0xff))
            val lapB = 4 * b - ((l and 0xff) + (rr and 0xff) + (t and 0xff) + (bb and 0xff))
            val edgeStrength = (strength * (edge / 180f).coerceIn(0.25f, 1f))

            val sr = (r + lapR * edgeStrength).toInt().coerceIn(0, 255)
            val sg = (g + lapG * edgeStrength).toInt().coerceIn(0, 255)
            val sb = (b + lapB * edgeStrength).toInt().coerceIn(0, 255)
            out[idx] = 0xFF000000.toInt() or (sr shl 16) or (sg shl 8) or sb
        }
    }
    return out
}
```

- [ ] **Step 4: Run the targeted tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.domain.ScannerProcessorTest
```

Expected: the `magicColor_flattensShadowedPaperWhileKeepingTextDarkAndColorVisible` test passes. The `monochrome_separatesTextFromUnevenPaperIntoBlackAndWhite` test may still fail until Task 3 is complete.

## Task 3: Improve `MONOCHROME` Adaptive Threshold Cleanup

**Files:**
- Modify: `app/src/main/java/com/example/domain/ScannerProcessor.kt`
- Test: `app/src/test/java/com/example/domain/ScannerProcessorTest.kt`

- [ ] **Step 1: Strengthen `applyLocalAdaptiveThreshold`**

Replace the body of `applyLocalAdaptiveThreshold` with:

```kotlin
private fun applyLocalAdaptiveThreshold(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val size = w * h

    val bgIllum = getIlluminationMap(bitmap)
    val srcPixels = IntArray(size)
    val bgPixels = IntArray(size)
    val binary = IntArray(size)

    bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)
    bgIllum.getPixels(bgPixels, 0, w, 0, 0, w, h)
    bgIllum.recycle()

    for (i in 0 until size) {
        val src = srcPixels[i]
        val bg = bgPixels[i]

        val sGray = luminance((src shr 16) and 0xff, (src shr 8) and 0xff, src and 0xff)
        val bGray = luminance((bg shr 16) and 0xff, (bg shr 8) and 0xff, bg and 0xff)
        val thresholdBias = (bGray * 0.085f).toInt().coerceIn(13, 24)

        binary[i] = if (sGray < bGray - thresholdBias) Color.BLACK else Color.WHITE
    }

    val cleaned = cleanupBinaryNoise(binary, w, h)
    val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    dest.setPixels(cleaned, 0, w, 0, 0, w, h)
    return dest
}
```

- [ ] **Step 2: Add binary cleanup helper**

Add below the threshold function:

```kotlin
private fun cleanupBinaryNoise(pixels: IntArray, w: Int, h: Int): IntArray {
    val out = pixels.copyOf()
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            var blackNeighbors = 0
            for (yy in -1..1) {
                for (xx in -1..1) {
                    if (xx == 0 && yy == 0) continue
                    if (pixels[(y + yy) * w + x + xx] == Color.BLACK) {
                        blackNeighbors++
                    }
                }
            }

            if (pixels[idx] == Color.BLACK && blackNeighbors <= 1) {
                out[idx] = Color.WHITE
            } else if (pixels[idx] == Color.WHITE && blackNeighbors >= 7) {
                out[idx] = Color.BLACK
            }
        }
    }
    return out
}
```

`ScannerProcessor.kt` already imports `android.graphics.ColorMatrix`; add `import android.graphics.Color` at the top if it is not present.

- [ ] **Step 3: Run targeted tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.domain.ScannerProcessorTest
```

Expected: both tests pass.

## Task 4: Full Verification and Final Build

**Files:**
- Verify: `app/src/main/java/com/example/domain/ScannerProcessor.kt`
- Verify: `app/src/test/java/com/example/domain/ScannerProcessorTest.kt`

- [ ] **Step 1: Run all unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build the debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, with the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Inspect only relevant diff**

Run:

```bash
git diff -- app/src/main/java/com/example/domain/ScannerProcessor.kt app/src/test/java/com/example/domain/ScannerProcessorTest.kt app/build.gradle.kts gradle/libs.versions.toml
```

Expected: only the test harness, Balanced Auto pipeline, and monochrome cleanup are changed. Existing launcher-fix changes may still be present in the worktree but are unrelated to this plan.

## Self-Review

- Spec coverage: Balanced Auto native pipeline is Task 2; Monochrome improvement is Task 3; tests are Tasks 1, 3, and 4; no OpenCV/ML/UI redesign is introduced.
- Placeholder scan: no `TBD`, `TODO`, or vague "add tests" steps remain.
- Type consistency: public API remains `ScannerProcessor.applyFilter(bitmap, filter)`, `ImageFilter.MAGIC_COLOR`, and `ImageFilter.MONOCHROME`.
