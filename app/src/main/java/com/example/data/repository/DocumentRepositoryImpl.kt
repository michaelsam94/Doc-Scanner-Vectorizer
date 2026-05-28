package com.example.data.repository

import com.example.data.local.DocumentDao
import com.example.data.local.ScannedDocumentEntity
import com.example.domain.model.DocPoint
import com.example.domain.model.DocumentCorners
import com.example.domain.model.ImageFilter
import com.example.domain.model.ScannedDocument
import com.example.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File

class DocumentRepositoryImpl(
    private val dao: DocumentDao
) : DocumentRepository {

    override fun getAllDocuments(): Flow<List<ScannedDocument>> {
        return dao.getAllDocuments().map { entities ->
            entities.map { mapToDomain(it) }
        }
    }

    override fun getDocumentById(id: String): Flow<ScannedDocument?> {
        return dao.getDocumentById(id).map { entity ->
            entity?.let { mapToDomain(it) }
        }
    }

    override suspend fun saveDocument(document: ScannedDocument) {
        dao.insertDocument(mapToEntity(document))
    }

    override suspend fun deleteDocument(id: String) {
        // Clean up real files first to free disk space
        dao.getDocumentById(id).map { entity ->
            entity?.let {
                safeDeleteFile(it.originalPath)
                safeDeleteFile(it.processedPath)
            }
        }
        dao.deleteDocumentById(id)
    }

    private fun safeDeleteFile(path: String?) {
        if (!path.isNullOrEmpty()) {
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Mappers
    private fun mapToDomain(entity: ScannedDocumentEntity): ScannedDocument {
        return ScannedDocument(
            id = entity.id,
            title = entity.title,
            originalPath = entity.originalPath,
            processedPath = entity.processedPath,
            corners = jsonToCorners(entity.cornersJson),
            filter = enumValueOfOrNull<ImageFilter>(entity.filterName) ?: ImageFilter.ORIGINAL,
            createdAt = entity.createdAt,
            note = entity.note
        )
    }

    private fun mapToEntity(doc: ScannedDocument): ScannedDocumentEntity {
        return ScannedDocumentEntity(
            id = doc.id,
            title = doc.title,
            originalPath = doc.originalPath,
            processedPath = doc.processedPath,
            cornersJson = cornersToJson(doc.corners),
            filterName = doc.filter.name,
            createdAt = doc.createdAt,
            note = doc.note
        )
    }

    private fun cornersToJson(corners: DocumentCorners): String {
        val obj = JSONObject()
        obj.put("tl_x", corners.topLeft.x)
        obj.put("tl_y", corners.topLeft.y)
        obj.put("tr_x", corners.topRight.x)
        obj.put("tr_y", corners.topRight.y)
        obj.put("br_x", corners.bottomRight.x)
        obj.put("br_y", corners.bottomRight.y)
        obj.put("bl_x", corners.bottomLeft.x)
        obj.put("bl_y", corners.bottomLeft.y)
        return obj.toString()
    }

    private fun jsonToCorners(jsonStr: String): DocumentCorners {
        return try {
            val obj = JSONObject(jsonStr)
            DocumentCorners(
                topLeft = DocPoint(obj.getDouble("tl_x").toFloat(), obj.getDouble("tl_y").toFloat()),
                topRight = DocPoint(obj.getDouble("tr_x").toFloat(), obj.getDouble("tr_y").toFloat()),
                bottomRight = DocPoint(obj.getDouble("br_x").toFloat(), obj.getDouble("br_y").toFloat()),
                bottomLeft = DocPoint(obj.getDouble("bl_x").toFloat(), obj.getDouble("bl_y").toFloat())
            )
        } catch (e: Exception) {
            DocumentCorners(
                topLeft = DocPoint(0f, 0f),
                topRight = DocPoint(1f, 0f),
                bottomRight = DocPoint(1f, 1f),
                bottomLeft = DocPoint(0f, 1f)
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOfOrNull(name: String): T? {
        return try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (e: Exception) {
            null
        }
    }
}
