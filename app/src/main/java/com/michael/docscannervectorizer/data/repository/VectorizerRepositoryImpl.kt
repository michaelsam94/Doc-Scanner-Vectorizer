package com.michael.docscannervectorizer.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.michael.docscannervectorizer.data.local.DocumentDao
import com.michael.docscannervectorizer.data.local.VectorizedAssetEntity
import com.michael.docscannervectorizer.domain.model.VectorizedAsset
import com.michael.docscannervectorizer.domain.repository.VectorizerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class VectorizerRepositoryImpl(
    private val context: Context,
    private val dao: DocumentDao
) : VectorizerRepository {

    override suspend fun vectorize(bitmap: Bitmap, sourceDocumentId: String): VectorizedAsset = withContext(Dispatchers.Default) {
        val assetId = UUID.randomUUID().toString()

        // 1. Convert paper scan to clean transparent channel ink bitmap
        val transparentBitmap = extractTransparentPng(bitmap)

        // 2. Perform calligraphic SVG scanline tracing
        val svgXml = traceBitmapToSvg(bitmap)

        // 3. Save transparent PNG to local storage (app files directory) to save space
        val vectorsDir = File(context.filesDir, "vectors").apply { mkdirs() }
        val pngFile = File(vectorsDir, "$assetId.png")
        FileOutputStream(pngFile).use { out ->
            transparentBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val assetEntity = VectorizedAssetEntity(
            id = assetId,
            sourceDocumentId = sourceDocumentId,
            pngPath = pngFile.absolutePath,
            svgContent = svgXml,
            createdAt = System.currentTimeMillis()
        )

        // 4. Save to Room DB
        dao.insertVector(assetEntity)

        VectorizedAsset(
            id = assetId,
            sourceDocumentId = sourceDocumentId,
            pngPath = pngFile.absolutePath,
            svgContent = svgXml,
            createdAt = assetEntity.createdAt
        )
    }

    override fun getAssetsForDocument(documentId: String): Flow<List<VectorizedAsset>> {
        return dao.getAssetsForDocument(documentId).map { entities ->
            entities.map { entity ->
                VectorizedAsset(
                    id = entity.id,
                    sourceDocumentId = entity.sourceDocumentId,
                    pngPath = entity.pngPath,
                    svgContent = entity.svgContent,
                    createdAt = entity.createdAt
                )
            }
        }
    }

    override suspend fun deleteAsset(id: String) {
        dao.deleteAssetById(id)
    }

    private fun extractTransparentPng(bitmap: Bitmap, threshold: Int = 135): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val grayLuminance = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()

            if (grayLuminance > threshold) {
                // Background paper -> absolute transparency
                pixels[i] = 0x00000000
            } else {
                // Ink stroke -> make it rich dark and anti-aliased smooth
                val diff = (threshold - grayLuminance).coerceAtLeast(0)
                val opFactor = diff.toFloat() / threshold.toFloat()
                val alpha = (opFactor * 255f).coerceIn(0f, 255f).toInt()
                // Keeping original hue but setting custom alpha channels for extreme feathering
                pixels[i] = (alpha shl 24) or (p and 0x00ffffff)
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun traceBitmapToSvg(bitmap: Bitmap): String {
        // Downscale to make SVG extremely compact yet accurate
        val maxDim = 180
        val scale = maxDim.toFloat() / Math.max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(10)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(10)
        
        val scaledBmp = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val pixels = IntArray(scaledW * scaledH)
        scaledBmp.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)

        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $scaledW $scaledH\" width=\"100%\" height=\"100%\">\n")
        sb.append("  <g fill=\"currentColor\">\n") // Allows dynamic Theme coloring!

        val threshold = 135
        for (y in 0 until scaledH) {
            var startX = -1
            for (x in 0 until scaledW) {
                val p = pixels[y * scaledW + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

                if (lum < threshold) {
                    if (startX == -1) {
                        startX = x
                    }
                } else {
                    if (startX != -1) {
                        val w = x - startX
                        sb.append("    <rect x=\"$startX\" y=\"$y\" width=\"$w\" height=\"1.15\" rx=\"0.35\" />\n")
                        startX = -1
                    }
                }
            }
            if (startX != -1) {
                val w = scaledW - startX
                sb.append("    <rect x=\"$startX\" y=\"$y\" width=\"$w\" height=\"1.15\" rx=\"0.35\" />\n")
            }
        }
        sb.append("  </g>\n")
        sb.append("</svg>")
        return sb.toString()
    }
}
