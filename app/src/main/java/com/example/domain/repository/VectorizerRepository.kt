package com.example.domain.repository

import android.graphics.Bitmap
import com.example.domain.model.VectorizedAsset
import kotlinx.coroutines.flow.Flow

interface VectorizerRepository {
    suspend fun vectorize(bitmap: Bitmap, sourceDocumentId: String): VectorizedAsset
    fun getAssetsForDocument(documentId: String): Flow<List<VectorizedAsset>>
    suspend fun deleteAsset(id: String)
}
