package org.altiro.core

import java.io.File
import java.util.concurrent.CancellationException

/** Immutable model inputs prevent settings changes from altering an active recording. */
data class RecognitionInput(
    val spec: ModelSpec,
    val file: File,
    val backend: RecognitionBackend = RecognitionBackend.CPU,
)

data class TimedTranscript(
    val modelId: String,
    val text: String,
    val elapsedMillis: Long,
    val backend: RecognitionBackend = RecognitionBackend.CPU,
)

object RecognitionBatch {
    /** One owned recording, one model at a time, and no retained audio after any outcome. */
    fun run(
        audio: File,
        inputs: List<RecognitionInput>,
        cancelled: () -> Boolean,
        transcribe: (RecognitionInput) -> String?,
        clockNanos: () -> Long = System::nanoTime,
        phase: (RecognitionStage, String?) -> Unit = { _, _ -> },
        failed: () -> Unit = {},
    ): List<TimedTranscript> {
        try {
            require(inputs.size in 1..4 && inputs.map { it.spec.id to it.backend }.distinct().size == inputs.size)
            val results =
                inputs.map { input ->
                    if (cancelled()) throw CancellationException()
                    val started = clockNanos()
                    phase(RecognitionStage.VERIFYING, input.spec.id)
                    check(VerifiedModel.matches(input.file, input.spec, cancelled)) { "Model verification failed" }
                    val text = transcribe(input)
                    if (cancelled()) throw CancellationException()
                    TimedTranscript(input.spec.id, text.orEmpty().trim(), (clockNanos() - started) / 1_000_000, input.backend)
                }
            if (cancelled()) throw CancellationException()
            return results
        } catch (failure: Throwable) {
            if (!cancelled()) failed()
            throw failure
        } finally {
            phase(RecognitionStage.AUDIO_DELETE, null)
            audio.delete()
        }
    }
}
