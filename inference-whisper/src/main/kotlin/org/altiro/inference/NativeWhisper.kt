package org.altiro.inference

fun interface NativeProgress {
    fun onProgress(percent: Int)

    /**
     * 1 audio read, 2 cold load, 3 inference, 4 text assembly, 5 release, 6 failure before cleanup.
     */
    fun onPhase(phase: Int) {}

    /** Structured hardware properties / counters only, never native log text. */
    fun onRuntime(report: String) {}
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
        gpu: Boolean,
        progress: NativeProgress,
    ): String?

    companion object {
        init {
            System.loadLibrary("altiro-whisper")
        }
    }
}
