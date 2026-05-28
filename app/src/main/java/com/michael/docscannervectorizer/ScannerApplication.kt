package com.michael.docscannervectorizer

import android.app.Application
import com.michael.docscannervectorizer.di.AppContainer

class ScannerApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
