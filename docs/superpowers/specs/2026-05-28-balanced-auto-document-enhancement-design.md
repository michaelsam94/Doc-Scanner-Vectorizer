# Balanced Auto Document Enhancement Design

## Goal

Make the default post-crop document processing feel closer to CamScanner's balanced enhancement: cleaner paper, reduced shadows, stronger readable text, and preserved useful color for notes, stamps, diagrams, and highlights.

The main default remains `ImageFilter.MAGIC_COLOR`, but its behavior becomes a Balanced Auto enhancement pipeline instead of a simple whitening and sharpening pass.

## Current Flow

Captured and gallery images currently flow through:

1. `ScannerProcessor.detectDocumentCorners`
2. `ScannerProcessor.applyPerspectiveCorrection`
3. `ScannerProcessor.applyFilter(cropped, selectedFilter)`

`MainViewModel` defaults `selectedFilter` to `ImageFilter.MAGIC_COLOR`, and `ReviewScreen` exposes all filter variants in a horizontal strip.

## Chosen Approach

Use native Kotlin/Android bitmap processing. Do not add OpenCV or ML model dependencies for this pass.

This keeps the app offline, small, and aligned with the current codebase. Advanced dewarping and deep shadow/glare inpainting can be added later if the app needs book-page correction or heavy restoration.

## Balanced Auto Pipeline

`MAGIC_COLOR` should run these stages after perspective correction:

1. Local illumination flattening
   Estimate the page lighting with a low-resolution blurred background map, then divide source channels by that background to reduce shadows and uneven lighting.

2. Gray-world white balance
   Estimate average channel bias from the cropped page and gently correct color cast without destroying pen/highlight colors.

3. Robust auto levels
   Build luminance percentiles and stretch contrast between low and high percentile anchors. Avoid pure clipping so faint text and paper texture do not collapse.

4. Paper whitening with highlight protection
   Push likely paper pixels toward white while preserving darker ink and colored annotation pixels.

5. Gentle denoise
   Smooth small camera noise using a small edge-aware or conservative blur pass, avoiding text smearing.

6. Text-aware sharpening
   Apply unsharp or Laplacian sharpening more strongly near high-frequency text edges and less strongly in flat paper areas.

## Monochrome Improvement

`ImageFilter.MONOCHROME` should remain the strongest black-and-white text mode. It should use local adaptive thresholding and a small cleanup pass so receipts, printed forms, and OCR-style scans are crisp.

This mode is not the default because it discards color.

## UI Impact

No new screen is required for this pass.

The existing filter strip remains:

- `ORIGINAL`
- `GRAYSCALE`
- `MONOCHROME`
- `SHADOW_REMOVED`
- `MAGIC_COLOR`

The visible label can continue deriving from the enum name for now. If labels are polished later, `MAGIC_COLOR` can display as `Balanced Auto` without changing saved metadata.

## Performance

The pipeline must run on `Dispatchers.Default`, as it does today. It should avoid per-pixel object allocation and use `IntArray`/primitive arrays.

Target behavior:

- Smooth enough for a typical phone after capture.
- No live camera frame enhancement.
- No large native dependency added.

## Testing

Add JVM unit tests around pure pixel-processing helpers where possible. Tests should use small synthetic bitmaps to verify:

- Shadowed paper becomes more uniform.
- Dark text remains dark or becomes darker.
- Colored marks remain visibly colored in `MAGIC_COLOR`.
- `MONOCHROME` produces clear black/white separation.

Because Android `Bitmap` is involved, tests may need Robolectric or instrumentation if pure JVM tests cannot instantiate bitmap operations reliably in this project.

## Out Of Scope

- OpenCV dependency integration.
- ML-based glare removal or inpainting.
- Book-page dewarping.
- OCR.
- PDF export changes.
- Redesigning the Review screen.

## Acceptance Criteria

- `MAGIC_COLOR` remains the default selected filter.
- New captures and gallery imports use the improved Balanced Auto enhancement automatically.
- Existing saved scans continue to load without metadata migration.
- The app builds successfully with `:app:assembleDebug`.
- Filter switching still works from the review screen.
