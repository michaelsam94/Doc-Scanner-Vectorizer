package com.example.domain.repository

import com.example.domain.model.ScannedDocument
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<ScannedDocument>>
    fun getDocumentById(id: String): Flow<ScannedDocument?>
    suspend fun saveDocument(document: ScannedDocument)
    suspend fun deleteDocument(id: String)
}
