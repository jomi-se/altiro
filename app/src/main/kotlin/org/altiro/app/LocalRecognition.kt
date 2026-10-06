package org.altiro.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.RecognitionBatch
import org.altiro.core.RecognitionDiagnostics
import org.altiro.core.RecognitionInput
import org.altiro.core.RecognitionStage
import org.altiro.core.TimedTranscript
import org.altiro.inference.NativeProgress
import org.altiro.inference.NativeWhisper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One native worker. Cancellation never frees handles underneath inference. */
class LocalRecognition {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val native by lazy { NativeWhisper() }
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var active: Operation? = null

    private class Operation(
        val handle: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(),
    )

    fun transcribe(
        audio: File,
        inputs: List<RecognitionInput>,
        language: String,
        diagnostics: RecognitionDiagnostics,
        progress: (Int, String) -> Unit,
        complete: (Result<List<TimedTranscript>>) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper() && !busy.value)
        diagnostics.phase(RecognitionStage.RUNTIME_START)
        val operation = Operation(native.create())
        active = operation
        mutableBusy.value = true
        diagnostics.phase(RecognitionStage.QUEUED)
        worker.execute {
            var index = 0
            val result =
                runCatching {
                    RecognitionBatch.run(audio, inputs, operation.cancelled::get, { input ->
                        val current = index++
                        var modelPercent = 0
                        main.post {
                            if (active === operation && !operation.cancelled.get()) progress(current * 100 / inputs.size, input.spec.id)
                        }
                        native.transcribe(
                            operation.handle,
                            input.file.absolutePath,
                            audio.absolutePath,
                            language,
                            object : NativeProgress {
                                override fun onProgress(percent: Int) {
                                    modelPercent = percent
                                    val overall = (current * 100 + percent) / inputs.size
                                    main.post {
                                        if (active === operation && !operation.cancelled.get()) {
                                            progress(overall, input.spec.id)
                                        }
                                    }
                                }

                                override fun onPhase(phase: Int) {
                                    if (phase == 6) {
                                        diagnostics.markFailure()
                                        return
                                    }
                                    val stage =
                                        when (phase) {
                                            1 -> RecognitionStage.AUDIO_READ
                                            2 -> RecognitionStage.MODEL_LOAD
                                            3 -> RecognitionStage.INFERENCE
                                            4 -> RecognitionStage.TEXT_ASSEMBLY
                                            5 -> RecognitionStage.MODEL_RELEASE
                                            else -> return
                                        }
                                    diagnostics.phase(stage, input.spec.id)
                                    val overall = (current * 100 + modelPercent) / inputs.size
                                    main.post {
                                        if (active === operation && !operation.cancelled.get()) {
                                            progress(overall, input.spec.id)
                                        }
                                    }
                                }
                            },
                        )
                    }, phase = { stage, modelId ->
                        diagnostics.phase(stage, modelId)
                        val overall = index * 100 / inputs.size
                        if (modelId != null) {
                            main.post {
                                if (active === operation && !operation.cancelled.get()) progress(overall, modelId)
                            }
                        }
                    }, failed = diagnostics::markFailure)
                }
            // Every native context has been released before deleting the audio or completing.
            diagnostics.phase(RecognitionStage.WORKER_RELEASE)
            native.release(operation.handle)
            main.post {
                if (active === operation) active = null
                mutableBusy.value = false
                complete(if (operation.cancelled.get()) Result.success(emptyList()) else result)
            }
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active?.let {
            it.cancelled.set(true)
            native.cancel(it.handle)
        }
    }
}
