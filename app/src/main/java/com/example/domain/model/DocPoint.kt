package com.example.domain.model

data class DocPoint(val x: Float, val y: Float)

data class DocumentCorners(
    val topLeft: DocPoint,
    val topRight: DocPoint,
    val bottomRight: DocPoint,
    val bottomLeft: DocPoint
) {
    fun toFloatArray(width: Float, height: Float): FloatArray {
        return floatArrayOf(
            topLeft.x * width, topLeft.y * height,
            topRight.x * width, topRight.y * height,
            bottomRight.x * width, bottomRight.y * height,
            bottomLeft.x * width, bottomLeft.y * height
        )
    }
}
