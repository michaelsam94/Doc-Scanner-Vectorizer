package com.michael.docscannervectorizer.di

import android.content.Context
import com.michael.docscannervectorizer.data.local.AppDatabase
import com.michael.docscannervectorizer.data.repository.DocumentRepositoryImpl
import com.michael.docscannervectorizer.data.repository.ScannerRepositoryImpl
import com.michael.docscannervectorizer.data.repository.VectorizerRepositoryImpl
import com.michael.docscannervectorizer.domain.repository.DocumentRepository
import com.michael.docscannervectorizer.domain.repository.ScannerRepository
import com.michael.docscannervectorizer.domain.repository.VectorizerRepository

class AppContainer(private val context: Context) {
    
    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(context)
    }

    val documentRepository: DocumentRepository by lazy {
        DocumentRepositoryImpl(database.documentDao())
    }

    val scannerRepository: ScannerRepository by lazy {
        ScannerRepositoryImpl()
    }

    val vectorizerRepository: VectorizerRepository by lazy {
        VectorizerRepositoryImpl(context, database.documentDao())
    }
}
