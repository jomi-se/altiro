package org.altiro.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.altiro.core.RecognitionBackend
import org.altiro.core.RecognitionBatch
import org.altiro.core.RecognitionDiagnostics
import org.altiro.core.RecognitionInput
import org.altiro.core.RecognitionStage
import org.altiro.core.RecognitionWindow
import org.altiro.core.RuntimeDetails
import org.altiro.core.RuntimeFailure
import org.altiro.core.RuntimeStatus
import org.altiro.core.TimedTranscript
import org.altiro.core.WorkerExitReason

/** One parent-owned batch; each native pass gets a fresh crash-contained worker process. */
class LocalRecognition(
    private val context: Context,
    private val checkpoint: RuntimeCheckpoint,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var active: Operation? = null

    private class WorkerTerminationFailure : IllegalStateException()

    private class WorkerDeadlineFailure : IllegalStateException()

    private class Operation(val cancelled: AtomicBoolean = AtomicBoolean()) {
        @Volatile var call: NativeCall? = null
        @Volatile var terminationUnconfirmed = false
        @Volatile var parentCompleted = false
    }

    private inner class NativeCall(
        private val input: RecognitionInput,
        private val audio: File,
        private val language: String,
        private val operation: Operation,
        private val diagnostics: RecognitionDiagnostics,
        private val progress: (Int) -> Unit,
        private val labCase: org.altiro.core.EncoderExperiment? = null,
        private val labReport: ((String) -> Unit)? = null,
    ) : ServiceConnection {
        private val connected = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val guard = Any()

        @Volatile private var remote: IRecognitionWorker? = null

        @Volatile private var binder: IBinder? = null

        @Volatile private var bound = false
        private var status = 2
        private var details =
            RuntimeDetails(
                input.spec.id,
                input.backend,
                window = input.window,
                engine = input.engine,
            )
        private val text = StringBuilder()
        private val startedWall = System.currentTimeMillis()
        private val death = IBinder.DeathRecipient {
            synchronized(guard) {
                if (finished.count != 0L) {
                    details =
                        if (operation.cancelled.get()) {
                            details.copy(status = RuntimeStatus.CANCELLED)
                        } else {
                            details.copy(
                                status = RuntimeStatus.WORKER_DIED,
                                failure = RuntimeFailure.WORKER_DIED,
                                workerExit = exitReason(),
                            )
                        }
                    publish()
                    if (!operation.cancelled.get()) diagnostics.markFailure()
                }
            }
            died.countDown()
            finished.countDown()
            connected.countDown()
            main.post {
                if (active === operation && operation.terminationUnconfirmed) {
                    operation.terminationUnconfirmed = false
                    if (operation.parentCompleted) {
                        active = null
                        mutableBusy.value = false
                    }
                }
            }
        }

        private fun publish() {
            diagnostics.runtime(details)
            checkpoint.save(diagnostics.report.value)
        }

        private fun exitReason(): WorkerExitReason =
            try {
                val record =
                    context
                        .getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(null, 0, 8)
                        .firstOrNull {
                            it.processName == "${context.packageName}:recognition" &&
                                it.timestamp >= startedWall
                        }
                when (record?.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> WorkerExitReason.LOW_MEMORY
                    ApplicationExitInfo.REASON_CRASH -> WorkerExitReason.CRASH
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> WorkerExitReason.NATIVE_CRASH
                    ApplicationExitInfo.REASON_SIGNALED -> WorkerExitReason.SIGNALED
                    ApplicationExitInfo.REASON_ANR -> WorkerExitReason.ANR
                    null -> WorkerExitReason.UNKNOWN
                    else -> WorkerExitReason.OTHER
                }
            } catch (_: Exception) {
                WorkerExitReason.UNKNOWN
            }

        private val callback =
            object : IRecognitionCallback.Stub() {
                override fun onProgress(percent: Int) {
                    progress(percent.coerceIn(0, 100))
                }

                override fun onPhase(phase: Int) {
                    synchronized(guard) {
                        if (died.count == 0L || finished.count == 0L) return
                        if (phase == 6) {
                            diagnostics.markFailure()
                        } else {
                            val stage =
                                when (phase) {
                                    1 -> RecognitionStage.AUDIO_READ
                                    2 -> RecognitionStage.MODEL_LOAD
                                    3 -> RecognitionStage.INFERENCE
                                    4 -> RecognitionStage.TEXT_ASSEMBLY
                                    5 -> RecognitionStage.MODEL_RELEASE
                                    7 -> RecognitionStage.GPU_PROBE
                                    else -> return
                                }
                            diagnostics.phase(stage, input.spec.id)
                        }
                        checkpoint.save(diagnostics.report.value)
                    }
                }

                override fun onRuntime(report: String) {
                    synchronized(guard) {
                        if (died.count == 0L || finished.count == 0L) return
                        if (labCase != null && org.json.JSONObject(report).optBoolean("lab")) {
                            labReport?.invoke(report)
                            return
                        }
                        details =
                            try {
                                runtimeDetails(details, report)
                            } catch (_: Exception) {
                                details.copy(
                                    status = RuntimeStatus.FAILED,
                                    failure = RuntimeFailure.INVALID_REPORT,
                                )
                            }
                        publish()
                    }
                }

                override fun onTextChunk(chunk: String) {
                    synchronized(guard) {
                        if (died.count == 0L || finished.count == 0L) return
                        check(chunk.length <= 8192 && text.length + chunk.length <= 1_048_576)
                        text.append(chunk)
                    }
                }

                override fun onComplete(value: Int) {
                    synchronized(guard) {
                        if (died.count == 0L || finished.count == 0L) return
                        status = value
                        if (value != 0 && details.failure == RuntimeFailure.NONE) {
                            details =
                                details.copy(
                                    status =
                                        if (value == 1) RuntimeStatus.CANCELLED
                                        else RuntimeStatus.FAILED,
                                    failure =
                                        if (value == 1) RuntimeFailure.NONE
                                        else RuntimeFailure.INFERENCE_FAILED,
                                )
                            publish()
                        }
                    }
                    finished.countDown()
                }
            }

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            binder = service
            try {
                service.linkToDeath(death, 0)
                remote = IRecognitionWorker.Stub.asInterface(service)
            } catch (_: Exception) {
                death.binderDied()
            }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            death.binderDied()
        }

        override fun onBindingDied(name: ComponentName) {
            death.binderDied()
        }

        override fun onNullBinding(name: ComponentName) {
            death.binderDied()
        }

        fun run(): String? {
            diagnostics.phase(RecognitionStage.WORKER_START, input.spec.id)
            publish()
            main.post {
                try {
                    bound =
                        context.bindService(
                            Intent(context, RecognitionWorkerService::class.java),
                            this,
                            Context.BIND_AUTO_CREATE,
                        )
                } catch (_: Exception) {
                    bound = false
                }
                if (!bound) connected.countDown()
            }
            try {
                check(connected.await(20, TimeUnit.SECONDS) && remote != null && died.count != 0L)
                if (!operation.cancelled.get()) {
                    remote!!.run(
                        1,
                        input.engine.name,
                        input.file.absolutePath,
                        audio.absolutePath,
                        language,
                        input.backend == RecognitionBackend.VULKAN,
                        input.flashAttention,
                        input.window == RecognitionWindow.DYNAMIC,
                        input.vocabulary.prompt,
                        labCase?.wire.orEmpty(),
                        callback,
                    )
                    if (operation.cancelled.get()) remote!!.cancel(1)
                    if (!finished.await(if (labCase == null) 600 else 120, TimeUnit.SECONDS)) {
                        cancel()
                        throw WorkerDeadlineFailure()
                    }
                    check(
                        (status == 0 &&
                            synchronized(guard) { details.failure == RuntimeFailure.NONE }) ||
                            operation.cancelled.get()
                    )
                } else {
                    synchronized(guard) {
                        details = details.copy(status = RuntimeStatus.CANCELLED)
                        publish()
                    }
                    finished.countDown()
                }
                if (operation.cancelled.get()) return null
                return synchronized(guard) { text.toString() }
            } catch (failure: Exception) {
                if (!operation.cancelled.get()) diagnostics.markFailure()
                synchronized(guard) {
                    if (details.failure == RuntimeFailure.NONE && !operation.cancelled.get()) {
                        val code =
                            if (remote == null) {
                                RuntimeFailure.WORKER_START_FAILED
                            } else {
                                RuntimeFailure.IPC_FAILED
                            }
                        details = details.copy(status = RuntimeStatus.FAILED, failure = code)
                        publish()
                    }
                }
                throw failure
            } finally {
                diagnostics.phase(RecognitionStage.WORKER_STOP, input.spec.id)
                checkpoint.save(diagnostics.report.value)
                val unbound = CountDownLatch(1)
                main.post {
                    unbindOnMain()
                    unbound.countDown()
                }
                unbound.await()
                // Unbind before killing so Android cannot automatically restart
                // this bound service between CPU/GPU passes. Binder death remains
                // linked independently of the now-removed ServiceConnection.
                terminate()
                if (binder != null && !died.await(10, TimeUnit.SECONDS)) {
                    operation.terminationUnconfirmed = true
                    // Close the race between await returning and death arriving.
                    if (died.count == 0L) operation.terminationUnconfirmed = false
                    if (operation.terminationUnconfirmed) throw WorkerTerminationFailure()
                }
            }
        }

        fun failureCode(): String =
            synchronized(guard) {
                if (details.failure == RuntimeFailure.WORKER_DIED)
                    "WORKER_DIED_${details.workerExit.name}"
                else if (labCase != null && details.failure == RuntimeFailure.NONE)
                    "LAB_SETUP_FAILED"
                else details.failure.name
            }

        fun cancel() {
            try {
                remote?.cancel(1)
            } catch (_: Exception) {}
            main.postDelayed(
                {
                    if (finished.count != 0L && operation.call === this) {
                        synchronized(guard) {
                            details =
                                details.copy(
                                    status = RuntimeStatus.CANCELLED,
                                    failure = RuntimeFailure.CANCEL_TIMEOUT,
                                )
                            publish()
                        }
                        unbindOnMain()
                        terminate()
                    }
                },
                10_000,
            )
        }

        private fun unbindOnMain() {
            if (!bound) return
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {}
            bound = false
        }

        private fun terminate() {
            try {
                remote?.shutdown()
            } catch (_: Exception) {}
        }
    }

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
        val operation = Operation()
        active = operation
        mutableBusy.value = true
        diagnostics.phase(RecognitionStage.QUEUED)
        executor.execute {
            var index = 0
            val result = runCatching {
                RecognitionBatch.run(
                    audio,
                    inputs,
                    operation.cancelled::get,
                    { input ->
                        val current = index++
                        val call =
                            NativeCall(
                                input,
                                audio,
                                language,
                                operation,
                                diagnostics,
                                progress = { percent ->
                                    main.post {
                                        if (active === operation && !operation.cancelled.get()) {
                                            progress(
                                                (current * 100 + percent) / inputs.size,
                                                input.spec.id,
                                            )
                                        }
                                    }
                                },
                            )
                        operation.call = call
                        try {
                            call.run()
                        } finally {
                            if (!operation.terminationUnconfirmed) operation.call = null
                        }
                    },
                    phase = { stage, modelId ->
                        diagnostics.selectBackend(
                            if (modelId == null) null else inputs[index].backend,
                            if (modelId == null) null else inputs[index].window,
                        )
                        diagnostics.phase(stage, modelId)
                        checkpoint.save(diagnostics.report.value)
                        if (modelId != null) {
                            main.post {
                                if (active === operation && !operation.cancelled.get())
                                    progress(index * 100 / inputs.size, modelId)
                            }
                        }
                    },
                    failed = diagnostics::markFailure,
                )
            }
            diagnostics.phase(RecognitionStage.WORKER_RELEASE)
            main.post {
                finishOperation(operation) {
                    complete(if (operation.cancelled.get()) Result.success(emptyList()) else result)
                    checkpoint.save(diagnostics.report.value)
                }
            }
        }
    }

    fun benchmark(
        audio: File,
        inputs: List<RecognitionInput>,
        diagnostics: RecognitionDiagnostics,
        lab: EncoderLab,
        mode: Int,
        profile: Boolean,
        update: (Int, String) -> Unit,
        complete: (Result<List<TimedTranscript>>) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper() && !busy.value)
        val operation = Operation()
        val reverse = lab.reverseNextSuite()
        val orderedInputs = if (reverse) inputs.reversed() else inputs
        val cases =
            org.altiro.core.EncoderExperiment.suite(
                mode == 2,
                profile,
                reverse,
                mode == 3,
                mode == 4,
                mode == 5,
                mode == 6,
            )
        val plannedCases = orderedInputs.sumOf { input ->
            cases.count { it.appliesTo(input.spec.id) }
        }
        lab.begin(orderedInputs, cases, reverse)
        active = operation
        mutableBusy.value = true
        executor.execute {
            var result = runCatching {
                require(audio.length() in 46..960044) { "Lab audio must be at most 30 seconds" }
                var done = 0
                for (input in orderedInputs) {
                    check(
                        org.altiro.core.VerifiedModel.matches(
                            input.file,
                            input.spec,
                            operation.cancelled::get,
                        )
                    )
                    for (case in cases.filter { it.appliesTo(input.spec.id) }) {
                        if (operation.cancelled.get()) break
                        if (case.id == "gpu-cache-empty")
                            check(
                                java.io.File(context.cacheDir, "encoder-lab-vulkan").let {
                                    !it.exists() || it.deleteRecursively()
                                }
                            )
                        val current = input.copy(backend = case.backend)
                        lab.startCase(input.spec.id, case)
                        if (
                            case.id == "gpu-cache-seeded" &&
                                java.io
                                    .File(context.cacheDir, "encoder-lab-vulkan")
                                    .listFiles()
                                    ?.any {
                                        it.isFile && it.name.endsWith(".pcache") && it.length() > 0
                                    } != true
                        ) {
                            lab.endCase("SKIPPED", "CACHE_SEED_UNAVAILABLE")
                            done++
                            continue
                        }
                        val caseIndex = done
                        main.post { update(caseIndex * 100 / plannedCases, case.id) }
                        diagnostics.selectBackend(current.backend, current.window)
                        val call =
                            NativeCall(
                                current,
                                audio,
                                "auto",
                                operation,
                                diagnostics,
                                progress = {},
                                labCase = case,
                                labReport = { lab.accept(it) },
                            )
                        operation.call = call
                        try {
                            call.run()
                            lab.endCase(if (operation.cancelled.get()) "CANCELLED" else "FINISHED")
                        } catch (failure: Exception) {
                            lab.endCase(
                                if (operation.cancelled.get()) "CANCELLED" else "WORKER_FAILED",
                                if (failure is WorkerDeadlineFailure) "CASE_DEADLINE"
                                else call.failureCode(),
                            )
                            if (failure is WorkerTerminationFailure) throw failure
                        } finally {
                            if (!operation.terminationUnconfirmed) operation.call = null
                        }
                        done++
                    }
                }
                emptyList<TimedTranscript>()
            }
            diagnostics.selectBackend(null)
            audio.delete()
            try {
                lab.finish(
                    if (operation.cancelled.get()) "CANCELLED"
                    else if (result.isFailure) "FAILED" else "FINISHED"
                )
            } catch (failure: Exception) {
                result = Result.failure(failure)
            }
            main.post {
                finishOperation(operation) {
                    complete(if (operation.cancelled.get()) Result.success(emptyList()) else result)
                }
            }
        }
    }

    private fun finishOperation(operation: Operation, completion: () -> Unit) {
        try {
            completion()
        } finally {
            operation.parentCompleted = true
            if (!operation.terminationUnconfirmed && active === operation) {
                active = null
                mutableBusy.value = false
            }
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active?.let {
            it.cancelled.set(true)
            it.call?.cancel()
        }
    }
}
