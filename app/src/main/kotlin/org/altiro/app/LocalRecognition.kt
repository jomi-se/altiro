package org.altiro.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.VerifiedModel
import org.altiro.inference.NativeProgress
import org.altiro.inference.NativeWhisper
import java.io.File
import java.util.concurrent.CancellationException
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
        model: ModelStore,
        language: String,
        progress: (Int) -> Unit,
        complete: (Result<String?>) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper() && !busy.value)
        val operation = Operation(native.create())
        active = operation
        mutableBusy.value = true
        worker.execute {
            val result =
                runCatching {
                    check(VerifiedModel.matches(model.file, model.spec, operation.cancelled::get)) { "Model verification failed" }
                    if (operation.cancelled.get()) throw CancellationException()
                    native.transcribe(
                        operation.handle,
                        model.file.absolutePath,
                        audio.absolutePath,
                        language,
                        NativeProgress { percent ->
                            main.post {
                                if (active === operation &&
                                    !operation.cancelled.get()
                                ) {
                                    progress(percent)
                                }
                            }
                        },
                    )
                }
            // Native transcribe returns only after its context and buffers are released.
            native.release(operation.handle)
            audio.delete()
            main.post {
                if (active === operation) active = null
                mutableBusy.value = false
                complete(if (operation.cancelled.get()) Result.success(null) else result)
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
