package org.altiro.app

import android.app.Application

class AltiroApplication : Application() {
    lateinit var controller: DictationController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = DictationController(this)
        // The process never replays sessions. This dedicated cache holds only our audio.
        cacheDir
            .resolve("dictation")
            .apply { mkdirs() }
            .listFiles()
            ?.forEach { it.delete() }
    }
}
