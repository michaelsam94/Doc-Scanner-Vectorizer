package com.michael.docscannervectorizer.domain.repository

import com.michael.docscannervectorizer.domain.model.ScannedDocument
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<ScannedDocument>>
    fun getDocumentById(id: String): Flow<ScannedDocument?>
    suspend fun saveDocument(document: ScannedDocument)
    suspend fun deleteDocument(id: String)
}
