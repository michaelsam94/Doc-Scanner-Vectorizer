package com.michael.docscannervectorizer.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.michael.docscannervectorizer.feature.adjust.AdjustViewModel
import com.michael.docscannervectorizer.feature.gallery.GalleryViewModel
import com.michael.docscannervectorizer.feature.scan.ScanViewModel
import com.michael.docscannervectorizer.feature.vectorize.VectorizeViewModel

class ViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(GalleryViewModel::class.java) -> {
                GalleryViewModel(appContainer.documentRepository) as T
            }
            modelClass.isAssignableFrom(ScanViewModel::class.java) -> {
                ScanViewModel(appContainer.scannerRepository, appContainer.documentRepository) as T
            }
            modelClass.isAssignableFrom(AdjustViewModel::class.java) -> {
                AdjustViewModel(appContainer.documentRepository, appContainer.scannerRepository) as T
            }
            modelClass.isAssignableFrom(VectorizeViewModel::class.java) -> {
                VectorizeViewModel(appContainer.documentRepository, appContainer.vectorizerRepository) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
