package org.altiro.core

import java.io.File
import java.util.concurrent.CancellationException

/** Immutable model inputs prevent settings changes from altering an active recording. */
data class RecognitionInput(
    val spec: ModelSpec,
    val file: File,
)

data class TimedTranscript(
    val modelId: String,
    val text: String,
    val elapsedMillis: Long,
)

object RecognitionBatch {
    /** One owned recording, one model at a time, and no retained audio after any outcome. */
    fun run(
        audio: File,
        inputs: List<RecognitionInput>,
        cancelled: () -> Boolean,
        transcribe: (RecognitionInput) -> String?,
        clockNanos: () -> Long = System::nanoTime,
    ): List<TimedTranscript> {
        try {
            require(inputs.size in 1..4 && inputs.map { it.spec.id }.distinct().size == inputs.size)
            val results =
                inputs.map { input ->
                    if (cancelled()) throw CancellationException()
                    val started = clockNanos()
                    check(VerifiedModel.matches(input.file, input.spec, cancelled)) { "Model verification failed" }
                    val text = transcribe(input)
                    if (cancelled()) throw CancellationException()
                    TimedTranscript(input.spec.id, text.orEmpty().trim(), (clockNanos() - started) / 1_000_000)
                }
            if (cancelled()) throw CancellationException()
            return results
        } finally {
            audio.delete()
        }
    }
}
