package org.altiro.app

import android.app.Application

class AltiroApplication : Application() {
    lateinit var controller: DictationController
        private set

    override fun onCreate() {
        super.onCreate()
        // The recognition process must not create a controller, verify every model,
        // or sweep audio still owned by the parent process.
        if (getProcessName() != packageName) return
        controller = DictationController(this)
        // The process never replays sessions. This dedicated cache holds only our audio.
        cacheDir
            .resolve("dictation")
            .apply { mkdirs() }
            .listFiles()
            ?.forEach { it.delete() }
    }
}
