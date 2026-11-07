package com.runwise.app

import android.app.Application
import com.runwise.app.data.repository.RunwiseRepository

open class AerolapApplication : Application() {

    lateinit var repository: RunwiseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = RunwiseRepository.getInstance(this)
        // Background sync on startup
        repository.sync()
    }
}

class RunwiseApplication : AerolapApplication()
