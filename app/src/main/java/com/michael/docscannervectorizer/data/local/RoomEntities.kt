package com.michael.docscannervectorizer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_documents")
data class ScannedDocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalPath: String,
    val processedPath: String?,
    val cornersJson: String, // Holds raw corners coordinate string
    val filterName: String,
    val createdAt: Long,
    val note: String
)

@Entity(tableName = "vectorized_assets")
data class VectorizedAssetEntity(
    @PrimaryKey val id: String,
    val sourceDocumentId: String,
    val pngPath: String,
    val svgContent: String?,
    val createdAt: Long
)
