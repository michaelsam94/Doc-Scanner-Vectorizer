package com.example.di

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.repository.DocumentRepositoryImpl
import com.example.data.repository.ScannerRepositoryImpl
import com.example.data.repository.VectorizerRepositoryImpl
import com.example.domain.repository.DocumentRepository
import com.example.domain.repository.ScannerRepository
import com.example.domain.repository.VectorizerRepository

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
