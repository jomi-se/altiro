package org.altiro.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.RecognitionBatch
import org.altiro.core.RecognitionInput
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
        progress: (Int, String) -> Unit,
        complete: (Result<List<TimedTranscript>>) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper() && !busy.value)
        val operation = Operation(native.create())
        active = operation
        mutableBusy.value = true
        worker.execute {
            var index = 0
            val result =
                runCatching {
                    RecognitionBatch.run(audio, inputs, operation.cancelled::get, { input ->
                        val current = index++
                        main.post {
                            if (active === operation && !operation.cancelled.get()) progress(current * 100 / inputs.size, input.spec.id)
                        }
                        native.transcribe(
                            operation.handle,
                            input.file.absolutePath,
                            audio.absolutePath,
                            language,
                            NativeProgress { percent ->
                                main.post {
                                    if (active === operation && !operation.cancelled.get()) {
                                        progress((current * 100 + percent) / inputs.size, input.spec.id)
                                    }
                                }
                            },
                        )
                    })
                }
            // Every native context has been released before deleting the audio or completing.
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
