package org.altiro.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.util.concurrent.Executors
import org.altiro.inference.NativeProgress
import org.altiro.inference.NativeWhisper

/** One native call per process: CPU never enumerates Vulkan, and GPU failures stay here. */
class RecognitionWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var requestId = 0L
    private var cancelled = false
    private var runtime: NativeWhisper? = null
    private var handle = 0L
    private val binder =
        object : IRecognitionWorker.Stub() {
            override fun run(
                request: Long,
                model: String,
                audio: String,
                language: String,
                gpu: Boolean,
                flashAttention: Boolean,
                callback: IRecognitionCallback,
            ) {
                synchronized(lock) {
                    check(requestId == 0L)
                    requestId = request
                }
                executor.execute {
                    var status = 2
                    try {
                        val native = NativeWhisper()
                        synchronized(lock) {
                            runtime = native
                            handle = native.create()
                            if (cancelled) native.cancel(handle)
                        }
                        val text =
                            native.transcribe(
                                handle,
                                model,
                                audio,
                                language,
                                gpu,
                                flashAttention,
                                object : NativeProgress {
                                    override fun onProgress(percent: Int) =
                                        callback.onProgress(percent)

                                    override fun onPhase(phase: Int) = callback.onPhase(phase)

                                    override fun onRuntime(report: String) =
                                        callback.onRuntime(report)
                                },
                            )
                        if (text != null && !synchronized(lock) { cancelled }) {
                            // Bound Binder transactions; partial chunks never become an insertion
                            // payload.
                            for (chunk in text.chunked(8192)) callback.onTextChunk(chunk)
                            status = 0
                        } else {
                            status = 1
                        }
                    } catch (_: LinkageError) {
                        callback.onRuntime(
                            "{\"status\":\"FAILED\",\"failure\":\"NATIVE_UNAVAILABLE\"}"
                        )
                    } catch (_: Exception) {
                        // Native code has already supplied a typed failure when possible.
                    } finally {
                        synchronized(lock) {
                            if (handle != 0L) runtime?.release(handle)
                            handle = 0
                        }
                        try {
                            callback.onComplete(status)
                        } catch (_: Exception) {
                            Process.killProcess(Process.myPid())
                        }
                    }
                }
            }

            override fun cancel(request: Long) {
                synchronized(lock) {
                    if (requestId != request) return
                    cancelled = true
                    if (handle != 0L) runtime?.cancel(handle)
                }
            }

            override fun shutdown() {
                Process.killProcess(Process.myPid())
            }
        }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        // No native work may outlive its single parent-owned binding.
        Process.killProcess(Process.myPid())
        return false
    }
}
