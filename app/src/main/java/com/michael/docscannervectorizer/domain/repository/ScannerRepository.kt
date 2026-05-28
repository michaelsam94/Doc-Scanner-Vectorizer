package com.michael.docscannervectorizer.domain.repository

import android.graphics.Bitmap
import com.michael.docscannervectorizer.domain.model.DocumentCorners
import com.michael.docscannervectorizer.domain.model.ImageFilter

interface ScannerRepository {
    suspend fun detectEdges(bitmap: Bitmap): DocumentCorners
    suspend fun applyPerspectiveCorrection(bitmap: Bitmap, corners: DocumentCorners): Bitmap
    suspend fun applyFilter(bitmap: Bitmap, filter: ImageFilter): Bitmap
    suspend fun removeShadow(bitmap: Bitmap): Bitmap
}
