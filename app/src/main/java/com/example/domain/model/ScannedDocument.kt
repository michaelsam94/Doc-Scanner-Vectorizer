package com.example.domain.model

data class DocPoint(val x: Float, val y: Float)

data class DocumentCorners(
    val topLeft: DocPoint,
    val topRight: DocPoint,
    val bottomRight: DocPoint,
    val bottomLeft: DocPoint
)

enum class ImageFilter { 
    ORIGINAL, 
    GRAYSCALE, 
    MONOCHROME, 
    SHADOW_REMOVED, 
    MAGIC_COLOR 
}

data class ScannedDocument(
    val id: String,
    val title: String,
    val originalPath: String,
    val processedPath: String?,
    val corners: DocumentCorners,
    val filter: ImageFilter,
    val createdAt: Long,
    val note: String = ""
)

data class VectorizedAsset(
    val id: String,
    val sourceDocumentId: String,
    val pngPath: String,
    val svgContent: String?, // direct SVG xml content is super powerful for sharing/rendering!
    val createdAt: Long
)
