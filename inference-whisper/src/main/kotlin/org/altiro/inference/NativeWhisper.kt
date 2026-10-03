package org.altiro.inference

fun interface NativeProgress {
    fun onProgress(percent: Int)
}

/** Internal operations are serialized by the owner; cancel is thread safe. */
class NativeWhisper {
    external fun create(): Long

    external fun cancel(operation: Long)

    external fun release(operation: Long)

    external fun transcribe(
        operation: Long,
        modelPath: String,
        wavPath: String,
        language: String,
        progress: NativeProgress,
    ): String?

    companion object {
        init {
            System.loadLibrary("altiro-whisper")
        }
    }
}
