package com.michael.docscannervectorizer.domain.repository

import android.graphics.Bitmap
import com.michael.docscannervectorizer.domain.model.VectorizedAsset
import kotlinx.coroutines.flow.Flow

interface VectorizerRepository {
    suspend fun vectorize(bitmap: Bitmap, sourceDocumentId: String): VectorizedAsset
    fun getAssetsForDocument(documentId: String): Flow<List<VectorizedAsset>>
    suspend fun deleteAsset(id: String)
}
