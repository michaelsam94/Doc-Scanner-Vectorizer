package com.michael.docscannervectorizer.domain.model

data class ScannedDocument(
    val id: String,
    val imagePath: String,
    val note: String,
    val timestamp: Long,
    val filterName: String
)
