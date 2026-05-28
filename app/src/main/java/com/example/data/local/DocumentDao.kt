package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM scanned_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<ScannedDocumentEntity>>

    @Query("SELECT * FROM scanned_documents WHERE id = :id LIMIT 1")
    fun getDocumentById(id: String): Flow<ScannedDocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: ScannedDocumentEntity)

    @Query("DELETE FROM scanned_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    // Vectorized Assets
    @Query("SELECT * FROM vectorized_assets WHERE sourceDocumentId = :documentId ORDER BY createdAt DESC")
    fun getAssetsForDocument(documentId: String): Flow<List<VectorizedAssetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVector(asset: VectorizedAssetEntity)

    @Query("DELETE FROM vectorized_assets WHERE id = :id")
    suspend fun deleteAssetById(id: String)
}
