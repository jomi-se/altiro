package org.altiro.transcribepsy

import org.altiro.inference.NativeProgress

/** A separate, statically linked runtime, loaded only inside its recognition worker. */
class NativeTranscribePsy {
    external fun create(): Long

    external fun cancel(handle: Long)

    external fun release(handle: Long)

    fun transcribe(
        handle: Long,
        modelPath: String,
        wavPath: String,
        gpu: Boolean,
        progress: NativeProgress,
    ): String? =
        recognize(handle, modelPath, wavPath, gpu, progress)
            ?.decodeToString(throwOnInvalidSequence = true)

    private external fun recognize(
        handle: Long,
        modelPath: String,
        wavPath: String,
        gpu: Boolean,
        progress: NativeProgress,
    ): ByteArray?

    companion object {
        init {
            System.loadLibrary("altiro-transcribepsy")
        }
    }
}
