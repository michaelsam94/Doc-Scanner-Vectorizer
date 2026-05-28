package com.example.domain.model

enum class ImageFilter {
    ORIGINAL,
    GRAYSCALE,
    MONOCHROME,
    SHADOW_REMOVED,
    MAGIC_COLOR,
    ENHANCED,       // CLAHE adaptive contrast
    SHARP,          // Unsharp mask sharpening
    DENOISED,       // Bilateral filter denoising
    COLOR_CORRECT   // Auto white balance + gamma
}
